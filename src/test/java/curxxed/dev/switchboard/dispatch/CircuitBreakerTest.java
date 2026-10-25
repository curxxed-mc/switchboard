package curxxed.dev.switchboard.dispatch;

import curxxed.dev.switchboard.config.AppConfig;
import curxxed.dev.switchboard.dispatch.CircuitBreaker.Admitted;
import curxxed.dev.switchboard.dispatch.CircuitBreaker.Decision;
import curxxed.dev.switchboard.dispatch.CircuitBreaker.Denied;
import curxxed.dev.switchboard.dispatch.CircuitBreaker.Reason;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    /** depth 5, 8 turns per chain, 3 per agent, 15s cooldown, /discuss up to 12 */
    private final AppConfig.Limits limits = AppConfig.Limits.DEFAULTS;
    private final TestClock clock = new TestClock();
    private final CircuitBreaker breaker = new CircuitBreaker(limits, clock);

    private long admit(String chain, String agent, int depth, boolean human) {
        Decision d = breaker.admit(chain, agent, depth, human);
        assertInstanceOf(Admitted.class, d, () -> "expected admission, got " + d);
        return ((Admitted) d).epoch();
    }

    private Denied deny(String chain, String agent, int depth, boolean human) {
        Decision d = breaker.admit(chain, agent, depth, human);
        assertInstanceOf(Denied.class, d, () -> "expected denial, got " + d);
        return (Denied) d;
    }

    /** Admit-and-complete with a unique reply, advancing past the cooldown. */
    private void turn(String chain, String agent, int depth, boolean human, String reply) {
        long epoch = admit(chain, agent, depth, human);
        assertTrue(breaker.complete(chain, agent, depth, epoch, reply).isEmpty(), "unexpected trip for: " + reply);
        clock.advance(Duration.ofSeconds(20));
    }

    @Test
    void depthLimitStopsTheCascadeAndReportsOnce() {
        String[] agents = {"claude", "codex", "gemini", "claude", "codex"};
        for (int depth = 1; depth <= 5; depth++) turn("c", agents[depth - 1], depth, depth == 1, "reply number " + depth);

        Denied d = deny("c", "gemini", 6, false);
        assertEquals(Reason.DEPTH_LIMIT, d.reason());
        assertTrue(d.firstForChain());
        assertTrue(d.detail().contains("depth limit of 5"), d.detail());

        Denied again = deny("c", "claude", 3, false);
        assertEquals(Reason.CHAIN_STOPPED, again.reason());
        assertFalse(again.firstForChain(), "a stopped chain is reported only once");
    }

    @Test
    void chainBudgetCapsTotalTurns() {
        CircuitBreaker roomy = new CircuitBreaker(new AppConfig.Limits(50, 4, 50, 0, 12), clock);
        String[] agents = {"claude", "codex", "gemini", "claude"};
        for (int i = 0; i < 4; i++) {
            long epoch = ((Admitted) roomy.admit("c", agents[i], i + 1, false)).epoch();
            roomy.complete("c", agents[i], i + 1, epoch, "unique reply " + i);
            clock.advance(Duration.ofSeconds(20));
        }
        Denied d = (Denied) roomy.admit("c", "codex", 5, false);
        assertEquals(Reason.CHAIN_BUDGET, d.reason());
        assertTrue(d.detail().contains("budget of 4"), d.detail());
    }

    @Test
    void pingPongIsCappedPerAgent() {
        // Generous depth and budget, so the per-agent cap is what stops Claude and Codex trading turns.
        CircuitBreaker deep = new CircuitBreaker(new AppConfig.Limits(50, 50, 3, 0, 12), clock);
        for (int i = 0; i < 3; i++) {
            long e = ((Admitted) deep.admit("p", "claude", 1 + 2 * i, i == 0)).epoch();
            deep.complete("p", "claude", 1 + 2 * i, e, "claude says " + i);
            long f = ((Admitted) deep.admit("p", "codex", 2 + 2 * i, false)).epoch();
            deep.complete("p", "codex", 2 + 2 * i, f, "codex says " + i);
        }
        Denied d = (Denied) deep.admit("p", "claude", 7, false);
        assertEquals(Reason.AGENT_TURN_LIMIT, d.reason());
        assertTrue(d.detail().contains("claude already spoke 3 times"), d.detail());
    }

    @Test
    void anAgentRunsOneTurnAtATimeAcrossChains() {
        long epoch = admit("chain-1", "codex", 1, true);
        Denied busy = deny("chain-2", "codex", 1, true);
        assertEquals(Reason.AGENT_BUSY, busy.reason());
        assertFalse(busy.reason().endsChain);
        assertEquals(Set.of("codex"), breaker.snapshot().busyAgents());

        admit("chain-2", "claude", 1, true); // other agents are unaffected
        breaker.complete("chain-1", "codex", 1, epoch, "done");
        admit("chain-2", "codex", 1, true);
    }

    @Test
    void busyDenialDoesNotConsumeBudget() {
        CircuitBreaker tight = new CircuitBreaker(new AppConfig.Limits(5, 1, 3, 0, 12), clock);
        long epoch = ((Admitted) tight.admit("other", "codex", 1, true)).epoch();
        assertEquals(Reason.AGENT_BUSY, ((Denied) tight.admit("c", "codex", 1, true)).reason());
        tight.complete("other", "codex", 1, epoch, "x");
        assertInstanceOf(Admitted.class, tight.admit("c", "codex", 1, true), "the busy attempt must not count");
    }

    @Test
    void cooldownAppliesToAgentHandoffsButNotToTheAdmin() {
        long epoch = admit("c1", "gemini", 1, true);
        breaker.complete("c1", "gemini", 1, epoch, "first");
        clock.advance(Duration.ofSeconds(5));

        Denied d = deny("c2", "gemini", 2, false);
        assertEquals(Reason.COOLDOWN, d.reason());
        long humanEpoch = admit("c3", "gemini", 1, true); // admin asked explicitly
        breaker.complete("c3", "gemini", 1, humanEpoch, "second");

        clock.advance(Duration.ofSeconds(16));
        admit("c2", "gemini", 2, false);
    }

    @Test
    void exactDuplicateReplyTripsTheChain() {
        turn("c", "claude", 1, true, "Thanks @Codex!");
        long epoch = admit("c", "codex", 2, false);
        var trip = breaker.complete("c", "codex", 2, epoch, "thanks, @Claude");
        assertTrue(trip.isPresent(), "'Thanks!' traded back and forth is a loop");
        assertTrue(trip.get().detail().contains("codex repeated"));
        clock.advance(Duration.ofSeconds(20));
        Denied d = deny("c", "claude", 3, false);
        assertEquals(Reason.CHAIN_STOPPED, d.reason());
        assertFalse(d.firstForChain(), "the trip itself was the report");
    }

    @Test
    void nearDuplicateLongReplyTripsTheChain() {
        String plan = "Here is the plan: first refactor the parser module, then add unit tests for the new "
                + "grammar rules, then update the documentation and finally run the full integration suite";
        turn("c", "claude", 1, true, plan);
        long epoch = admit("c", "codex", 2, false);
        assertTrue(breaker.complete("c", "codex", 2, epoch, plan.replace("finally", "then") + ".").isPresent());
    }

    @Test
    void agentsAgreeingInParallelIsNotALoop() {
        // One admin message to two agents: both answer at depth 1, identically. Seen live with the
        // placeholder runner, which tripped the old check.
        long a = admit("c", "codex", 1, true);
        long b = admit("c", "gemini", 1, true);
        assertTrue(breaker.complete("c", "codex", 1, a, "LGTM, ship it").isEmpty());
        assertTrue(breaker.complete("c", "gemini", 1, b, "LGTM, ship it").isEmpty());
        // ...but repeating it one hop later is.
        clock.advance(Duration.ofSeconds(20));
        long c = admit("c", "claude", 2, false);
        assertTrue(breaker.complete("c", "claude", 2, c, "lgtm ship it!").isPresent());
    }

    @Test
    void differentRepliesDoNotTrip() {
        turn("c", "claude", 1, true, "I wrote the parser; @Codex please add tests.");
        long epoch = admit("c", "codex", 2, false);
        assertTrue(breaker.complete("c", "codex", 2, epoch, "Added 12 tests, all passing. @Gemini can you review?").isEmpty());
    }

    @Test
    void discussionGetsItsOwnBudgetCappedAtTheMaximum() {
        assertEquals(3, breaker.openDiscussion("d", 3));
        assertTrue(breaker.isDiscussion("d"));
        assertFalse(breaker.isDiscussion("other"));
        turn("d", "claude", 1, true, "one");
        turn("d", "codex", 2, false, "two");
        turn("d", "gemini", 3, false, "three");
        assertEquals(Reason.CHAIN_BUDGET, deny("d", "claude", 4, false).reason());

        assertEquals(12, breaker.openDiscussion("big", 500));
    }

    @Test
    void resetAllDisownsRunningTurns() {
        long old = admit("c", "claude", 1, true);
        breaker.resetAll();
        assertTrue(breaker.snapshot().busyAgents().isEmpty());

        long fresh = admit("c2", "claude", 1, true); // claude is free again after /stopall
        assertTrue(breaker.complete("c", "claude", 1, old, "late reply").isPresent(), "a pre-reset reply must not cascade");
        breaker.release("claude", old);
        assertEquals(Set.of("claude"), breaker.snapshot().busyAgents(), "a stale release must not free the new turn");
        breaker.complete("c2", "claude", 1, fresh, "ok");
        assertTrue(breaker.snapshot().busyAgents().isEmpty());
    }

    @Test
    void idleChainsAreForgotten() {
        turn("old", "claude", 1, true, "x");
        assertEquals(1, breaker.snapshot().activeChains());
        clock.advance(Duration.ofHours(2));
        admit("new", "codex", 1, true);
        assertEquals(1, breaker.snapshot().activeChains());
    }

    @Test
    void similarityNormalization() {
        assertEquals("thanks", ResponseSimilarity.normalize("  Thanks, @Codex!! "));
        assertTrue(ResponseSimilarity.sameReply("ok", "ok"));
        assertFalse(ResponseSimilarity.sameReply("ok sure", "ok fine"), "short replies only match exactly");
    }

    static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-25T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
