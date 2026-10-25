package curxxed.dev.switchboard.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import curxxed.dev.switchboard.app.Log;
import curxxed.dev.switchboard.config.AppConfig;
import curxxed.dev.switchboard.dispatch.AgentRunner;
import curxxed.dev.switchboard.dispatch.Turn;
import curxxed.dev.switchboard.store.AgentStore;
import curxxed.dev.switchboard.store.MessageLedger;
import curxxed.dev.switchboard.store.Settings;
import curxxed.dev.switchboard.t3.T3RpcClient;
import curxxed.dev.switchboard.t3.T3RpcClient.T3RpcException;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Runs an agent turn in T3 Code: one T3 thread per (agent, project), reused across turns so each
 * agent keeps its own conversation, tools and worktree.
 */
public final class T3AgentRunner implements AgentRunner {

    static final Duration LAUNCH_TIMEOUT = Duration.ofMinutes(3); // includes creating a worktree
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_RESUBSCRIBES = 3;
    static final int SHOWN_STEPS = 6;

    /** A run that ended without completing. */
    public static final class RunFailedException extends IOException {
        private final JsonNode details;

        RunFailedException(String message) {
            this(message, new ObjectMapper().createObjectNode().put("message", message));
        }

        RunFailedException(String message, JsonNode details) {
            super(message);
            this.details = details.deepCopy();
        }

        public JsonNode details() {
            return details.deepCopy();
        }
    }

    private final AppConfig config;
    private final AgentStore agents;
    private final MessageLedger ledger;
    private final Settings settings;
    private final ProjectResolver projects;
    private final T3RpcClient rpc;
    private final ProgressReporter progress;
    private final HandoffPolicy handoffs;
    private final ApprovalDesk approvals;
    private final QuestionDesk questions;
    private final PromptBuilder prompts;
    private final ObjectMapper mapper;
    private final Clock clock;

