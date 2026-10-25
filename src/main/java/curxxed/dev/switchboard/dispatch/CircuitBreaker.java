package curxxed.dev.switchboard.dispatch;

import curxxed.dev.switchboard.config.AppConfig;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether an agent turn may start, and stops chains that run away.
 *
 * <p>A chain is everything that follows from one human message (or one {@code /discuss}). Limits:
 * <ul>
 *   <li>depth: how many agents deep the cascade goes</li>
 *   <li>chain budget: total agent turns in the chain</li>
 *   <li>per-agent turns: how often one agent may speak in the chain</li>
 *   <li>busy: an agent runs one turn at a time, across all chains</li>
 *   <li>cooldown: an agent just finished can't be re-triggered by another agent right away</li>
 *   <li>duplicate replies: a reply matching an earlier one in the chain stops it</li>
 * </ul>
 * Human-direct turns skip the cooldown (the admin asked explicitly) but not the busy check.
 */
public final class CircuitBreaker {

    public enum Reason {
        DEPTH_LIMIT(true), CHAIN_BUDGET(true), AGENT_TURN_LIMIT(true), CHAIN_STOPPED(true),
        AGENT_BUSY(false), COOLDOWN(false);

        /** True when this denial ends the chain, rather than just skipping one turn. */
        public final boolean endsChain;

        Reason(boolean endsChain) {
            this.endsChain = endsChain;
        }
    }

    public sealed interface Decision permits Admitted, Denied {}

    /** @param epoch pass back to {@link #complete} so replies from before a reset can't cascade */
    public record Admitted(long epoch) implements Decision {}

    /** @param firstForChain true the first time this chain is denied for a chain-ending reason */
    public record Denied(Reason reason, String detail, boolean firstForChain) implements Decision {}

    /** Why a chain was stopped after a reply. */
    public record Trip(String detail) {}

    public record Snapshot(Set<String> busyAgents, int activeChains) {}

    /** Chains idle this long are forgotten. */
    static final Duration CHAIN_IDLE = Duration.ofHours(1);

    private final AppConfig.Limits limits;
    private final Clock clock;
    private final Map<String, String> busy = new LinkedHashMap<>(); // agent -> chain
    private final Map<String, Instant> lastFinished = new HashMap<>();
    private final Map<String, Chain> chains = new HashMap<>();
    private long epoch;

    private record Reply(int depth, String normalized) {}

    private static final class Chain {
        final int budget;
        final boolean discuss;
        int turns;
        final Map<String, Integer> turnsByAgent = new HashMap<>();
        final List<Reply> replies = new ArrayList<>();
        String stoppedBecause;
        boolean reported;
        Instant lastActivity;

        Chain(int budget, boolean discuss, Instant now) {
            this.budget = budget;
            this.discuss = discuss;
            this.lastActivity = now;
        }
    }

