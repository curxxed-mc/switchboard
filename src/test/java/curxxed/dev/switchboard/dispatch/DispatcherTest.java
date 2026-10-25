package curxxed.dev.switchboard.dispatch;

import curxxed.dev.switchboard.config.AppConfig;
import curxxed.dev.switchboard.gateway.AgentDirectory;
import curxxed.dev.switchboard.gateway.Command;
import curxxed.dev.switchboard.gateway.MentionParser;
import curxxed.dev.switchboard.gateway.MessageEnvelope;
import curxxed.dev.switchboard.store.Database;
import curxxed.dev.switchboard.store.InvocationLog;
import curxxed.dev.switchboard.store.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class DispatcherTest {

    static final long GROUP = -1001234567890L;
    static final long ADMIN = 555;
    static final AgentDirectory DIRECTORY = new AgentDirectory(List.of(
            new AgentDirectory.AgentBot("claude", "Claude", 101, "ClaudeRelayBot"),
            new AgentDirectory.AgentBot("codex", "Codex", 102, "CodexRelayBot"),
            new AgentDirectory.AgentBot("gemini", "Gemini", 103, "GeminiRelayBot")));

    record Post(String kind, String agentKey, long messageId, long replyTo, String text) {}

    /** Records posts and hands out increasing message ids, like Telegram would. */
    static final class RecordingOutbox implements Outbox {
        final List<Post> posts = new CopyOnWriteArrayList<>();
        final AtomicLong nextId = new AtomicLong(1000);

        @Override
        public long postReply(String agentKey, long chatId, long replyTo, String text) {
            long id = nextId.incrementAndGet();
            posts.add(new Post("reply", agentKey, id, replyTo, text));
            return id;
        }

        @Override
        public void notice(String agentKey, long chatId, long replyTo, String text) {
            posts.add(new Post("notice", agentKey, nextId.incrementAndGet(), replyTo, text));
        }

        List<Post> replies() {
            return posts.stream().filter(p -> p.kind().equals("reply")).toList();
        }

        List<Post> notices() {
            return posts.stream().filter(p -> p.kind().equals("notice")).toList();
        }
    }

    private final Map<String, Function<Turn, String>> scripts = new ConcurrentHashMap<>();
    private final List<Turn> ran = new CopyOnWriteArrayList<>();
    private final RecordingOutbox outbox = new RecordingOutbox();
    private final AtomicInteger nextMessageId = new AtomicInteger(1);
    private Database db;
    private Settings settings;
    private InvocationLog invocations;
    private Dispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        settings = new Settings(db);
        invocations = new InvocationLog(db, Clock.systemUTC());
        // No cooldown, so cascades run back to back; cooldown itself is covered in CircuitBreakerTest.
        AppConfig.Limits limits = new AppConfig.Limits(5, 8, 3, 0, 12);
        AgentRunner runner = turn -> {
            ran.add(turn);
            return scripts.getOrDefault(turn.agentKey(), t -> "done").apply(turn);
        };
        dispatcher = new Dispatcher(settings, new CircuitBreaker(limits, Clock.systemUTC()), invocations, runner,
                outbox, DIRECTORY, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() throws Exception {
        dispatcher.close();
        db.close();
    }

    private MessageEnvelope message(long from, String text) {
        long id = nextMessageId.getAndIncrement();
        return new MessageEnvelope(GROUP, id, Instant.now(),
                new MessageEnvelope.Sender(from, from == ADMIN ? "Admin" : "Guest", null, false, from == ADMIN, null),
                text, new MentionParser(DIRECTORY).mentionedAgents(text, true), null, Command.parse(text).orElse(null), 0,
                MessageEnvelope.rootChainId(GROUP, id), "claude");
    }

    private void send(long from, String text) throws Exception {
        dispatcher.handle(message(from, text), null);
        assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(10)), "turns did not finish");
    }

    private List<String> ranAgents() {
        return ran.stream().map(Turn::agentKey).toList();
    }

    @Test
    void manualModeRunsTheAddressedAgentButNeverForwards() throws Exception {
        scripts.put("claude", t -> "Done. @Codex please run the tests.");
        send(ADMIN, "@Claude refactor the parser");

        assertEquals(List.of("claude"), ranAgents());
        assertEquals(1, outbox.replies().size());
        assertEquals(1, outbox.replies().get(0).replyTo(), "the reply answers the admin's message");
        assertTrue(outbox.notices().isEmpty());
    }

    @Test
    void onlyTheAdminCanStartAgents() throws Exception {
        send(777, "@Claude @Codex do something");
        assertTrue(ran.isEmpty());
        assertTrue(outbox.posts.isEmpty(), "non-admin requests are ignored quietly");
    }

    @Test
    void oneMessageCanAddressSeveralAgents() throws Exception {
        send(ADMIN, "@Codex write it and @Gemini review it");
        assertEquals(List.of("codex", "gemini"), ranAgents().stream().sorted().toList());
        assertTrue(ran.stream().allMatch(t -> t.depth() == 1 && t.humanDirect()));
    }

    @Test
    void autonomousModeForwardsThroughMentionsWithThreadedReplies() throws Exception {
        settings.setMode(Settings.Mode.AUTONOMOUS);
        scripts.put("claude", t -> "Parser done, @Codex add tests.");
        scripts.put("codex", t -> "Tests added. @Gemini review please.");
        scripts.put("gemini", t -> "Looks good to me.");
        send(ADMIN, "@Claude build the parser");

        assertEquals(List.of("claude", "codex", "gemini"), ranAgents());
        assertEquals(List.of(1, 2, 3), ran.stream().map(Turn::depth).toList());
        assertEquals("claude", ran.get(1).requestedByAgent());
        List<Post> replies = outbox.replies();
        assertEquals(replies.get(0).messageId(), replies.get(1).replyTo(), "Codex answers Claude's message");
        assertEquals(replies.get(1).messageId(), replies.get(2).replyTo(), "Gemini answers Codex's message");
        assertEquals(1, ran.stream().map(Turn::chainId).distinct().count(), "one chain");
        assertTrue(outbox.notices().isEmpty());
    }

    @Test
    void parallelIdenticalRepliesAreNotStopped() throws Exception {
        scripts.put("codex", t -> "Checked, all good.");
        scripts.put("gemini", t -> "Checked, all good.");
        send(ADMIN, "@Codex and @Gemini check this");
        assertEquals(2, outbox.replies().size());
        assertTrue(outbox.notices().isEmpty(), "no stop notice: " + outbox.notices());
    }

    @Test
    void aRepeatThatHandsOffToNobodyStopsQuietly() throws Exception {
        settings.setMode(Settings.Mode.AUTONOMOUS);
        scripts.put("claude", t -> "Same answer as always. @Codex confirm?");
        scripts.put("codex", t -> "Same answer as always, confirm!"); // normalizes to Claude's reply
        MessageEnvelope start = message(ADMIN, "@Claude answer");
        dispatcher.handle(start, null);
        assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(10)));
        assertEquals(List.of("claude", "codex"), ranAgents());
        assertTrue(outbox.notices().isEmpty(), "nothing was blocked, so nothing to announce");
        assertTrue(dispatcher.breaker().admit(start.chainId(), "gemini", 3, false) instanceof CircuitBreaker.Denied,
                "the chain was still stopped");
    }

    @Test
    void adminCanAddressEveryoneButAgentsCannotBroadcast() throws Exception {
        settings.setMode(Settings.Mode.AUTONOMOUS);
        scripts.put("claude", t -> "Done. @everyone take a look.");
        send(ADMIN, "@everyone status check");
        assertEquals(List.of("claude", "codex", "gemini"), ranAgents().stream().sorted().toList(),
                "the admin's @everyone reaches all three, and Claude's @everyone reaches no one");
    }

    @Test
    void discussAcceptsEveryone() throws Exception {
        send(ADMIN, "/discuss 3 @all introduce yourselves");
        assertEquals(List.of("claude", "codex", "gemini"), ranAgents().stream().sorted().toList());
    }

    @Test
    void aHandOffToABusyAgentWaitsInsteadOfBeingDropped() throws Exception {
        // Replays the live run: the admin addressed Claude and Gemini at once, Gemini answered too
        // early, and Claude's hand-off to Gemini arrived while Gemini was still busy.
        settings.setMode(Settings.Mode.AUTONOMOUS);
        CountDownLatch claudeDone = new CountDownLatch(1);
        // Claude replies only once Gemini's own turn is running, so its hand-off can't overtake it.
        CountDownLatch geminiStarted = new CountDownLatch(1);
        scripts.put("claude", t -> {
            try {
                geminiStarted.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            claudeDone.countDown();
            return "The bug hid quiet in line forty-two. @Gemini, can you critique this?";
        });
        scripts.put("gemini", t -> {
            if (t.humanDirect()) {
                geminiStarted.countDown();
                try {
                    claudeDone.await(5, TimeUnit.SECONDS);
                    Thread.sleep(300); // still busy when Claude's hand-off arrives
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "Waiting on the poem before I can review it.";
            }
            return "Solid rhythm; the punchline lands.";
        });
        send(ADMIN, "@Claude write a two-line poem about bugs and ask @Gemini to critique it");

        List<Turn> geminiTurns = ran.stream().filter(t -> t.agentKey().equals("gemini")).toList();
        assertEquals(2, geminiTurns.size(), "the queued hand-off ran after Gemini's first turn: " + ranAgents());
        Turn handoff = geminiTurns.get(1);
        assertEquals("claude", handoff.requestedByAgent());
        assertEquals(2, handoff.depth());
        assertTrue(handoff.triggerText().contains("forty-two"), "Gemini gets Claude's poem");
        assertTrue(outbox.replies().stream().anyMatch(p -> p.text().contains("punchline lands")));
        assertTrue(outbox.notices().isEmpty(), "waiting isn't worth a chat message: " + outbox.notices());
    }

    @Test
    void cooldownDelaysAHandOffInsteadOfDroppingIt() throws Exception {
        dispatcher.close();
        AgentRunner runner = turn -> {
            ran.add(turn);
            return scripts.getOrDefault(turn.agentKey(), t -> "done").apply(turn);
        };
        dispatcher = new Dispatcher(settings, new CircuitBreaker(new AppConfig.Limits(5, 8, 3, 1, 12), Clock.systemUTC()),
                invocations, runner, outbox, DIRECTORY, Clock.systemUTC());
        settings.setMode(Settings.Mode.AUTONOMOUS);
        scripts.put("claude", t -> t.humanDirect() ? "Starting. @Codex your part." : "Got it, wrapping up.");
        scripts.put("codex", t -> "Done my part, back to @Claude.");
        long started = System.nanoTime();
        send(ADMIN, "@Claude kick off");

        assertEquals(List.of("claude", "codex", "claude"), ranAgents(), "Claude's second turn waited out its 1s cooldown");
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 900, "it really waited");
    }

    @Test
    void theAdminStillHearsBusyImmediately() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        scripts.put("codex", t -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "ok";
        });
        dispatcher.handle(message(ADMIN, "@Codex one"), null);
        Thread.sleep(100);
        dispatcher.handle(message(ADMIN, "@Codex two"), null);
        assertEquals(1, outbox.notices().size(), "the admin's request is refused, not silently queued");
        release.countDown();
        assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(5)));
        assertEquals(1, ran.size());
    }

    @Test
    void anAgentMentioningItselfDoesNotLoop() throws Exception {
        settings.setMode(Settings.Mode.AUTONOMOUS);
        scripts.put("claude", t -> "I (@Claude) will handle it.");
        send(ADMIN, "@Claude go");
        assertEquals(List.of("claude"), ranAgents());
    }

    @Test
    void endlessPingPongIsStoppedByTheDepthLimitWithOneNotice() throws Exception {
        settings.setMode(Settings.Mode.AUTONOMOUS);
        AtomicInteger n = new AtomicInteger();
        scripts.put("claude", t -> "Round " + n.incrementAndGet() + " from Claude about part " + n.get() + ", over to @Codex");
        scripts.put("codex", t -> "Round " + n.incrementAndGet() + " from Codex about piece " + n.get() + ", back to @Claude");
        send(ADMIN, "@Claude start");

        assertEquals(List.of("claude", "codex", "claude", "codex", "claude"), ranAgents());
        List<Post> notices = outbox.notices();
        assertEquals(1, notices.size(), "exactly one stop notice: " + notices);
        assertTrue(notices.get(0).text().contains("depth limit of 5"), notices.get(0).text());
        assertEquals(outbox.replies().get(4).messageId(), notices.get(0).replyTo(), "notice answers the last reply");
    }

    @Test
    void tradingThanksIsCaughtAsADuplicateLoop() throws Exception {
        settings.setMode(Settings.Mode.AUTONOMOUS);
        scripts.put("claude", t -> t.humanDirect() ? "Here is the summary. Thanks @Codex!" : "Thanks @Codex!");
        scripts.put("codex", t -> "Thanks @Claude!");
        send(ADMIN, "@Claude summarize and thank Codex");

        // claude(summary) -> codex(thanks) -> claude(thanks) = duplicate of codex's reply
        assertEquals(List.of("claude", "codex", "claude"), ranAgents());
        assertEquals(3, outbox.replies().size(), "the repeated reply is still shown");
        assertEquals(1, outbox.notices().size());
        assertTrue(outbox.notices().get(0).text().contains("claude repeated an earlier reply"), outbox.notices().get(0).text());
    }

    @Test
    void busyAgentTellsTheAdminInsteadOfRunningTwice() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        scripts.put("codex", t -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "finished the long task";
        });
        dispatcher.handle(message(ADMIN, "@Codex long task"), null);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        dispatcher.handle(message(ADMIN, "@Codex another task"), null);

        assertEquals(1, outbox.notices().size());
        assertTrue(outbox.notices().get(0).text().contains("Codex is still working"));
        release.countDown();
        assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(10)));
        assertEquals(1, ran.size());

        send(ADMIN, "@Codex now it's free");
        assertEquals(2, ran.size());
    }

    @Test
    void discussRunsABoundedConversationEvenInManualMode() throws Exception {
        assertEquals(Settings.Mode.MANUAL, settings.mode());
        AtomicInteger n = new AtomicInteger();
        scripts.put("claude", t -> "Design idea number " + n.incrementAndGet() + " @Codex thoughts?");
        scripts.put("codex", t -> "Implementation note " + n.incrementAndGet() + " @Gemini check?");
        scripts.put("gemini", t -> "Review comment " + n.incrementAndGet() + " @Claude revise?");
        send(ADMIN, "/discuss 3 @Claude design the cache, then hand to Codex");

        assertEquals(List.of("claude", "codex", "gemini"), ranAgents());
        assertEquals("@Claude design the cache, then hand to Codex", ran.get(0).triggerText());
        assertEquals(1, outbox.notices().size());
        assertTrue(outbox.notices().get(0).text().contains("budget of 3"), outbox.notices().get(0).text());

        // The discussion budget belonged to that chain only; manual mode still applies elsewhere.
        send(ADMIN, "@Claude one more idea");
        assertEquals(4, ran.size());
    }

    @Test
    void discussNeedsATurnCountAndAnAgent() throws Exception {
        send(ADMIN, "/discuss @Claude talk");
        send(ADMIN, "/discuss 3 nobody is mentioned");
        send(ADMIN, "/discuss 0 @Claude");
        assertTrue(ran.isEmpty());
        assertEquals(3, outbox.notices().size());
        assertTrue(outbox.notices().stream().allMatch(p -> p.text().startsWith("Usage: /discuss")));
    }

    @Test
    void discussIsCappedAtTheConfiguredMaximum() throws Exception {
        send(ADMIN, "/discuss 99 @Claude go");
        assertTrue(outbox.notices().get(0).text().contains("Capped at 12"));
    }

    @Test
    void modeCommandSwitchesAndPersists() throws Exception {
        send(ADMIN, "/mode");
        send(ADMIN, "/mode autonomous");
        send(ADMIN, "/mode sideways");
        assertEquals(Settings.Mode.AUTONOMOUS, settings.mode());
        assertEquals(List.of("Mode is manual.", "Mode is now autonomous: agents can hand work to each other by mentioning them.",
                "Usage: /mode [manual|autonomous]"), outbox.notices().stream().map(Post::text).toList());
        assertTrue(new Settings(db).mode() == Settings.Mode.AUTONOMOUS, "stored, not just in memory");
    }

    @Test
    void aFailingTurnIsReportedAndFreesTheAgent() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        scripts.put("gemini", t -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("T3 thread not found");
            return "ok now";
        });
        send(ADMIN, "@Gemini try");
        assertEquals("Gemini couldn't finish: T3 thread not found", outbox.notices().get(0).text());
        send(ADMIN, "@Gemini try again");
        assertEquals(1, outbox.replies().size());
    }

    @Test
    void everyTurnIsAudited() throws Exception {
        settings.setMode(Settings.Mode.AUTONOMOUS);
        AtomicInteger n = new AtomicInteger();
        scripts.put("claude", t -> "Step " + n.incrementAndGet() + " handled, @Codex next part " + n.get());
        scripts.put("codex", t -> "Step " + n.incrementAndGet() + " handled, @Claude next part " + n.get());
        MessageEnvelope start = message(ADMIN, "@Claude begin");
        dispatcher.handle(start, null);
        assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(10)));

        List<InvocationLog.Row> rows = invocations.chain(start.chainId());
        assertEquals(List.of("completed", "completed", "completed", "completed", "completed", "denied:depth_limit"),
                rows.stream().map(InvocationLog.Row::status).toList());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), rows.stream().map(InvocationLog.Row::depth).toList());
        assertTrue(rows.stream().limit(5).allMatch(r -> r.responseHash() != null && r.responseHash().length() == 32));
    }
}