    public T3AgentRunner(AppConfig config, AgentStore agents, MessageLedger ledger, Settings settings,
                         ProjectResolver projects, T3RpcClient rpc, ProgressReporter progress, HandoffPolicy handoffs,
                         ApprovalDesk approvals, QuestionDesk questions, ObjectMapper mapper, Clock clock) {
        this.config = config;
        this.prompts = new PromptBuilder(config);
        this.agents = agents;
        this.ledger = ledger;
        this.settings = settings;
        this.projects = projects;
        this.rpc = rpc;
        this.progress = progress;
        this.handoffs = handoffs;
        this.approvals = approvals;
        this.questions = questions;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public String run(Turn turn) throws Exception {
        String name = config.displayNameFor(turn.agentKey());
        ProjectResolver.Project project = projects.resolve(settings.activeProjectId().orElse(config.t3().project()));
        Optional<AgentStore.AgentThread> existing = agents.thread(turn.agentKey(), project.id());
        String prompt = prompts.build(turn, ledger.recent(turn.chatId(), config.t3().effectiveContextMessages()),
                new PromptBuilder.Context(project.title(), workspaceText(project, existing), handoffs.live(turn),
                        handoffs.hopsLeft(turn), existing.isEmpty()));

        Instant started = clock.instant();
        ProgressReporter.Progress shown = progress.start(turn, name + " is starting…");
        String messageId = UUID.randomUUID().toString();
        RunTracker tracker = new RunTracker(messageId);
        LinkedBlockingQueue<JsonNode> items = new LinkedBlockingQueue<>();
        String threadId = null;
        T3RpcClient.Subscription subscription = null;
        String ended = "the run ended";
        try {
            if (existing.isPresent()) {
                threadId = existing.get().t3ThreadId();
                try {
                    subscription = subscribe(threadId, items, tracker);
                    rpc.call("orchestration.dispatchCommand", dispatchPayload(turn, threadId, messageId, prompt), CALL_TIMEOUT);
                } catch (T3RpcException e) {
                    if (!threadGone(e)) throw e;
                    Log.warn(turn.agentKey() + "'s T3 thread " + threadId + " is gone (" + e.getMessage() + "); launching a new one");
                    if (subscription != null) subscription.close();
                    agents.deleteThread(turn.agentKey(), project.id());
                    String fresh = prompts.build(turn, ledger.recent(turn.chatId(), config.t3().effectiveContextMessages()),
                            new PromptBuilder.Context(project.title(), workspaceText(project, Optional.empty()),
                                    handoffs.live(turn), handoffs.hopsLeft(turn), true));
                    threadId = launch(turn, project, messageId, fresh, tracker);
                    subscription = subscribe(threadId, items, tracker);
                }
            } else {
                shown.update(name + " is setting up" + (project.usesWorktrees() ? " its worktree…" : "…"));
                threadId = launch(turn, project, messageId, prompt, tracker);
                subscription = subscribe(threadId, items, tracker);
            }

            syncDesks(turn, threadId, tracker); // the run may already be waiting
            Instant deadline = started.plus(config.t3().turnTimeout());
            int resubscribes = 0;
            while (!tracker.finished()) {
                JsonNode item = items.poll(1, TimeUnit.SECONDS);
                boolean changed = false;
                while (item != null) {
                    changed |= tracker.accept(item);
                    item = items.poll();
                }
                if (changed) syncDesks(turn, threadId, tracker);
                if (subscription.done().isDone() && !tracker.finished()) {
                    if (++resubscribes > MAX_RESUBSCRIBES) throw new IOException("lost the T3 stream for " + name + "'s run");
                    Log.warn(turn.agentKey() + " stream ended mid-run; resubscribing (" + resubscribes + "/" + MAX_RESUBSCRIBES + ")");
                    subscription.close();
                    subscription = subscribe(threadId, items, tracker);
                }
                if (clock.instant().isAfter(deadline)) {
                    interruptRun(threadId, tracker, "timed out");
                    shown.dismiss();
                    throw new RunFailedException(name + " timed out after " + config.t3().turnTimeout().toMinutes() + " minutes");
                }
                shown.update(render(name, tracker, Duration.between(started, clock.instant())));
            }

            // The status message has done its job: the reply (or the dispatcher's failure notice)
            // takes its place, so remove it rather than leave a "finished in" line behind.
            shown.dismiss();
            String status = tracker.status().orElse("unknown");
            if (status.equals("completed")) ended = "the run finished";
            if (!status.equals("completed")) {
                throw new RunFailedException(name + "'s run " + status + tracker.failure().map(f -> ": " + f).orElse(""),
                        tracker.failureDetails().orElseGet(() -> mapper.createObjectNode()));
            }
            String reply = tracker.finalReply();
            return reply.isEmpty() ? "(" + name + " finished without a written reply.)" : reply;
        } catch (InterruptedException e) {
            ended = "the run was stopped";
            interruptRun(threadId, tracker, "stopped from Telegram");
            shown.finish(name + " was stopped.");
            throw e;
        } catch (RunFailedException e) {
            throw e; // already dismissed above
        } catch (IOException | RuntimeException e) {
            shown.dismiss(); // the dispatcher posts "couldn't finish: …" with the reason
            throw e;
        } finally {
            if (subscription != null) subscription.close();
            if (threadId != null) {
                approvals.close(threadId, ended);
                questions.close(threadId, ended);
            }
        }
    }

    private void syncDesks(Turn turn, String threadId, RunTracker tracker) {
        approvals.sync(turn, threadId, tracker.pendingApprovals());
        questions.sync(turn, threadId, tracker.pendingQuestions());
    }

    /** Creates the agent's thread with the prompt as its first message, which starts the first run. */
    private String launch(Turn turn, ProjectResolver.Project project, String messageId, String prompt, RunTracker tracker)
            throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("commandId", UUID.randomUUID().toString());
        payload.put("projectId", project.id());
        payload.put("title", "Telegram: " + config.displayNameFor(turn.agentKey()));
        payload.put("generateTitle", false);
        payload.set("modelSelection", modelSelection(turn.agentKey()));
        payload.put("runtimeMode", config.t3().effectiveRuntimeMode());
        payload.put("interactionMode", "default");
        ObjectNode strategy = payload.putObject("workspaceStrategy");
        if (project.usesWorktrees()) strategy.put("type", "worktree").put("baseRef", project.gitBranch());
        else strategy.put("type", "root");
        ObjectNode initial = payload.putObject("initialMessage");
        initial.put("messageId", messageId);
        initial.put("text", prompt);
        initial.putArray("attachments");

        JsonNode result = rpc.call("orchestration.launchThread", payload, LAUNCH_TIMEOUT);
        String threadId = result.path("threadId").asText();
        JsonNode thread = result.path("projection").path("thread");
        tracker.applySnapshot(result.path("projection"));
        agents.saveThread(new AgentStore.AgentThread(turn.agentKey(), project.id(), threadId,
                thread.path("worktreePath").asText(null), thread.path("branch").asText(null)));
        Log.info(turn.agentKey() + " launched T3 thread " + threadId + " in " + project.title()
                + (thread.hasNonNull("worktreePath") ? " (worktree " + thread.path("worktreePath").asText() + ")" : ""));
        return threadId;
    }

    /**
     * Subscribes before anything is dispatched, so no update to the new run can be missed. T3
     * answers with a snapshot (bounded, so long threads stay small) and then live events.
     */
    private T3RpcClient.Subscription subscribe(String threadId, LinkedBlockingQueue<JsonNode> items, RunTracker tracker)
            throws IOException, InterruptedException {
        ObjectNode payload = mapper.createObjectNode().put("threadId", threadId).put("acceptBoundedSnapshot", true);
        T3RpcClient.Subscription sub = rpc.subscribe("orchestration.subscribeThread", payload, items::add);
        Instant deadline = clock.instant().plus(CALL_TIMEOUT);
        JsonNode first = null;
        // Poll in short steps so a rejected subscription (e.g. a deleted thread) fails at once.
        while (first == null && !sub.done().isDone() && clock.instant().isBefore(deadline)) {
            first = items.poll(250, TimeUnit.MILLISECONDS);
        }
        if (first == null) first = items.poll();
        if (first == null) {
            sub.close();
            if (sub.done().isCompletedExceptionally()) {
                try {
                    sub.done().join();
                } catch (Exception e) {
                    if (e.getCause() instanceof IOException io) throw io;
                    throw new IOException(e.getCause());
                }
            }
            throw new IOException("T3 sent nothing for thread " + threadId + " within " + CALL_TIMEOUT.toSeconds() + "s");
        }
        tracker.accept(first);
        return sub;
    }

