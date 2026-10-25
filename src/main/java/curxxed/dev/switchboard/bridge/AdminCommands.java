package curxxed.dev.switchboard.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import curxxed.dev.switchboard.app.Log;
import curxxed.dev.switchboard.config.AppConfig;
import curxxed.dev.switchboard.dispatch.Dispatcher;
import curxxed.dev.switchboard.gateway.AgentDirectory;
import curxxed.dev.switchboard.gateway.BotHealth;
import curxxed.dev.switchboard.gateway.Command;
import curxxed.dev.switchboard.gateway.MessageEnvelope;
import curxxed.dev.switchboard.store.AgentStore;
import curxxed.dev.switchboard.store.Settings;
import curxxed.dev.switchboard.t3.T3Auth;
import curxxed.dev.switchboard.t3.T3Session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@code /agents}, {@code /stopall}, {@code /model}, {@code /project} and {@code /help}. The
 * gateway has already refused admin-only commands from anyone but the admin.
 */
public final class AdminCommands implements Dispatcher.Commands {

    /** A poller that hasn't heard from Telegram for this long is reported as not responding. */
    static final Duration STALE_POLL = Duration.ofMinutes(2);

    private final AppConfig config;
    private final AgentStore agents;
    private final Settings settings;
    private final ProjectResolver projects;
    private final ModelCatalog catalog;
    private final AgentDirectory directory;
    private final BotHealth health;
    private final T3Session session;
    private final Path configPath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;
    private final UsageMonitor usage;

    /** @param configPath re-read by {@code /reauth} for a fresh {@code t3.pairingToken} */
    public AdminCommands(AppConfig config, AgentStore agents, Settings settings, ProjectResolver projects,
                         ModelCatalog catalog, AgentDirectory directory, BotHealth health, T3Session session,
                         Path configPath, Clock clock) {
        this(config, agents, settings, projects, catalog, directory, health, session, configPath, clock, null);
    }

    public AdminCommands(AppConfig config, AgentStore agents, Settings settings, ProjectResolver projects,
                         ModelCatalog catalog, AgentDirectory directory, BotHealth health, T3Session session,
                         Path configPath, Clock clock, UsageMonitor usage) {
        this.config = config;
        this.agents = agents;
        this.settings = settings;
        this.projects = projects;
        this.catalog = catalog;
        this.directory = directory;
        this.health = health;
        this.session = session;
        this.configPath = configPath;
        this.clock = clock;
        this.usage = usage;
    }

    @Override
    public String handle(Command command, MessageEnvelope envelope, Dispatcher dispatcher) throws Exception {
        return switch (command.name()) {
            case "agents" -> agentsReport(dispatcher);
            case "usage" -> usage == null ? "Usage monitoring unavailable." : usage.report(command.args());
            case "stopall" -> stopAll(dispatcher);
            case "model" -> model(command.args(), dispatcher);
            case "project" -> project(command.args());
            case "reauth" -> reauth();
            case "help", "start" -> help();
            default -> null;
        };
    }

    String agentsReport(Dispatcher dispatcher) throws Exception {
        StringBuilder s = new StringBuilder();
        s.append("Mode: ").append(settings.mode().name().toLowerCase(Locale.ROOT)).append(" · Project: ");
        try {
            ProjectResolver.Project p = activeProject();
            s.append(p.title()).append(p.usesWorktrees() ? " (worktree per agent)" : "");
        } catch (IOException e) {
            s.append("unavailable (").append(e.getMessage()).append(")");
        }
        session.expiresAt().ifPresent(at -> s.append("\nT3 credential valid until ").append(at.toString(), 0, 10)
                .append(" (").append(Math.max(0, Duration.between(clock.instant(), at).toDays())).append(" days)"));
        s.append('\n');
        List<Dispatcher.Running> running = dispatcher.running();
        for (AgentDirectory.AgentBot bot : directory.all()) {
            String key = bot.agentKey();
            AppConfig.Agent agent = config.agents().get(key);
            s.append('\n').append(bot.displayName()).append(" (@").append(bot.username()).append("): ")
                    .append(onlineState(key)).append(", ");
            Optional<Dispatcher.Running> turn = running.stream().filter(r -> r.turn().agentKey().equals(key)).findFirst();
            s.append(turn.map(r -> "working for " + T3AgentRunner.format(Duration.between(r.startedAt(), clock.instant())))
                    .orElse("idle"));
            Optional<String> override = agents.modelOverride(key).filter(m -> !m.equals(agent.model()));
            s.append("\n  ").append(agent.instanceId()).append(" · ").append(override.orElse(agent.model()));
            override.ifPresent(o -> s.append(" (set with /model; default ").append(agent.model()).append(")"));
        }
        int queued = dispatcher.queuedHandoffs();
        if (queued > 0) s.append("\n\nQueued hand-offs: ").append(queued);
        return s.toString();
    }

