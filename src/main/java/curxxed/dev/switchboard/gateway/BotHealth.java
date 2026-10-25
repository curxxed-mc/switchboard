package curxxed.dev.switchboard.gateway;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Each bot poller's last known state, for {@code /agents}. */
public final class BotHealth {

    /**
     * @param online   true while polls succeed
     * @param lastPoll when Telegram last answered this bot's {@code getUpdates}
     * @param problem  the latest error, or null when healthy
     */
    public record State(boolean online, Instant lastPoll, String problem) {}

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Clock clock;

    public BotHealth(Clock clock) {
        this.clock = clock;
    }

    public void polled(String agentKey) {
        states.put(agentKey, new State(true, clock.instant(), null));
    }

    public void failed(String agentKey, String problem, boolean permanent) {
        State previous = states.get(agentKey);
        states.put(agentKey, new State(!permanent && previous != null && previous.online(),
                previous == null ? null : previous.lastPoll(), problem));
    }

    public Optional<State> state(String agentKey) {
        return Optional.ofNullable(states.get(agentKey));
    }
}
