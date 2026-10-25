package curxxed.dev.switchboard.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import curxxed.dev.switchboard.app.RelayApp;
import curxxed.dev.switchboard.config.AppConfig;
import curxxed.dev.switchboard.dispatch.AgentRunner;
import curxxed.dev.switchboard.dispatch.CircuitBreaker;
import curxxed.dev.switchboard.dispatch.Dispatcher;
import curxxed.dev.switchboard.dispatch.Outbox;
import curxxed.dev.switchboard.gateway.AgentDirectory;
import curxxed.dev.switchboard.gateway.BotHealth;
import curxxed.dev.switchboard.gateway.Command;
import curxxed.dev.switchboard.gateway.MentionParser;
import curxxed.dev.switchboard.gateway.MessageEnvelope;
import curxxed.dev.switchboard.store.AgentStore;
import curxxed.dev.switchboard.store.Database;
import curxxed.dev.switchboard.store.InvocationLog;
import curxxed.dev.switchboard.store.Settings;
import curxxed.dev.switchboard.t3.CredentialStore;
import curxxed.dev.switchboard.t3.T3Auth;
import curxxed.dev.switchboard.t3.T3Http;
import curxxed.dev.switchboard.t3.T3Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class AdminCommandsTest {

    static final long GROUP = -1001234567890L;
    static final long ADMIN = 555;
    static final AgentDirectory DIRECTORY = new AgentDirectory(List.of(
            new AgentDirectory.AgentBot("claude", "Claude", 101, "ClaudeRelayBot"),
            new AgentDirectory.AgentBot("codex", "Codex", 102, "CodexRelayBot"),
            new AgentDirectory.AgentBot("gemini", "Gemini", 103, "GeminiRelayBot")));

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> notices = new CopyOnWriteArrayList<>();
    private final List<String> started = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);
    private HttpServer t3;
    private ExecutorService executor;
    private Database db;
    private Settings settings;
    private AgentStore agents;
    private BotHealth health;
    private Dispatcher dispatcher;
    private volatile CountDownLatch block = new CountDownLatch(0);
    private Path configFile;
    private T3Session session;

    @BeforeEach
    void setUp() throws Exception {
        t3 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Path repo = Files.createDirectories(dir.resolve("ridgeline"));
        String projects = """
                {"projects": [
                  {"id": "p-scratch", "title": "No project", "workspaceRoot": "%s", "deletedAt": null},
                  {"id": "p-ridge", "title": "Ridgeline", "workspaceRoot": "%s", "deletedAt": null},
                  {"id": "p-gone", "title": "Old", "workspaceRoot": "/x", "deletedAt": "2026-01-01T00:00:00Z"}]}"""
                .formatted(dir.resolve("scratch"), repo);
        t3.createContext("/api/projects", ex -> {
            byte[] b = projects.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("content-type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        t3.createContext("/oauth/token", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean ok = body.contains("subject_token=FRESH");
            byte[] b = (ok ? "{\"access_token\":\"bearer-2\",\"token_type\":\"Bearer\",\"expires_in\":2592000,\"scope\":\"orchestration:read orchestration:operate\"}"
                           : "{\"_tag\":\"EnvironmentAuthInvalidError\",\"reason\":\"invalid_credential\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("content-type", "application/json");
            ex.sendResponseHeaders(ok ? 200 : 401, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        t3.start();
        executor = Executors.newVirtualThreadPerTaskExecutor();
        T3Http http = new T3Http(URI.create("http://127.0.0.1:" + t3.getAddress().getPort()), RelayApp.newHttpClient(executor), mapper);

        Path manifest = dir.resolve("model-manifest.json");
        Files.writeString(manifest, """
                {"manifest": {"providers": {
                  "codex": {"models": [
                    {"slug": "gpt-6.1-sol", "name": "GPT-6.1 Sol", "status": "current"},
                    {"slug": "gpt-6-astra", "name": "GPT-6 Astra", "status": "current"},
                    {"slug": "gpt-6-sol", "name": "GPT-6 Sol", "status": "legacy"}]},
                  "antigravity": {"models": [
                    {"slug": "gemini-3.8-flash-high", "name": "Gemini 3.8 Flash (High)", "status": "current"},
                    {"slug": "gemini-3.8-flash-low", "name": "Gemini 3.8 Flash (Low)", "status": "current"}]}}}}""");

        db = Database.inMemory();
        settings = new Settings(db);
        agents = new AgentStore(db, Clock.systemUTC());
        health = new BotHealth(Clock.systemUTC());
        Map<String, AppConfig.Agent> configuredAgents = new LinkedHashMap<>();
        configuredAgents.put("claude", new AppConfig.Agent("1:a", "claudeAgent", "claude-opus-5-5"));
        configuredAgents.put("codex", new AppConfig.Agent("2:b", "codex_0a1b2c3d", "gpt-6.1-sol"));
        configuredAgents.put("gemini", new AppConfig.Agent("3:c", "antigravity", "gemini-3.8-flash-high"));
        AppConfig config = new AppConfig(new AppConfig.Telegram(GROUP, ADMIN, null, null),
                new AppConfig.T3(null, null, "state", "No project", null, null, null),
                configuredAgents,
                null);
        Outbox outbox = new Outbox() {
            @Override
            public long postReply(String agentKey, long chatId, long replyTo, String text) {
                return ids.incrementAndGet();
            }

            @Override
            public void notice(String agentKey, long chatId, long replyTo, String text) {
                notices.add(text);
            }
        };
        AgentRunner runner = turn -> {
            started.add(turn.agentKey());
            block.await(); // interruptible: this is how /stopall cancels a turn
            return turn.agentKey().equals("claude") ? "Done, over to @Codex." : "ok";
        };
        dispatcher = new Dispatcher(settings, new CircuitBreaker(new AppConfig.Limits(5, 8, 3, 0, 12), Clock.systemUTC()),
                new InvocationLog(db, Clock.systemUTC()), runner, outbox, DIRECTORY, Clock.systemUTC());
        configFile = dir.resolve("config.json");
        Files.writeString(configFile, "{\"t3\": {\"pairingToken\": null}}");
        session = new T3Session(new T3Auth(http, new CredentialStore(dir.resolve("state"), mapper), Clock.systemUTC()),
                new T3Auth.Bearer("bearer-1", false, "s", Instant.now().plus(Duration.ofDays(20)).getEpochSecond()));
        dispatcher.setCommands(new AdminCommands(config, agents, settings, new ProjectResolver(http, session::bearer),
                new ModelCatalog(manifest, mapper), DIRECTORY, health, session, configFile, Clock.systemUTC()));
    }

    @AfterEach
    void tearDown() throws Exception {
        block.countDown();
        dispatcher.close();
        t3.stop(0);
        executor.close();
        db.close();
    }

    private String run(String text) throws Exception {
        int before = notices.size();
        long id = ids.incrementAndGet();
        dispatcher.handle(new MessageEnvelope(GROUP, id, Instant.now(),
                new MessageEnvelope.Sender(ADMIN, "Admin", null, false, true, null), text,
                new MentionParser(DIRECTORY).mentionedAgents(text, true), null, Command.parse(text).orElse(null), 0,
                MessageEnvelope.rootChainId(GROUP, id), "claude"), null);
        return notices.size() > before ? notices.get(notices.size() - 1) : null;
    }

    @Test
    void agentsShowsModeProjectHealthWorkAndModels() throws Exception {
        health.polled("claude");
        health.polled("codex");
        health.failed("gemini", "token rejected by Telegram", true);
        agents.setModelOverride("codex", "gpt-6-astra");
        block = new CountDownLatch(1);
        run("@Claude long task");
        long deadline = System.currentTimeMillis() + 5000;
        while (started.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10);

        String report = run("/agents");
        assertTrue(report.startsWith("Mode: manual · Project: No project"), report);
        assertTrue(report.contains("Claude (@ClaudeRelayBot): online, working for"), report);
        assertTrue(report.contains("Codex (@CodexRelayBot): online, idle"), report);
        assertTrue(report.contains("codex_0a1b2c3d · gpt-6-astra (set with /model; default gpt-6.1-sol)"), report);
        assertTrue(report.contains("Gemini (@GeminiRelayBot): offline (token rejected by Telegram), idle"), report);
        assertTrue(report.contains("claudeAgent · claude-opus-5-5"), report);
    }

    @Test
    void stopAllInterruptsRunningTurnsAndNothingCascades() throws Exception {
        settings.setMode(Settings.Mode.AUTONOMOUS);
        block = new CountDownLatch(1);
        run("@Claude go");
        run("@Gemini go too");
        long deadline = System.currentTimeMillis() + 5000;
        while (started.size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10);

        String reply = run("/stopall");
        assertTrue(reply.startsWith("Stopped 2 running turns ("), reply);
        assertTrue(reply.contains("Claude") && reply.contains("Gemini"), reply);
        block.countDown();
        long idle = System.currentTimeMillis() + 5000;
        while (!dispatcher.running().isEmpty() && System.currentTimeMillis() < idle) Thread.sleep(10);
        assertTrue(dispatcher.running().isEmpty());
        Thread.sleep(300);
        assertFalse(started.contains("codex"), "Claude's hand-off to Codex must not survive /stopall");
        assertTrue(dispatcher.breaker().snapshot().busyAgents().isEmpty());

        assertEquals("Nothing was running.", run("/stopall"));
        run("@Claude again");
        long again = System.currentTimeMillis() + 5000;
        while (started.size() < 4 && System.currentTimeMillis() < again) Thread.sleep(10);
        assertEquals(List.of("claude", "codex"), started.subList(2, started.size()),
                "a new chain after /stopall works and hands off normally");
    }

    @Test
    void modelSwitchesByShortNameAndResets() throws Exception {
        assertTrue(run("/model").contains("Codex: gpt-6.1-sol"));
        assertEquals("Codex uses gpt-6.1-sol.\nCurrent models: gpt-6.1-sol, gpt-6-astra", run("/model codex"));

        assertEquals("Codex now uses GPT-6 Astra (gpt-6-astra) from its next turn.", run("/model codex astra"));
        assertEquals("gpt-6-astra", agents.modelOverride("codex").orElseThrow());
        assertEquals("Codex now uses GPT-6.1 Sol (gpt-6.1-sol) from its next turn.", run("/model @Codex sol"),
                "'sol' prefers the current generation over legacy gpt-6-sol");
        assertTrue(agents.modelOverride("codex").isEmpty(), "choosing the default clears the override (seen live in /agents)");
        assertTrue(run("/model").contains("Codex: gpt-6.1-sol\n"), "no '(set with /model)' for the default");

        assertTrue(run("/model gemini flash").startsWith("'flash' matches several models: gemini-3.8-flash-high, gemini-3.8-flash-low"));
        assertTrue(run("/model codex gpt-9").startsWith("T3 has no 'gpt-9' model for Codex. Current models: gpt-6.1-sol"));
        assertEquals("No agent called 'gpt'. Use claude, codex or gemini.", run("/model gpt sol"));

        assertEquals("Codex is back on its default, gpt-6.1-sol, from its next turn.", run("/model codex default"));
        assertTrue(agents.modelOverride("codex").isEmpty());
        assertTrue(run("/model claude opus").startsWith("Couldn't read T3's model list"),
                "no claudeAgent models in this manifest, so nothing is changed blindly");
    }

    @Test
    void projectListsSwitchesAndResets() throws Exception {
        String shown = run("/project");
        assertTrue(shown.startsWith("Agents work in No project ("), shown);
        assertTrue(shown.contains("Projects: No project, Ridgeline"), shown);
        assertFalse(shown.contains("Old"), "deleted projects aren't offered");

        String switched = run("/project ridgeline");
        assertTrue(switched.startsWith("Agents now work in Ridgeline ("), switched);
        assertTrue(switched.endsWith("Claude, Codex, Gemini start new T3 threads there on their next turn."), switched);
        assertEquals("p-ridge", settings.activeProjectId().orElseThrow());

        assertTrue(run("/project nope").startsWith("No T3 project with id or title 'nope'"));
        assertEquals("p-ridge", settings.activeProjectId().orElseThrow(), "a typo doesn't change anything");

        assertTrue(run("/project default").startsWith("Back to the configured project: No project"));
        assertTrue(settings.activeProjectId().isEmpty());
    }

    @Test
    void reauthSwapsTheCredentialFromConfig() throws Exception {
        assertTrue(run("/agents").contains("T3 credential valid until "), "the expiry shows in /agents");
        assertTrue(run("/reauth").startsWith("No t3.pairingToken in config.json."));

        Files.writeString(configFile, "{\"t3\": {\"pairingToken\": \"STALE\"}}");
        assertTrue(run("/reauth").startsWith("T3 didn't accept that pairing token"));
        assertEquals("bearer-1", session.bearer(), "a refused token leaves the working credential alone");

        Files.writeString(configFile, "{\"t3\": {\"pairingToken\": \"FRESH\"}}");
        String reply = run("/reauth");
        assertTrue(reply.startsWith("Switchboard has a new T3 credential. It's valid until "), reply);
        assertEquals("bearer-2", session.bearer(), "every T3 caller now uses the new bearer");
        assertTrue(Files.exists(dir.resolve("state").resolve("t3-credential.json")), "and it's stored for restarts");
    }

    @Test
    void helpListsCommands() throws Exception {
        String help = run("/help");
        assertTrue(help.contains("/stopall") && help.contains("@everyone"), help);
    }

    @Test
    void driverIsTheInstancePrefix() {
        assertEquals("codex", ModelCatalog.driverOf("codex_0a1b2c3d-0000-4000-8000-000000000000"));
        assertEquals("claudeAgent", ModelCatalog.driverOf("claudeAgent"));
    }
}