    private String onlineState(String agentKey) {
        Optional<BotHealth.State> state = health.state(agentKey);
        if (state.isEmpty()) return "starting";
        BotHealth.State st = state.get();
        if (!st.online()) return "offline" + (st.problem() != null ? " (" + st.problem() + ")" : "");
        if (st.lastPoll() != null && Duration.between(st.lastPoll(), clock.instant()).compareTo(STALE_POLL) > 0) {
            return "not responding for " + T3AgentRunner.format(Duration.between(st.lastPoll(), clock.instant()));
        }
        return "online";
    }

    String stopAll(Dispatcher dispatcher) {
        Dispatcher.StopResult result = dispatcher.stopAll();
        if (result.interrupted().isEmpty() && result.queuedDropped() == 0) return "Nothing was running.";
        StringBuilder s = new StringBuilder("Stopped");
        if (!result.interrupted().isEmpty()) {
            s.append(' ').append(result.interrupted().size()).append(" running turn")
                    .append(result.interrupted().size() == 1 ? "" : "s").append(" (")
                    .append(result.interrupted().stream().map(r -> directory.displayNameFor(r.turn().agentKey()))
                            .distinct().collect(Collectors.joining(", ")))
                    .append(")");
        }
        if (result.queuedDropped() > 0) {
            s.append(result.interrupted().isEmpty() ? " " : " and ").append(result.queuedDropped())
                    .append(" queued hand-off").append(result.queuedDropped() == 1 ? "" : "s");
        }
        return s.append(". Nothing will continue from them.").toString();
    }

    String model(String args, Dispatcher dispatcher) throws Exception {
        String[] parts = args.strip().isEmpty() ? new String[0] : args.strip().split("\\s+", 2);
        if (parts.length == 0) {
            StringBuilder s = new StringBuilder();
            for (AgentDirectory.AgentBot bot : directory.all()) {
                s.append(bot.displayName()).append(": ").append(currentModel(bot.agentKey())).append('\n');
            }
            return s.append("\nUsage: /model <" + String.join("|", config.agentKeys()) + "> [model|default]").toString();
        }
        Optional<String> agentKey = directory.resolveAlias(parts[0].replaceFirst("^@", ""));
        if (agentKey.isEmpty()) return "No agent called '" + parts[0] + "'. Use " + orList(config.agentKeys()) + ".";
        String key = agentKey.get();
        String name = directory.displayNameFor(key);
        AppConfig.Agent agent = config.agents().get(key);
        String driver = ModelCatalog.driverOf(agent.instanceId());

        if (parts.length == 1) {
            String available = catalog.models(driver).stream().filter(m -> m.status().equals("current"))
                    .map(ModelCatalog.Model::slug).collect(Collectors.joining(", "));
            return name + " uses " + currentModel(key) + "." + (available.isEmpty() ? "" : "\nCurrent models: " + available);
        }
        String wanted = parts[1].strip();
        if (wanted.equalsIgnoreCase("default") || wanted.equalsIgnoreCase("reset")) {
            agents.setModelOverride(key, null);
            return name + " is back on its default, " + agent.model() + ", from its next turn.";
        }
        return switch (catalog.resolve(driver, wanted)) {
            case ModelCatalog.Found found -> {
                // Picking the configured default clears the override instead of pinning a copy of it.
                agents.setModelOverride(key, found.model().slug().equals(agent.model()) ? null : found.model().slug());
                Log.info("model for " + key + " set to " + found.model().slug());
                boolean busy = dispatcher.running().stream().anyMatch(r -> r.turn().agentKey().equals(key));
                yield name + " now uses " + found.model().name() + " (" + found.model().slug() + ") from its next turn."
                        + (busy ? " Its current turn finishes on the old model." : "");
            }
            case ModelCatalog.Ambiguous amb -> "'" + wanted + "' matches several models: "
                    + amb.candidates().stream().map(ModelCatalog.Model::slug).collect(Collectors.joining(", "))
                    + ". Be more specific.";
            case ModelCatalog.Unknown unknown -> unknown.available().isEmpty()
                    ? "Couldn't read T3's model list, so '" + wanted + "' can't be checked. Nothing changed."
                    : "T3 has no '" + wanted + "' model for " + name + ". Current models: "
                      + unknown.available().stream().filter(m -> m.status().equals("current"))
                              .map(ModelCatalog.Model::slug).collect(Collectors.joining(", "));
        };
    }

