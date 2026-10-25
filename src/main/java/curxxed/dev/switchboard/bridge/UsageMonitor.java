package curxxed.dev.switchboard.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import curxxed.dev.switchboard.app.Log;
import curxxed.dev.switchboard.app.Supervisor;
import curxxed.dev.switchboard.config.AppConfig;
import curxxed.dev.switchboard.dispatch.AgentRunner;
import curxxed.dev.switchboard.store.AgentStore;
import curxxed.dev.switchboard.store.UsageStore;
import curxxed.dev.switchboard.store.UsageStore.Identity;
import curxxed.dev.switchboard.store.UsageStore.Limit;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

public final class UsageMonitor implements Supervisor.Worker {
    @FunctionalInterface
    public interface Notices {
        void send(String agent, String text) throws Exception;
    }

    /**
     * T3's own usage meters: the {@code providers} array of {@code server.getConfig}, where each
     * entry may carry {@code usageLimits.windows} with {@code usedPercent} and {@code resetsAt}.
     */
    @FunctionalInterface
    public interface LiveMeters {
        JsonNode providers() throws Exception;
    }

    private static final Duration LIVE_POLL = Duration.ofMinutes(5);
    private static final DateTimeFormatter RESET_FORMAT =
            DateTimeFormatter.ofPattern("EEE HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final AppConfig config;
    private final AgentStore agents;
    private final UsageStore store;
    private final Clock clock;
    private final Notices notices;
    private final LiveMeters live;
    private Instant lastLivePoll = Instant.MIN;

    public UsageMonitor(AppConfig config, AgentStore agents, UsageStore store, Clock clock, Notices notices) {
        this(config, agents, store, clock, notices, null);
    }

    /** @param live may be null, in which case only failure-based observations are reported */
    public UsageMonitor(AppConfig config, AgentStore agents, UsageStore store, Clock clock, Notices notices,
                        LiveMeters live) {
        this.config = config;
        this.agents = agents;
        this.store = store;
        this.clock = clock;
        this.notices = notices;
        this.live = live;
    }

    public AgentRunner track(AgentRunner runner) {
        return turn -> {
            Identity identity = identity(turn.agentKey());
            String reply;
            try {
                reply = runner.run(turn);
            } catch (T3AgentRunner.RunFailedException failure) {
                Optional<UsageLimit> limit = UsageLimit.detect(failure.details(), clock.instant());
                if (limit.isEmpty()) throw failure;
                UsageLimit observed = limit.get();
                try {
                    record(identity, observed);
                } catch (SQLException persistenceFailure) {
                    Log.warn("could not save usage limit for " + identity.agent() + ": " + persistenceFailure.getMessage());
                }
                T3AgentRunner.RunFailedException alert = new T3AgentRunner.RunFailedException(observed.window() + " usage limit reached for "
                        + identity.model() + "; " + resetText(observed.resetsAt(), clock.instant()), failure.details());
                alert.initCause(failure);
                throw alert;
            }
            try {
                recovered(identity);
            } catch (SQLException failure) {
                Log.warn("could not clear usage limit for " + identity.agent() + ": " + failure.getMessage());
            }
            return reply;
        };
    }

    synchronized void record(Identity identity, UsageLimit limit) throws SQLException {
        store.record(identity, limit.window(), clock.instant(), limit.resetsAt());
    }

    private synchronized void recovered(Identity identity) throws SQLException {
        store.clear(identity);
    }

    public synchronized String report(String requested) throws SQLException {
        String key = requested.strip().toLowerCase(java.util.Locale.ROOT);
        if (!key.isEmpty() && !config.agentKeys().contains(key)) return "Usage: /usage [" + String.join("|", config.agentKeys()) + "]";
        JsonNode providers = null;
        String liveError = null;
        if (live != null) {
            try {
                providers = live.providers();
            } catch (Exception failure) {
                liveError = failure.getMessage();
            }
        }
        StringBuilder report = new StringBuilder(providers != null ? "Usage (live meters from T3 Code):"
                : "Usage observations (not live account meters):");
        if (liveError != null) report.append("\nT3 usage meters unavailable: ").append(liveError);
        Instant now = clock.instant();
        for (String agent : key.isEmpty() ? config.agentKeys() : List.of(key)) {
            Identity identity = identity(agent);
            report.append("\n\n").append(config.displayNameFor(agent)).append(" · ")
                    .append(identity.provider()).append(" · ").append(identity.model());
            JsonNode meters = providers == null ? null : meters(providers, identity.provider());
            if (meters == null) {
                report.append("\n5-hour / weekly usage percentages: unknown");
            } else {
                appendMeters(report, meters, now);
            }
            List<Limit> limits = store.limits(identity);
            if (limits.isEmpty() && meters == null) report.append("\nNo limit currently recorded; availability unverified.");
            for (Limit limit : limits) {
                report.append("\n").append(limit.window()).append(" limit reported at ").append(limit.observedAt())
                        .append("; ").append(resetText(limit.resetsAt(), clock.instant()));
            }
        }
        return report.toString();
    }

    public synchronized void tick() throws Exception {
        Instant now = clock.instant();
        if (live != null && !now.isBefore(lastLivePoll.plus(LIVE_POLL))) {
            lastLivePoll = now;
            try {
                watchLive(live.providers(), now);
            } catch (Exception failure) {
                Log.warn("could not read T3 usage meters: " + failure.getMessage());
            }
        }
        for (String agent : config.agentKeys()) {
            for (Limit limit : store.limits(identity(agent))) {
                if (limit.reminded() || limit.resetsAt() == null || limit.resetsAt().isAfter(now)) continue;
                notices.send(agent, config.displayNameFor(agent) + " · " + limit.identity().model()
                        + ": the provider-reported " + limit.window() + " reset time (" + limit.resetsAt()
                        + ") has passed. Availability is not verified; another limit may still apply."
                        + " You can try another turn.");
                store.markReminded(limit);
            }
        }
    }

    @Override
    public void run() throws Exception {
        while (!Thread.currentThread().isInterrupted()) {
            tick();
            Thread.sleep(Duration.ofSeconds(30));
        }
    }

    /**
     * A window at 100% becomes a recorded limit with T3's reset time, so the reset reminder below
     * fires as it does for limits seen in failed turns. Each window and reset time alerts once.
     */
    private void watchLive(JsonNode providers, Instant now) throws Exception {
        for (String agent : config.agentKeys()) {
            Identity identity = identity(agent);
            JsonNode meters = meters(providers, identity.provider());
            if (meters == null) continue;
            for (JsonNode window : meters.path("windows")) {
                if (window.path("usedPercent").asDouble(0) < 100) continue;
                Instant reset = parse(window.path("resetsAt").asText(null));
                if (reset != null && !reset.isAfter(now)) continue;
                String name = window.path("label").asText(window.path("id").asText("unknown window"));
                boolean known = store.limits(identity).stream()
                        .anyMatch(l -> l.window().equals(name) && java.util.Objects.equals(l.resetsAt(), reset));
                if (known) continue;
                store.record(identity, name, now, reset);
                notices.send(agent, config.displayNameFor(agent) + " used 100% of its " + name
                        + " usage limit; " + (reset == null ? "reset time unknown." : "resets " + when(reset, now) + "."));
            }
        }
    }

    /** The {@code usageLimits} T3 reports for a provider instance, or null if it has none. */
    private static JsonNode meters(JsonNode providers, String instanceId) {
        for (JsonNode provider : providers) {
            if (!instanceId.equals(provider.path("instanceId").asText())) continue;
            JsonNode limits = provider.path("usageLimits");
            return limits.isObject() ? limits : null;
        }
        return null;
    }

    private static void appendMeters(StringBuilder report, JsonNode meters, Instant now) {
        for (JsonNode window : meters.path("windows")) {
            report.append("\n").append(window.path("label").asText(window.path("id").asText()))
                    .append(": ").append(Math.round(window.path("usedPercent").asDouble())).append("% used");
            Instant reset = parse(window.path("resetsAt").asText(null));
            if (reset != null) {
                report.append(reset.isAfter(now) ? " · resets " + when(reset, now)
                        : " · reset time " + RESET_FORMAT.format(reset) + " passed; T3 hasn't refreshed yet");
            }
        }
        JsonNode unavailable = meters.path("unavailable");
        if (!meters.path("windows").isEmpty()) {
            Instant checked = parse(meters.path("checkedAt").asText(null));
            if (checked != null) report.append("\nchecked ").append(ago(checked, now));
        } else if (unavailable.isObject()) {
            report.append("\nT3 has no usage meter for this provider")
                    .append(unavailable.hasNonNull("message") ? ": " + unavailable.path("message").asText() : ".");
            JsonNode external = meters.path("externalUsage");
            if (external.hasNonNull("url")) report.append("\nSee ").append(external.path("url").asText());
        } else {
            report.append("\nNo usage windows reported.");
        }
    }

    private static String when(Instant reset, Instant now) {
        return RESET_FORMAT.format(reset) + " (in " + span(Duration.between(now, reset)) + ")";
    }

    private static String ago(Instant then, Instant now) {
        Duration age = Duration.between(then, now);
        return age.toMinutes() < 1 ? "just now" : span(age) + " ago";
    }

    private static String span(Duration d) {
        long minutes = Math.max(1, d.toMinutes());
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h " + minutes % 60 + "m";
        return hours / 24 + "d " + hours % 24 + "h";
    }

    private static Instant parse(String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (java.time.DateTimeException ignored) {
            return null;
        }
    }

    private Identity identity(String key) throws SQLException {
        AppConfig.Agent agent = config.agents().get(key);
        String model = agents.modelOverride(key).orElse(agent.model());
        return new Identity(key, agent.instanceId(), model == null || model.isBlank() ? "(provider default)" : model);
    }

    private static String resetText(Instant reset, Instant now) {
        if (reset == null) return "reset time unknown";
        if (!reset.isAfter(now)) return "reported reset time " + reset + " has passed; availability unverified";
        return "provider reports reset at " + reset + " (UTC)";
    }
}
