package curxxed.dev.switchboard.dispatch;

import curxxed.dev.switchboard.app.Log;
import curxxed.dev.switchboard.gateway.AgentDirectory;
import curxxed.dev.switchboard.gateway.ButtonHandler;
import curxxed.dev.switchboard.gateway.ButtonPress;
import curxxed.dev.switchboard.gateway.Command;
import curxxed.dev.switchboard.gateway.EnvelopeHandler;
import curxxed.dev.switchboard.gateway.MentionParser;
import curxxed.dev.switchboard.gateway.MessageEnvelope;
import curxxed.dev.switchboard.store.InvocationLog;
import curxxed.dev.switchboard.store.Settings;
import curxxed.dev.switchboard.telegram.TelegramApi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Routes accepted group messages to agent turns and agent replies to further turns.
 *
 * <ul>
 *   <li>Only the admin can start agents, by mentioning them or replying to one.</li>
 *   <li>MANUAL mode: an agent's reply never triggers another agent.</li>
 *   <li>AUTONOMOUS mode, or inside a {@code /discuss} chain: mentions in an agent's reply start
 *       the mentioned agents' turns, subject to the {@link CircuitBreaker}.</li>
 * </ul>
 * Turns run on their own virtual threads, so a long agent run never blocks a bot's polling.
 *
 * <p>A hand-off to an agent that is busy or cooling down is queued, not dropped, and starts once the
 * agent is free. It still has to pass every limit when it starts, and expires after
 * {@link #MAX_DEFER}. The admin's own requests are never queued: they hear "busy" straight away.
 */
public final class Dispatcher implements EnvelopeHandler, AutoCloseable {

    static final Duration MAX_DEFER = Duration.ofMinutes(10);
    static final Duration RETRY_INTERVAL = Duration.ofSeconds(2);

    /** A hand-off waiting for its agent, and when it first had to wait. */
    private record Deferred(Turn turn, Instant since) {}

    /** A turn in progress: what it is, when it started, and its thread (interrupted to cancel). */
    public record Running(long invocationId, Turn turn, Instant startedAt, Future<?> future) {}

    /** Commands beyond /mode and /discuss. Returns the reply to post, or null to stay silent. */
    @FunctionalInterface
    public interface Commands {
        String handle(Command command, MessageEnvelope envelope, Dispatcher dispatcher) throws Exception;

        Commands NONE = (c, e, d) -> null;
    }

    private final Settings settings;
    private final CircuitBreaker breaker;
    private final InvocationLog invocations;
    private final AgentRunner runner;
    private final Outbox outbox;
    private final MentionParser mentions;
    private final AgentDirectory directory;
    private final ExecutorService turns = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final List<Deferred> deferred = new ArrayList<>(); // guarded by itself
    private final LinkedBlockingQueue<Boolean> wake = new LinkedBlockingQueue<>();
    private final Map<Long, Running> running = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Thread retrier;
    private volatile Commands commands = Commands.NONE;
    private volatile ButtonHandler buttons = (press, via) -> via.answerCallbackQuery(press.queryId(), null, false);
    /** Gets first look at every message; true means it was consumed (e.g. an answer to an agent's question). */
    private volatile Predicate<MessageEnvelope> intercept = e -> false;

    public Dispatcher(Settings settings, CircuitBreaker breaker, InvocationLog invocations, AgentRunner runner,
                      Outbox outbox, AgentDirectory directory, Clock clock) {
        this.settings = settings;
        this.breaker = breaker;
        this.invocations = invocations;
        this.runner = runner;
        this.outbox = outbox;
        this.directory = directory;
        this.mentions = new MentionParser(directory);
        this.clock = clock;
        this.retrier = Thread.ofVirtual().name("handoff-retry").start(this::retryLoop);
    }

    public CircuitBreaker breaker() {
        return breaker;
    }

    public void setCommands(Commands commands) {
        this.commands = commands;
    }

    public void setButtons(ButtonHandler buttons) {
        this.buttons = buttons;
    }

    public void setIntercept(Predicate<MessageEnvelope> intercept) {
        this.intercept = intercept;
    }

    @Override
    public void handleButton(ButtonPress press, TelegramApi via) throws Exception {
        buttons.press(press, via);
    }

    /** Turns in progress, oldest first. */
    public List<Running> running() {
        return running.values().stream().sorted(Comparator.comparing(Running::startedAt)).toList();
    }

    public int queuedHandoffs() {
        synchronized (deferred) {
            return deferred.size();
        }
    }

    /** What {@code /stopall} did. */
    public record StopResult(List<Running> interrupted, int queuedDropped) {}

    /**
     * {@code /stopall}: interrupts every running turn (each one tells T3 to interrupt its run),
     * drops queued hand-offs, and resets the circuit breaker so nothing in flight can cascade.
     */
    public StopResult stopAll() {
        int dropped = clearDeferred();
        breaker.resetAll(); // first, so a turn finishing right now can't start a hand-off
        List<Running> stopped = running();
        stopped.forEach(r -> r.future().cancel(true));
        Log.info("stopall: interrupted " + stopped.size() + " turn(s), dropped " + dropped + " queued hand-off(s)");
        return new StopResult(stopped, dropped);
    }

    @Override
    public void handle(MessageEnvelope envelope, TelegramApi via) throws Exception {
        if (envelope.command() != null) {
            handleCommand(envelope);
            return;
        }
        if (intercept.test(envelope)) return;
        Set<String> targets = envelope.targetAgents();
        if (targets.isEmpty()) return;
        if (!envelope.sender().isAdmin()) {
            Log.info("message " + envelope.messageId() + " from non-admin " + envelope.sender().displayName()
                    + " addresses " + targets + "; only the admin can start agents");
            return;
        }
        for (String agent : targets) {
            start(new Turn(envelope.chainId(), agent, 1, envelope.chatId(), envelope.messageId(), envelope.text(),
                    envelope.sender().displayName(), null));
        }
    }

    private void handleCommand(MessageEnvelope e) throws Exception {
        Command command = e.command();
        switch (command.name()) {
            case "mode" -> handleMode(e, command.args());
            case "discuss" -> handleDiscuss(e, command.args());
            default -> {
                String reply = commands.handle(command, e, this);
                if (reply != null) outbox.notice(e.receivedBy(), e.chatId(), e.messageId(), reply);
            }
        }
    }

    private void handleMode(MessageEnvelope e, String args) throws Exception {
        String wanted = args.strip().toLowerCase(Locale.ROOT);
        String reply;
        if (wanted.isEmpty()) {
            reply = "Mode is " + settings.mode().name().toLowerCase(Locale.ROOT) + ".";
        } else if (wanted.equals("manual") || wanted.equals("autonomous")) {
            Settings.Mode mode = Settings.Mode.valueOf(wanted.toUpperCase(Locale.ROOT));
            settings.setMode(mode);
            reply = mode == Settings.Mode.AUTONOMOUS
                    ? "Mode is now autonomous: agents can hand work to each other by mentioning them."
                    : "Mode is now manual: agents only act when you address them.";
            Log.info("mode set to " + mode);
        } else {
            reply = "Usage: /mode [manual|autonomous]";
        }
        outbox.notice(e.receivedBy(), e.chatId(), e.messageId(), reply);
    }

    /** {@code /discuss <n> <task mentioning agents>}: a bounded agent conversation, even in manual mode. */
    private void handleDiscuss(MessageEnvelope e, String args) throws Exception {
        String[] parts = args.strip().split("\\s+", 2);
        Integer turnsWanted = parts.length == 2 ? parseTurns(parts[0]) : null;
        Set<String> targets = turnsWanted == null ? Set.of() : mentions.mentionedAgents(parts[1], true);
        if (turnsWanted == null || targets.isEmpty()) {
            outbox.notice(e.receivedBy(), e.chatId(), e.messageId(),
                    "Usage: /discuss <turns> <task that mentions at least one agent>, e.g. /discuss 4 @"
                            + directory.all().iterator().next().agentKey() + " design it");
            return;
        }
        int granted = breaker.openDiscussion(e.chainId(), turnsWanted);
        if (granted < turnsWanted) {
            outbox.notice(e.receivedBy(), e.chatId(), e.messageId(), "Capped at " + granted + " turns.");
        }
        for (String agent : targets) {
            start(new Turn(e.chainId(), agent, 1, e.chatId(), e.messageId(), parts[1], e.sender().displayName(), null));
        }
    }

    private static Integer parseTurns(String s) {
        try {
            int n = Integer.parseInt(s);
            return n > 0 ? n : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Admits and launches one turn, or reports why not. Never blocks on the agent. */
    void start(Turn turn) throws Exception {
        start(turn, null);
    }

    /** @param deferredSince when this hand-off first had to wait, or null on its first attempt */
    private void start(Turn turn, Instant deferredSince) throws Exception {
        CircuitBreaker.Decision decision = breaker.admit(turn.chainId(), turn.agentKey(), turn.depth(), turn.humanDirect());
        if (decision instanceof CircuitBreaker.Denied denied) {
            boolean waitable = denied.reason() == CircuitBreaker.Reason.AGENT_BUSY
                    || denied.reason() == CircuitBreaker.Reason.COOLDOWN;
            if (waitable && !turn.humanDirect()) {
                defer(turn, denied, deferredSince);
                return;
            }
            invocations.denied(turn.chainId(), turn.agentKey(), turn.depth(), turn.chatId(), turn.triggerMessageId(),
                    denied.reason().name());
            reportDenial(turn, denied);
            return;
        }
        long epoch = ((CircuitBreaker.Admitted) decision).epoch();
        long invocationId = invocations.start(turn.chainId(), turn.agentKey(), turn.depth(), turn.chatId(),
                turn.triggerMessageId());
        Log.info("turn " + invocationId + ": " + turn.agentKey() + " at depth " + turn.depth() + " for "
                + turn.requestedBy() + " (chain " + turn.chainId() + ")");
        inFlight.incrementAndGet();
        try {
            // The task waits until it's registered before removing itself, so even a turn that
            // finishes instantly can't leave a stale entry behind.
            CompletableFuture<Future<?>> self = new CompletableFuture<>();
            Future<?> future = turns.submit(() -> {
                try {
                    runTurn(turn, epoch, invocationId);
                } finally {
                    self.join();
                    running.remove(invocationId);
                    finished(); // last, so "idle" means no turn is listed as running
                }
            });
            running.put(invocationId, new Running(invocationId, turn, clock.instant(), future));
            self.complete(future);
        } catch (RuntimeException e) {
            running.remove(invocationId);
            finished();
            breaker.release(turn.agentKey(), epoch);
            throw e;
        }
    }

    private void defer(Turn turn, CircuitBreaker.Denied denied, Instant since) throws Exception {
        Instant now = clock.instant();
        if (since != null && Duration.between(since, now).compareTo(MAX_DEFER) > 0) {
            Log.info("dropping hand-off to " + turn.agentKey() + ": still " + denied.reason() + " after "
                    + MAX_DEFER.toMinutes() + " minutes");
            invocations.denied(turn.chainId(), turn.agentKey(), turn.depth(), turn.chatId(), turn.triggerMessageId(),
                    "expired_" + denied.reason().name());
            return;
        }
        if (since == null) {
            Log.info("queued hand-off to " + turn.agentKey() + " at depth " + turn.depth() + " until it's free ("
                    + denied.detail() + ")");
        }
        synchronized (deferred) {
            deferred.add(new Deferred(turn, since == null ? now : since));
        }
    }

    private void retryLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                wake.poll(RETRY_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
                wake.clear();
                List<Deferred> batch;
                synchronized (deferred) {
                    if (deferred.isEmpty()) continue;
                    batch = new ArrayList<>(deferred);
                    deferred.clear();
                    // Counted as in flight while being retried, so the work is never invisible
                    // between leaving the queue and starting.
                    inFlight.incrementAndGet();
                }
                try {
                    for (Deferred d : batch) {
                        try {
                            start(d.turn(), d.since()); // re-queues itself if the agent is still unavailable
                        } catch (InterruptedException e) {
                            throw e;
                        } catch (Exception e) {
                            Log.warn("queued hand-off to " + d.turn().agentKey() + " failed to start: " + e);
                        }
                    }
                } finally {
                    inFlight.decrementAndGet(); // no wake: nothing new became free
                }
            }
        } catch (InterruptedException e) {
            // closing
        }
    }

    /** {@code /stopall}: forget hand-offs that are waiting for an agent. */
    public int clearDeferred() {
        synchronized (deferred) {
            int n = deferred.size();
            deferred.clear();
            return n;
        }
    }

    private void runTurn(Turn turn, long epoch, long invocationId) {
        Thread.currentThread().setName("turn-" + turn.agentKey() + "-" + invocationId);
        try {
            String reply;
            try {
                reply = runner.run(turn);
            } catch (InterruptedException e) {
                breaker.release(turn.agentKey(), epoch);
                invocations.finish(invocationId, InvocationLog.Status.CANCELLED, null);
                return;
            } catch (Exception e) {
                breaker.release(turn.agentKey(), epoch);
                invocations.finish(invocationId, InvocationLog.Status.FAILED, null);
                Log.warn("turn " + invocationId + " (" + turn.agentKey() + ") failed: " + e);
                outbox.notice(turn.agentKey(), turn.chatId(), turn.triggerMessageId(),
                        directory.displayNameFor(turn.agentKey()) + " couldn't finish: "
                                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                return;
            }
            Optional<CircuitBreaker.Trip> trip = breaker.complete(turn.chainId(), turn.agentKey(), turn.depth(), epoch, reply);
            invocations.finish(invocationId, InvocationLog.Status.COMPLETED, hash(reply));
            long posted = outbox.postReply(turn.agentKey(), turn.chatId(), turn.triggerMessageId(), reply);
            Set<String> next = handoffs(turn, reply);
            if (trip.isPresent()) {
                Log.info("chain " + turn.chainId() + " stopped: " + trip.get().detail()
                        + (next.isEmpty() ? " (no hand-off pending)" : "; not forwarding to " + next));
                // Only worth a chat message when it actually blocked a hand-off.
                if (!next.isEmpty()) {
                    outbox.notice(turn.agentKey(), turn.chatId(), posted, "Stopped this chain: " + trip.get().detail() + ".");
                }
                return;
            }
            for (String agent : next) {
                start(new Turn(turn.chainId(), agent, turn.depth() + 1, turn.chatId(), posted, reply,
                        directory.displayNameFor(turn.agentKey()), turn.agentKey()));
            }
        } catch (Exception e) {
            Log.warn("turn " + invocationId + " (" + turn.agentKey() + ") could not be completed: " + e);
        }
    }

    /** Agents the reply hands off to: those it mentions, if the mode or a /discuss chain allows it. */
    private Set<String> handoffs(Turn turn, String reply) throws Exception {
        Set<String> next = new LinkedHashSet<>(mentions.mentionedAgents(reply));
        next.remove(turn.agentKey());
        if (next.isEmpty()) return next;
        if (settings.mode() != Settings.Mode.AUTONOMOUS && !breaker.isDiscussion(turn.chainId())) {
            Log.info(turn.agentKey() + " mentioned " + next + "; manual mode, so not forwarding");
            return Set.of();
        }
        return next;
    }

    private void reportDenial(Turn turn, CircuitBreaker.Denied denied) throws Exception {
        Log.info("denied " + turn.agentKey() + " at depth " + turn.depth() + ": " + denied.reason() + " (" + denied.detail() + ")");
        String name = directory.displayNameFor(turn.agentKey());
        String text;
        if (denied.reason().endsChain) {
            if (!denied.firstForChain()) return; // one stop notice per chain
            text = "Stopped this chain: " + denied.detail() + ". Mention an agent again to start a new one.";
        } else if (turn.humanDirect()) {
            text = name + " is still working on another request. Ask again once it has replied.";
        } else {
            return; // unreachable: agent hand-offs denied for busy/cooldown are queued, not reported
        }
        outbox.notice(turn.agentKey(), turn.chatId(), turn.triggerMessageId(), text);
    }

    private void finished() {
        inFlight.decrementAndGet();
        wake.offer(Boolean.TRUE); // an agent just became free: try queued hand-offs now
    }

    /** For tests: waits until no turn is running and no hand-off is queued. */
    boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            boolean idle;
            synchronized (deferred) {
                idle = deferred.isEmpty() && inFlight.get() == 0;
            }
            if (idle) return true;
            if (System.nanoTime() > deadline) return false;
            Thread.sleep(20);
        }
    }

    static String hash(String reply) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(ResponseSimilarity.normalize(reply).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Cancels running turns by interrupting their threads. */
    @Override
    public void close() {
        retrier.interrupt();
        clearDeferred();
        turns.shutdownNow();
        try {
            if (!turns.awaitTermination(10, TimeUnit.SECONDS)) Log.warn("some agent turns did not stop within 10s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