    private ObjectNode dispatchPayload(Turn turn, String threadId, String messageId, String prompt) throws Exception {
        ObjectNode cmd = mapper.createObjectNode();
        cmd.put("type", "message.dispatch");
        cmd.put("createdBy", "user");
        cmd.put("creationSource", "web");
        cmd.put("commandId", UUID.randomUUID().toString());
        cmd.put("threadId", threadId);
        cmd.put("messageId", messageId);
        cmd.put("text", prompt);
        cmd.putArray("attachments");
        cmd.set("modelSelection", modelSelection(turn.agentKey())); // applies /model changes per turn
        cmd.putObject("dispatchMode").put("type", "queue_after_active");
        return cmd;
    }

    private ObjectNode modelSelection(String agentKey) throws Exception {
        AppConfig.Agent agent = config.agents().get(agentKey);
        return mapper.createObjectNode()
                .put("instanceId", agent.instanceId())
                .put("model", agents.modelOverride(agentKey).orElse(agent.model()));
    }

    private void interruptRun(String threadId, RunTracker tracker, String reason) {
        if (threadId == null || tracker.runId().isEmpty() || tracker.finished()) return;
        ObjectNode cmd = mapper.createObjectNode();
        cmd.put("type", "run.interrupt");
        cmd.put("commandId", UUID.randomUUID().toString());
        cmd.put("threadId", threadId);
        cmd.put("runId", tracker.runId().get());
        cmd.put("reason", reason);
        // Runs on a fresh thread: the caller may have been interrupted, and the call must still go out.
        Thread sender = Thread.startVirtualThread(() -> {
            try {
                rpc.call("orchestration.dispatchCommand", cmd, CALL_TIMEOUT);
                Log.info("interrupted T3 run " + tracker.runId().get() + " (" + reason + ")");
            } catch (Exception e) {
                Log.warn("could not interrupt T3 run " + tracker.runId().get() + ": " + e.getMessage());
            }
        });
        try {
            sender.join(CALL_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static boolean threadGone(T3RpcException e) {
        String m = e.getMessage().toLowerCase(Locale.ROOT);
        return m.contains("not found") || m.contains("does not exist") || m.contains("no longer exists")
                || m.contains("deleted");
    }

    static String render(String name, RunTracker t, Duration elapsed) {
        String status = t.status().orElse("starting");
        StringBuilder s = new StringBuilder(name);
        switch (status) {
            case "preparing" -> s.append(" is preparing its workspace");
            case "queued" -> s.append(" is queued behind another run");
            case "waiting" -> s.append(" is waiting");
            default -> s.append(" is working");
        }
        s.append(" · ").append(format(elapsed));
        List<RunTracker.Activity> steps = t.activities();
        int hidden = Math.max(0, steps.size() - SHOWN_STEPS);
        if (hidden > 0) s.append("\n(").append(hidden).append(" earlier step").append(hidden == 1 ? "" : "s").append(")");
        for (RunTracker.Activity a : steps.subList(hidden, steps.size())) s.append("\n• ").append(a.text());
        if (!t.pendingApprovals().isEmpty()) {
            s.append("\nWaiting for your approval (buttons below).");
        } else if (!t.pendingQuestions().isEmpty()) {
            s.append("\nWaiting for your answer to the question below.");
        } else {
            t.waitingOn().ifPresent(w -> s.append("\nWaiting in T3 Code for ").append(w).append(". Answer it in the T3 Code app."));
        }
        return s.toString();
    }

    static String format(Duration d) {
        long sec = d.toSeconds();
        return sec < 60 ? sec + "s" : (sec / 60) + "m " + String.format("%02d", sec % 60) + "s";
    }

    /**
     * Where the agent works, as T3 actually set it up. T3 can give a thread its own folder even
     * without git (its scratch project does), so a known thread path beats what the strategy implies.
     */
    static String workspaceText(ProjectResolver.Project project, Optional<AgentStore.AgentThread> thread) {
        String path = thread.map(AgentStore.AgentThread::worktreePath).orElse(null);
        if (path != null && !path.equals(project.workspaceRoot())) {
            return "You work in your own folder " + path
                    + (project.usesWorktrees() ? ", a git worktree" + (thread.get().branch() != null ? " on branch " + thread.get().branch() : "") : "")
                    + "; the other agents have their own.";
        }
        if (path != null) {
            return "You share the folder " + path + " with the other agents, so coordinate before editing the same files.";
        }
        return project.usesWorktrees()
                ? "You get your own git worktree of " + project.workspaceRoot() + " (based on " + project.gitBranch()
                  + "); the other agents have their own."
                : "You work in the project folder " + project.workspaceRoot()
                  + " or a folder T3 Code sets up for you; other agents may share it, so coordinate before editing the same files.";
    }
}