    private String currentModel(String agentKey) throws Exception {
        String configured = config.agents().get(agentKey).model();
        Optional<String> override = agents.modelOverride(agentKey).filter(m -> !m.equals(configured));
        String model = override.orElse(configured);
        return model + (override.isPresent() ? " (set with /model)" : "");
    }

    String project(String args) throws Exception {
        String wanted = args.strip();
        if (wanted.isEmpty()) {
            ProjectResolver.Project p = activeProject();
            return "Agents work in " + describe(p) + ".\nProjects: " + String.join(", ", projects.titles())
                    + "\n\nUsage: /project <name> (or /project default)";
        }
        if (wanted.equalsIgnoreCase("default")) {
            settings.clearActiveProjectId();
            return "Back to the configured project: " + describe(activeProject()) + ".";
        }
        ProjectResolver.Project p;
        try {
            p = projects.resolve(wanted);
        } catch (IOException e) {
            return e.getMessage();
        }
        settings.setActiveProjectId(p.id());
        Log.info("active project set to " + p.title() + " (" + p.id() + ")");
        List<String> fresh = directory.all().stream().map(AgentDirectory.AgentBot::agentKey)
                .filter(k -> {
                    try {
                        return agents.thread(k, p.id()).isEmpty();
                    } catch (Exception e) {
                        return true;
                    }
                })
                .map(directory::displayNameFor).toList();
        return "Agents now work in " + describe(p) + "."
                + (fresh.isEmpty() ? " Each resumes its existing thread there."
                   : " " + String.join(", ", fresh) + (fresh.size() == 1 ? " starts a new T3 thread" : " start new T3 threads")
                     + " there on their next turn.");
    }

    /** Swaps in a new T3 credential from a fresh pairing token in config.json, without a restart. */
    String reauth() throws Exception {
        String token;
        try {
            token = mapper.readTree(Files.readString(configPath)).path("t3").path("pairingToken").asText(null);
        } catch (IOException e) {
            return "Couldn't read " + configPath.getFileName() + ": " + e.getMessage();
        }
        if (token == null || token.isBlank() || token.startsWith("paste ")) {
            return "No t3.pairingToken in config.json. On the PC run:\n" + T3Auth.PAIR_COMMAND
                    + "\nthen put the token in config.json as t3.pairingToken and send /reauth again.";
        }
        T3Auth.Bearer fresh;
        try {
            fresh = session.reauth(token);
        } catch (IOException e) {
            return "T3 didn't accept that pairing token (they're single-use and expire after 10 minutes): " + e.getMessage();
        }
        Log.info("T3 credential renewed with /reauth");
        String until = fresh.expiresAtEpochSecond() > 0
                ? " It's valid until " + Instant.ofEpochSecond(fresh.expiresAtEpochSecond()).toString().substring(0, 10) + "." : "";
        return "Switchboard has a new T3 credential." + until
                + " The pairing token is used up, so you can remove it from config.json.";
    }

    private ProjectResolver.Project activeProject() throws Exception {
        return projects.resolve(settings.activeProjectId().orElse(config.t3().project()));
    }

    private static String describe(ProjectResolver.Project p) {
        return p.title() + " (" + p.workspaceRoot() + ")"
                + (p.usesWorktrees() ? ", each agent in its own worktree off " + p.gitBranch() : ", no per-agent worktrees");
    }

    String help() {
        return """
                Address agents with %s, or @everyone. Reply to an agent's message to continue with it.

                /agents: status, provider and model of each agent
                /usage [%s]: live usage meters from T3 Code, observed limits and reset times
                /mode [manual|autonomous]: whether agents can hand work to each other
                /discuss <turns> <task>: a bounded agent conversation, even in manual mode
                /model <agent> [model|default]: switch an agent's model
                /project [name|default]: switch the T3 project agents work in
                /stopall: interrupt every running turn and queued hand-off
                /reauth: renew the T3 credential from a fresh t3.pairingToken in config.json"""
                .formatted(directory.all().stream().map(bot -> "@" + bot.displayName()).collect(Collectors.joining(", ")),
                        String.join("|", config.agentKeys()));
    }

    /** "a", "a or b", "a, b or c". */
    static String orList(List<String> items) {
        if (items.size() <= 1) return String.join("", items);
        return String.join(", ", items.subList(0, items.size() - 1)) + " or " + items.get(items.size() - 1);
    }
}
