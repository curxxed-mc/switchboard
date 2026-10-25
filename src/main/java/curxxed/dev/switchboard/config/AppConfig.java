package curxxed.dev.switchboard.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * External {@code config.json}. Secrets (bot tokens, T3 pairing token) live only here and the file
 * is gitignored; {@link #toString()} variants never print them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppConfig(Telegram telegram, T3 t3, Map<String, Agent> agents, Limits limits) {

    public List<String> agentKeys() {
        return agents == null ? List.of() : List.copyOf(agents.keySet());
    }

    public String displayNameFor(String agentKey) {
        return agents.get(agentKey).effectiveDisplayName(agentKey);
    }

    /**
     * @param apiBaseUrl        Bot API origin; null means {@code https://api.telegram.org}
     * @param staleAfterSeconds messages older than this are recorded but never trigger an agent
     *                          (backlog after downtime); null or 0 means the default of 300
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Telegram(long groupId, long adminUserId, String apiBaseUrl, Integer staleAfterSeconds) {
        public static final String DEFAULT_API = "https://api.telegram.org";

        public String apiBase() {
            return apiBaseUrl == null || apiBaseUrl.isBlank() ? DEFAULT_API : apiBaseUrl;
        }

        public Duration staleAfter() {
            return Duration.ofSeconds(staleAfterSeconds != null && staleAfterSeconds > 0 ? staleAfterSeconds : 300);
        }
    }

    /**
     * @param baseUrl            explicit T3 origin; null means discover it from {@code server-runtime.json}
     * @param pairingToken       one-shot credential from {@code t3 pair}; exchanged once for a bearer token
     * @param stateDir           where the relay keeps its SQLite database and exchanged T3 credential
     * @param project            T3 project the agents work in, by id or title, until {@code /project} changes it
     * @param runtimeMode        T3 permission mode for agent threads; defaults to {@code auto-accept-edits}
     * @param turnTimeoutMinutes a turn running longer is interrupted; defaults to 30
     * @param contextMessages    how many recent group messages go into each prompt; defaults to 15
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record T3(String baseUrl, String pairingToken, String stateDir, String project, String runtimeMode,
                     Integer turnTimeoutMinutes, Integer contextMessages) {
        public static final Set<String> RUNTIME_MODES =
                Set.of("approval-required", "auto-accept-edits", "auto", "full-access");

        public Path stateDirPath() {
            return stateDir == null || stateDir.isBlank() ? SwitchboardHome.state() : Path.of(stateDir);
        }

        public String effectiveRuntimeMode() {
            return runtimeMode == null || runtimeMode.isBlank() ? "auto-accept-edits" : runtimeMode;
        }

        public Duration turnTimeout() {
            return Duration.ofMinutes(turnTimeoutMinutes != null && turnTimeoutMinutes > 0 ? turnTimeoutMinutes : 30);
        }

        public int effectiveContextMessages() {
            return contextMessages != null && contextMessages >= 0 ? contextMessages : 15;
        }

        @Override
        public String toString() {
            return "T3[baseUrl=" + baseUrl + ", pairingToken=" + (pairingToken == null ? "unset" : "***")
                    + ", stateDir=" + stateDir + ", project=" + project + ", runtimeMode=" + effectiveRuntimeMode() + "]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Agent(String botToken, String instanceId, String model, String displayName) {
        public Agent(String botToken, String instanceId, String model) {
            this(botToken, instanceId, model, null);
        }

        public String effectiveDisplayName(String agentKey) {
            if (!isBlank(displayName)) return displayName;
            return agentKey.isEmpty() ? agentKey
                    : agentKey.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + agentKey.substring(1);
        }

        @Override
        public String toString() {
            return "Agent[botToken=***, instanceId=" + instanceId + ", model=" + model + "]";
        }
    }

    /**
     * Loop-prevention limits. Any field left out takes its default, as does 0 for everything but
     * the cooldown.
     *
     * @param maxDepth                   longest chain of agents triggering agents (human -> A is depth 1)
     * @param maxTurnsPerChain           total agent turns one human message can lead to in autonomous mode
     * @param maxTurnsPerAgentPerChain   how often one agent may speak within a chain
     * @param autonomousCooldownSeconds  pause before an agent can be re-triggered by another agent
     * @param maxDiscussTurns            upper bound for {@code /discuss <n>}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Limits(Integer maxDepth, Integer maxTurnsPerChain, Integer maxTurnsPerAgentPerChain,
                         Integer autonomousCooldownSeconds, Integer maxDiscussTurns) {
        public static final Limits DEFAULTS = new Limits(5, 8, 3, 15, 12);

        public int depth() {
            return orDefault(maxDepth, 5);
        }

        public int turnsPerChain() {
            return orDefault(maxTurnsPerChain, 8);
        }

        public int turnsPerAgentPerChain() {
            return orDefault(maxTurnsPerAgentPerChain, 3);
        }

        /** Unlike the other limits, 0 is meaningful here: no cooldown. */
        public Duration autonomousCooldown() {
            return Duration.ofSeconds(autonomousCooldownSeconds == null ? 15 : Math.max(0, autonomousCooldownSeconds));
        }

        public int discussTurns() {
            return orDefault(maxDiscussTurns, 12);
        }

        private static int orDefault(Integer value, int fallback) {
            return value != null && value > 0 ? value : fallback;
        }
    }

    /** Limits with defaults filled in when the section is absent. */
    public Limits effectiveLimits() {
        return limits == null ? Limits.DEFAULTS : limits;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);

    public static AppConfig load(Path path) throws IOException {
        AppConfig config = MAPPER.readValue(Files.readString(path), AppConfig.class);
        List<String> problems = config.validate();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid " + path + ":\n  - " + String.join("\n  - ", problems));
        }
        return config;
    }

    /** Structural checks only; whether instance IDs and models exist is verified against live T3. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (telegram == null) {
            problems.add("telegram section is missing");
        } else {
            if (telegram.groupId() >= 0) problems.add("telegram.groupId must be a negative group/supergroup id");
            if (telegram.adminUserId() <= 0) problems.add("telegram.adminUserId must be a positive user id");
        }
        if (t3 == null) {
            problems.add("t3 section is missing");
        } else {
            if (isBlank(t3.project())) problems.add("t3.project is required: the T3 project id or title agents work in");
            if (!T3.RUNTIME_MODES.contains(t3.effectiveRuntimeMode())) {
                problems.add("t3.runtimeMode must be one of " + T3.RUNTIME_MODES);
            }
        }
        if (agents == null) {
            problems.add("agents section is missing");
            return problems;
        }
        if (agents.isEmpty()) problems.add("agents must configure at least one agent");
        for (String key : agentKeys()) {
            Agent agent = agents.get(key);
            if (agent == null) {
                problems.add("agents." + key + " is missing");
                continue;
            }
            if (isBlank(agent.botToken()) || !agent.botToken().contains(":")) {
                problems.add("agents." + key + ".botToken does not look like a BotFather token");
            }
            if (isBlank(agent.instanceId())) problems.add("agents." + key + ".instanceId is required");
            if (isBlank(agent.model())) problems.add("agents." + key + ".model is required");
        }
        long distinctTokens = agents.values().stream().filter(java.util.Objects::nonNull).map(Agent::botToken).distinct().count();
        long configuredAgents = agents.values().stream().filter(java.util.Objects::nonNull).count();
        if (distinctTokens != configuredAgents) problems.add("each agent needs its own bot token");
        return problems;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
