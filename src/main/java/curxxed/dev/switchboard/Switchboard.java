package curxxed.dev.switchboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import curxxed.dev.switchboard.app.Log;
import curxxed.dev.switchboard.bridge.AdminCommands;
import curxxed.dev.switchboard.bridge.ApprovalDesk;
import curxxed.dev.switchboard.bridge.HandoffPolicy;
import curxxed.dev.switchboard.bridge.ModelCatalog;
import curxxed.dev.switchboard.bridge.ProjectResolver;
import curxxed.dev.switchboard.bridge.QuestionDesk;
import curxxed.dev.switchboard.bridge.T3AgentRunner;
import curxxed.dev.switchboard.bridge.TelegramProgress;
import curxxed.dev.switchboard.app.RelayApp;
import curxxed.dev.switchboard.config.AppConfig;
import curxxed.dev.switchboard.config.SwitchboardHome;
import curxxed.dev.switchboard.dispatch.CircuitBreaker;
import curxxed.dev.switchboard.dispatch.Dispatcher;
import curxxed.dev.switchboard.dispatch.Turn;
import curxxed.dev.switchboard.dispatch.TelegramOutbox;
import curxxed.dev.switchboard.store.InvocationLog;
import curxxed.dev.switchboard.store.Settings;
import curxxed.dev.switchboard.t3.T3Probe;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Switchboard {

    private static final AtomicBoolean SHUTDOWN_REQUESTED = new AtomicBoolean();

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0] : "help";
        Path configPath = args.length > 1 ? Path.of(args[1])
                : SwitchboardHome.defaultConfig(SwitchboardHome.root(), Path.of(""));
        if (command.equals("run") || command.equals("probe")) SwitchboardHome.initialize();

        switch (command) {
            case "probe" -> System.exit(probe(configPath) ? 0 : 1);
            case "run" -> {
                int code = run(configPath);
                // After a signal the JVM is already exiting; System.exit inside shutdown blocks forever.
                if (!SHUTDOWN_REQUESTED.get()) System.exit(code);
            }
            default -> {
                System.out.println("usage: switchboard <probe|run> [config.json]");
                System.out.println("default config: ~/.switchboard/configs/config.json (falls back to ./config.json)");
                System.exit(command.equals("help") ? 0 : 2);
            }
        }
    }

    private static int run(Path configPath) throws Exception {
        AppConfig config;
        try {
            config = AppConfig.load(configPath);
        } catch (IllegalArgumentException e) {
            Log.warn(e.getMessage());
            return 2;
        }
        RelayApp app;
        try {
            app = RelayApp.start(config, Clock.systemUTC(), services -> dispatcher(services, configPath));
        } catch (IOException e) {
            Log.warn("startup failed: " + e.getMessage());
            return 1;
        } catch (UncheckedIOException e) {
            Log.warn("startup failed: " + e.getCause().getMessage());
            return 1;
        }
        CountDownLatch stopped = new CountDownLatch(1);
        Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            SHUTDOWN_REQUESTED.set(true);
            Log.info("shutting down");
            mainThread.interrupt();
            try {
                stopped.await();
            } catch (InterruptedException ignored) {
                // JVM is exiting anyway.
            }
        }, "shutdown"));
        try {
            app.awaitShutdown();
        } catch (InterruptedException ignored) {
            // Ctrl-C: fall through to close.
        } finally {
            app.close();
            stopped.countDown();
        }
        return 0;
    }

    private static Dispatcher dispatcher(RelayApp.Services s, Path configPath) {
        AppConfig.Limits limits = s.config().effectiveLimits();
        CircuitBreaker breaker = new CircuitBreaker(limits, s.clock());
        HandoffPolicy handoffs = new HandoffPolicy() {
            @Override
            public boolean live(Turn turn) {
                try {
                    return s.settings().mode() == Settings.Mode.AUTONOMOUS || breaker.isDiscussion(turn.chainId());
                } catch (SQLException e) {
                    return false;
                }
            }

            @Override
            public int hopsLeft(Turn turn) {
                return Math.max(0, limits.depth() - turn.depth());
            }
        };
        ProjectResolver projects = new ProjectResolver(s.t3(), s.t3Session()::bearer);
        try {
            // Fail at startup, not on the first message, if the configured project doesn't exist.
            ProjectResolver.Project p = projects.resolve(s.settings().activeProjectId().orElse(s.config().t3().project()));
            Log.info("agents work in T3 project '" + p.title() + "' (" + p.workspaceRoot() + "), "
                    + (p.usesWorktrees() ? "one worktree each off " + p.gitBranch() : "not a git repo, so no per-agent worktrees")
                    + "; permissions: " + s.config().t3().effectiveRuntimeMode());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        ApprovalDesk approvals = new ApprovalDesk(s.bots(), ApprovalDesk.viaT3(s.t3Rpc(), s.mapper()), s.directory());
        QuestionDesk questions = new QuestionDesk(s.bots(), QuestionDesk.viaT3(s.t3Rpc(), s.mapper()), s.directory());
        T3AgentRunner runner = new T3AgentRunner(s.config(), s.agents(), s.ledger(), s.settings(),
                projects, s.t3Rpc(), new TelegramProgress(s.bots(), s.clock()),
                handoffs, approvals, questions, s.mapper(), s.clock());
        Dispatcher dispatcher = new Dispatcher(s.settings(), breaker, new InvocationLog(s.db(), s.clock()), s.usage().track(runner),
                new TelegramOutbox(s.bots(), s.directory(), s.ledger(), s.clock()), s.directory(), s.clock());
        dispatcher.setCommands(new AdminCommands(s.config(), s.agents(), s.settings(), projects,
                new ModelCatalog(ModelCatalog.defaultManifest(), s.mapper()), s.directory(), s.health(), s.t3Session(),
                configPath, s.clock(), s.usage()));
        dispatcher.setButtons((press, via) -> {
            if (press.data().startsWith("qa:")) questions.press(press, via);
            else approvals.press(press, via);
        });
        dispatcher.setIntercept(questions::consumeReply);
        return dispatcher;
    }

    private static boolean probe(Path configPath) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // The probe needs only the t3 section, so it runs before the Telegram bots are configured.
        String baseUrl = null;
        String pairingToken = null;
        Path stateDir = SwitchboardHome.state();
        if (Files.exists(configPath)) {
            AppConfig.T3 t3 = mapper.readValue(Files.readString(configPath), AppConfig.class).t3();
            if (t3 != null) {
                baseUrl = t3.baseUrl();
                pairingToken = t3.pairingToken();
                stateDir = t3.stateDirPath();
            }
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return new T3Probe(RelayApp.newHttpClient(executor), mapper, System.out).run(baseUrl, pairingToken, stateDir);
        }
    }
}
