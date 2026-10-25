package curxxed.dev.switchboard.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import curxxed.dev.switchboard.app.Log;
import curxxed.dev.switchboard.dispatch.Turn;
import curxxed.dev.switchboard.gateway.AgentDirectory;
import curxxed.dev.switchboard.gateway.ButtonHandler;
import curxxed.dev.switchboard.gateway.ButtonPress;
import curxxed.dev.switchboard.gateway.MessageEnvelope;
import curxxed.dev.switchboard.t3.T3RpcClient;
import curxxed.dev.switchboard.telegram.TelegramApi;
import curxxed.dev.switchboard.telegram.TelegramApi.Button;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Brings an agent's questions into the group. Each question becomes a message from the agent's bot
 * with its options as buttons. Multi-select questions toggle options and submit with Done. When a
 * question allows its own answer, the admin can reply to its message with text. Once every
 * question in a request is answered, the answers go to T3 as {@code runtime-request.respond}, keyed
 * by question id and shaped as T3's web client sends them: the option's value (or label), a list
 * of them for multi-select, or the typed text.
 */
public final class QuestionDesk implements ButtonHandler {

    static final String PREFIX = "qa:";
    static final String DONE = "done";
    static final Duration RESPOND_TIMEOUT = Duration.ofSeconds(30);

    @FunctionalInterface
    public interface Responder {
        void respond(String threadId, String requestId, Map<String, Object> answers) throws Exception;
    }

    private final class Request {
        final Turn turn;
        final String threadId;
        final RunTracker.QuestionRequest request;
        final Map<String, Object> answers = new LinkedHashMap<>();
        final Map<String, String> shown = new LinkedHashMap<>(); // question id -> answer as displayed
        final Map<Integer, Long> messageIds = new LinkedHashMap<>();
        final Map<Integer, String> tokens = new LinkedHashMap<>();
        final Map<Integer, Set<Integer>> selected = new LinkedHashMap<>();
        boolean submitting;

        Request(Turn turn, String threadId, RunTracker.QuestionRequest request) {
            this.turn = turn;
            this.threadId = threadId;
            this.request = request;
        }

        boolean complete() {
            return answers.size() == request.questions().size();
        }
    }

    private record Slot(Request request, int question) {}

    private final Map<String, TelegramApi> bots;
    private final Responder responder;
    private final AgentDirectory directory;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Request> byRequestId = new LinkedHashMap<>();
    private final Map<String, Slot> byToken = new LinkedHashMap<>();
    private final Map<Long, Slot> byMessage = new LinkedHashMap<>();

    public QuestionDesk(Map<String, TelegramApi> bots, Responder responder, AgentDirectory directory) {
        this.bots = bots;
        this.responder = responder;
        this.directory = directory;
    }

    public static Responder viaT3(T3RpcClient rpc, ObjectMapper mapper) {
        return (threadId, requestId, answers) -> {
            ObjectNode cmd = mapper.createObjectNode();
            cmd.put("type", "runtime-request.respond");
            cmd.put("commandId", UUID.randomUUID().toString());
            cmd.put("threadId", threadId);
            cmd.put("requestId", requestId);
            cmd.set("answers", mapper.valueToTree(answers));
            rpc.call("orchestration.dispatchCommand", cmd, RESPOND_TIMEOUT);
        };
    }

    /** Posts new questions and retires ones answered elsewhere. Called by the runner on changes. */
    public void sync(Turn turn, String threadId, List<RunTracker.QuestionRequest> pending) {
        Set<String> open = pending.stream().map(RunTracker.QuestionRequest::requestId).collect(Collectors.toSet());
        List<Request> settled = new ArrayList<>();
        List<RunTracker.QuestionRequest> fresh = new ArrayList<>();
        synchronized (this) {
            for (Request r : byRequestId.values()) {
                if (r.threadId.equals(threadId) && !r.submitting && !open.contains(r.request.requestId())) settled.add(r);
            }
            settled.forEach(this::forget);
            for (RunTracker.QuestionRequest q : pending) if (!byRequestId.containsKey(q.requestId())) fresh.add(q);
        }
        for (Request r : settled) finishAll(r, "Answered in T3 Code.");
        for (RunTracker.QuestionRequest q : fresh) announce(turn, threadId, q);
    }

    /** The run ended: questions still open can't be answered here any more. */
    public void close(String threadId, String why) {
        List<Request> left = new ArrayList<>();
        synchronized (this) {
            for (Request r : byRequestId.values()) if (r.threadId.equals(threadId) && !r.submitting) left.add(r);
            left.forEach(this::forget);
        }
        for (Request r : left) finishAll(r, "No longer needed: " + why + ".");
    }