    public CircuitBreaker(AppConfig.Limits limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * Starts a {@code /discuss} chain: agents may trigger each other even in manual mode, for up
     * to {@code turns} turns (capped at the configured maximum).
     *
     * @return the budget actually granted
     */
    public synchronized int openDiscussion(String chainId, int turns) {
        int budget = Math.max(1, Math.min(turns, limits.discussTurns()));
        chains.put(chainId, new Chain(budget, true, clock.instant()));
        return budget;
    }

    public synchronized boolean isDiscussion(String chainId) {
        Chain chain = chains.get(chainId);
        return chain != null && chain.discuss;
    }

    /**
     * @param depth       1 for a turn requested by a human, n + 1 for a turn requested by a depth-n agent
     * @param humanDirect true when the admin addressed the agent directly
     */
    public synchronized Decision admit(String chainId, String agentKey, int depth, boolean humanDirect) {
        Instant now = clock.instant();
        evictIdle(now);
        Chain chain = chains.computeIfAbsent(chainId, id -> new Chain(limits.turnsPerChain(), false, now));
        chain.lastActivity = now;

        if (chain.stoppedBecause != null) return deny(chain, Reason.CHAIN_STOPPED, chain.stoppedBecause);
        if (depth > limits.depth()) {
            return stop(chain, Reason.DEPTH_LIMIT, "it reached the depth limit of " + limits.depth() + " agent hops");
        }
        if (chain.turns >= chain.budget) {
            return stop(chain, Reason.CHAIN_BUDGET, "it used its budget of " + chain.budget + " agent turns");
        }
        int agentTurns = chain.turnsByAgent.getOrDefault(agentKey, 0);
        if (agentTurns >= limits.turnsPerAgentPerChain()) {
            return stop(chain, Reason.AGENT_TURN_LIMIT,
                    agentKey + " already spoke " + agentTurns + " times in it");
        }
        if (busy.containsKey(agentKey)) return deny(chain, Reason.AGENT_BUSY, agentKey + " is still working on another request");
        if (!humanDirect) {
            Instant last = lastFinished.get(agentKey);
            Duration cooldown = limits.autonomousCooldown();
            if (last != null && Duration.between(last, now).compareTo(cooldown) < 0) {
                return deny(chain, Reason.COOLDOWN, agentKey + " finished less than " + cooldown.toSeconds() + "s ago");
            }
        }
        busy.put(agentKey, chainId);
        chain.turns++;
        chain.turnsByAgent.merge(agentKey, 1, Integer::sum);
        return new Admitted(epoch);
    }

    /**
     * Records an admitted turn's reply and frees the agent.
     *
     * <p>A reply is compared only with replies from shallower turns: a loop is an agent repeating
     * what came before it in the cascade. Agents answering the same request in parallel (same
     * depth) may well agree, and that isn't a loop.
     *
     * @param depth         the turn's depth
     * @param admittedEpoch from {@link Admitted#epoch()}
     * @return a trip if the reply must not cascade: it repeats an earlier reply in the chain (the
     *         chain is then stopped), or everything was reset while the turn ran
     */
    public synchronized Optional<Trip> complete(String chainId, String agentKey, int depth, long admittedEpoch,
                                                String reply) {
        if (admittedEpoch != epoch) return Optional.of(new Trip("all runs were stopped while " + agentKey + " was working"));
        release(agentKey, admittedEpoch);
        Chain chain = chains.get(chainId);
        if (chain == null || reply == null) return Optional.empty();
        String normalized = ResponseSimilarity.normalize(reply);
        if (normalized.isEmpty()) return Optional.empty();
        for (Reply earlier : chain.replies) {
            if (earlier.depth() < depth && ResponseSimilarity.sameReply(normalized, earlier.normalized())) {
                String detail = agentKey + " repeated an earlier reply in this chain";
                if (chain.stoppedBecause == null) chain.stoppedBecause = detail;
                chain.reported = true;
                chain.replies.add(new Reply(depth, normalized));
                return Optional.of(new Trip(detail));
            }
        }
        chain.replies.add(new Reply(depth, normalized));
        return Optional.empty();
    }

    /**
     * Frees an agent whose turn ended without a reply (failure or cancellation). A turn from before
     * a reset is ignored, so it can't free an agent that's now running a newer turn.
     */
    public synchronized void release(String agentKey, long admittedEpoch) {
        if (admittedEpoch != epoch) return;
        if (busy.remove(agentKey) != null) lastFinished.put(agentKey, clock.instant());
    }

    /** Stops one chain; later admissions in it are denied. */
    public synchronized void stopChain(String chainId, String why) {
        Chain chain = chains.get(chainId);
        if (chain != null && chain.stoppedBecause == null) {
            chain.stoppedBecause = why;
            chain.reported = true;
        }
    }

    /** {@code /stopall}: forget every chain, busy flag and cooldown, and disown running turns. */
    public synchronized void resetAll() {
        epoch++;
        busy.clear();
        lastFinished.clear();
        chains.clear();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(Set.copyOf(busy.keySet()), chains.size());
    }

    private Denied stop(Chain chain, Reason reason, String detail) {
        chain.stoppedBecause = detail;
        return deny(chain, reason, detail);
    }

    private Denied deny(Chain chain, Reason reason, String detail) {
        boolean first = false;
        if (reason.endsChain && !chain.reported) {
            chain.reported = true;
            first = true;
        }
        return new Denied(reason, detail, first);
    }

    private void evictIdle(Instant now) {
        chains.entrySet().removeIf(e -> !busy.containsValue(e.getKey())
                && Duration.between(e.getValue().lastActivity, now).compareTo(CHAIN_IDLE) > 0);
    }
}