    private void announce(Turn turn, String threadId, RunTracker.QuestionRequest q) {
        Request r = new Request(turn, threadId, q);
        synchronized (this) {
            byRequestId.put(q.requestId(), r);
        }
        for (int i = 0; i < q.questions().size(); i++) {
            String token = token();
            try {
                long id = bots.get(turn.agentKey()).sendMessage(turn.chatId(), text(r, i), turn.triggerMessageId(),
                        buttons(r, i, token));
                synchronized (this) {
                    r.messageIds.put(i, id);
                    r.tokens.put(i, token);
                    byToken.put(token, new Slot(r, i));
                    byMessage.put(id, new Slot(r, i));
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                Log.warn("could not post a question from " + turn.agentKey() + ": " + e.getMessage());
            }
        }
        Log.info(turn.agentKey() + " asked " + q.questions().size() + " question(s)");
    }

    @Override
    public void press(ButtonPress press, TelegramApi via) throws Exception {
        String[] parts = press.data().substring(Math.min(PREFIX.length(), press.data().length())).split(":");
        Slot slot;
        String refusal = null;
        boolean alert = false;
        boolean submit = false;
        String toast = null;
        synchronized (this) {
            slot = parts.length == 2 && press.data().startsWith(PREFIX) ? byToken.get(parts[0]) : null;
            if (slot == null || slot.request().submitting) {
                refusal = "This question is no longer open.";
            } else if (!press.fromAdmin()) {
                refusal = "Only the relay administrator can answer.";
                alert = true;
            } else {
                Request r = slot.request();
                RunTracker.Question q = r.request.questions().get(slot.question());
                if (parts[1].equals(DONE)) {
                    Set<Integer> chosen = r.selected.getOrDefault(slot.question(), Set.of());
                    if (chosen.isEmpty()) {
                        refusal = "Pick at least one option first.";
                    } else {
                        List<String> values = new ArrayList<>();
                        List<String> labels = new ArrayList<>();
                        for (int idx : chosen) {
                            values.add(q.options().get(idx).answerValue());
                            labels.add(q.options().get(idx).label());
                        }
                        r.answers.put(q.id(), values);
                        r.shown.put(q.id(), String.join(", ", labels));
                        submit = true;
                    }
                } else {
                    int idx = parseIndex(parts[1]);
                    if (idx < 0 || idx >= q.options().size()) {
                        refusal = "Unknown option.";
                    } else if (q.multiSelect()) {
                        Set<Integer> chosen = r.selected.computeIfAbsent(slot.question(), k -> new LinkedHashSet<>());
                        if (!chosen.remove(idx)) chosen.add(idx);
                        toast = chosen.contains(idx) ? "Selected" : "Unselected";
                    } else {
                        r.answers.put(q.id(), q.options().get(idx).answerValue());
                        r.shown.put(q.id(), q.options().get(idx).label());
                        submit = true;
                    }
                }
            }
        }
        if (refusal != null) {
            via.answerCallbackQuery(press.queryId(), refusal, alert);
            return;
        }
        if (toast != null) { // multi-select toggle: redraw the buttons with their ✓ marks
            via.answerCallbackQuery(press.queryId(), toast, false);
            Request r = slot.request();
            edit(r, slot.question(), text(r, slot.question()), buttons(r, slot.question(), r.tokens.get(slot.question())));
            return;
        }
        via.answerCallbackQuery(press.queryId(), "Answer recorded", false);
        if (submit) answered(slot, press.fromName());
    }

    /**
     * Takes the admin's reply to a question message as a typed answer.
     *
     * @return true if the message was an answer, so it must not also start an agent turn
     */
    public boolean consumeReply(MessageEnvelope e) {
        if (e.replyTo() == null || !e.sender().isAdmin()) return false;
        Slot slot;
        boolean allowed;
        synchronized (this) {
            slot = byMessage.get(e.replyTo().messageId());
            if (slot == null || slot.request().submitting) return false;
            RunTracker.Question q = slot.request().request.questions().get(slot.question());
            allowed = q.allowCustom();
            if (allowed) {
                slot.request().answers.put(q.id(), e.text().strip());
                slot.request().shown.put(q.id(), "\"" + RunTracker.clip(e.text().strip(), 200) + "\"");
            }
        }
        if (!allowed) {
            send(slot.request(), "That question needs one of its listed options; tap a button instead.");
            return true;
        }
        answered(slot, e.sender().displayName());
        return true;
    }

    /** One question got its answer: submit if it was the last, otherwise show it and wait. */
    private void answered(Slot slot, String by) {
        Request r = slot.request();
        boolean submitNow;
        synchronized (this) {
            submitNow = r.complete() && !r.submitting;
            if (submitNow) r.submitting = true;
        }
        if (!submitNow) {
            String qid = r.request.questions().get(slot.question()).id();
            edit(r, slot.question(), text(r, slot.question()) + "\n\nYour answer: " + r.shown.get(qid)
                    + ". Waiting for the other questions.", null);
            return;
        }
        try {
            responder.respond(r.threadId, r.request.requestId(), new LinkedHashMap<>(r.answers));
        } catch (Exception e) {
            synchronized (this) {
                r.submitting = false;
                r.answers.clear();
                r.shown.clear();
                r.selected.clear();
            }
            Log.warn("T3 rejected answers for request …" + ApprovalDesk.shortId(r.request.requestId()) + ": " + e.getMessage());
            send(r, "T3 didn't accept the answer: " + RunTracker.clip(String.valueOf(e.getMessage()), 200) + ". Please answer again.");
            for (int i = 0; i < r.request.questions().size(); i++) edit(r, i, text(r, i), buttons(r, i, r.tokens.get(i)));
            return;
        }
        synchronized (this) {
            forget(r);
        }
        Log.info(r.turn.agentKey() + " got answers to request …" + ApprovalDesk.shortId(r.request.requestId()) + " from " + by);
        for (int i = 0; i < r.request.questions().size(); i++) {
            String qid = r.request.questions().get(i).id();
            edit(r, i, text(r, i) + "\n\nAnswered by " + by + ": " + r.shown.get(qid), null);
        }
    }

    private void finishAll(Request r, String note) {
        for (int i : r.messageIds.keySet()) edit(r, i, text(r, i) + "\n\n" + note, null);
    }

    private void forget(Request r) {
        byRequestId.remove(r.request.requestId());
        r.tokens.values().forEach(byToken::remove);
        r.messageIds.values().forEach(byMessage::remove);
    }

    private String text(Request r, int index) {
        RunTracker.Question q = r.request.questions().get(index);
        StringBuilder s = new StringBuilder(directory.displayNameFor(r.turn.agentKey())).append(" asks");
        if (r.request.questions().size() > 1) s.append(" (").append(index + 1).append('/').append(r.request.questions().size()).append(')');
        s.append(": ");
        if (!q.header().isBlank()) s.append(q.header()).append('\n');
        s.append(q.question());
        for (RunTracker.QuestionOption o : q.options()) {
            if (!o.description().isBlank()) s.append("\n• ").append(o.label()).append(": ").append(o.description());
        }
        if (q.options().isEmpty() || q.allowCustom()) {
            s.append(q.options().isEmpty() ? "\n\nReply to this message with your answer."
                    : "\n\nTap " + (q.multiSelect() ? "options, then Done," : "an option,") + " or reply to this message with your own answer.");
        }
        return s.toString();
    }

    private List<List<Button>> buttons(Request r, int index, String token) {
        RunTracker.Question q = r.request.questions().get(index);
        if (q.options().isEmpty()) return null;
        Set<Integer> chosen = r.selected.getOrDefault(index, Set.of());
        List<List<Button>> rows = new ArrayList<>();
        for (int i = 0; i < q.options().size(); i++) {
            String label = (q.multiSelect() && chosen.contains(i) ? "✓ " : "") + q.options().get(i).label();
            rows.add(List.of(new Button(label, PREFIX + token + ":" + i)));
        }
        if (q.multiSelect()) rows.add(List.of(new Button("Done", PREFIX + token + ":" + DONE)));
        return rows;
    }

    private void edit(Request r, int index, String text, List<List<Button>> buttons) {
        Long id = r.messageIds.get(index);
        if (id == null) return;
        try {
            bots.get(r.turn.agentKey()).editMessageText(r.turn.chatId(), id, text, buttons);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.warn("could not update a question message: " + e.getMessage());
        }
    }

    private void send(Request r, String text) {
        try {
            bots.get(r.turn.agentKey()).sendMessage(r.turn.chatId(), text, r.messageIds.values().stream().findFirst().orElse(null));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.warn("could not post to the group: " + e.getMessage());
        }
    }

    private String token() {
        byte[] bytes = new byte[4];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static int parseIndex(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Requests still waiting for answers, for tests and diagnostics. */
    synchronized int open() {
        return byRequestId.size();
    }
}
