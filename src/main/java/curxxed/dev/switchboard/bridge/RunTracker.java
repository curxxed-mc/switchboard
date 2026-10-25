package curxxed.dev.switchboard.bridge;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Follows one T3 run, the one started by our message, through {@code subscribeThread} items: a
 * {@code snapshot} with the whole projection, then {@code event}s that each carry a complete,
 * updated entity ({@code run.updated}, {@code message.updated}, {@code turn-item.updated}).
 * Entities are kept by id and filtered to our run when read, so events may arrive in any order.
 */
public final class RunTracker {

    static final Set<String> TERMINAL = Set.of("completed", "interrupted", "failed", "cancelled", "rolled_back");

    /** One step of the run, rendered for humans. */
    public record Activity(String id, int ordinal, String text, String status) {}

    /** A choice T3 offers for an approval, e.g. {@code accept} labelled "Yes, run it". */
    public record ApprovalOption(String decision, String label, String warning) {}

    /**
     * An approval the run is blocked on.
     *
     * @param kind    {@code command}, {@code file-read}, {@code file-change}, {@code mcp-elicitation} or {@code permission}
     * @param options what the provider offers; empty means the standard approve/deny set
     */
    public record Approval(String requestId, String kind, String prompt, String reason, List<ApprovalOption> options) {}

    /** One answer an agent's question offers; {@code value} is what T3 expects back (label if absent). */
    public record QuestionOption(String label, String description, String value) {
        public String answerValue() {
            return value != null ? value : label;
        }
    }

    /** @param allowCustom true unless T3 says free-text answers aren't allowed */
    public record Question(String id, String header, String question, List<QuestionOption> options,
                           boolean multiSelect, boolean allowCustom) {}

    /** An agent's question(s) the run is blocked on; all must be answered together. */
    public record QuestionRequest(String requestId, List<Question> questions) {}

    private final String userMessageId;
    private JsonNode run;
    private final Map<String, JsonNode> messages = new LinkedHashMap<>();
    private final Map<String, JsonNode> items = new LinkedHashMap<>();

    public RunTracker(String userMessageId) {
        this.userMessageId = userMessageId;
    }

    /** Applies one stream item; returns true if it touched our run. */
    public boolean accept(JsonNode item) {
        return switch (item.path("kind").asText()) {
            case "snapshot" -> applySnapshot(item.path("projection"));
            case "event" -> applyEvent(item.path("event"));
            default -> false; // "synchronized" and kinds this build doesn't know
        };
    }

    /** Applies a projection from a snapshot or {@code launchThread}'s result. */
    public boolean applySnapshot(JsonNode projection) {
        boolean touched = false;
        for (JsonNode r : projection.path("runs")) touched |= upsertRun(r);
        for (JsonNode m : projection.path("messages")) messages.put(m.path("id").asText(), m);
        for (JsonNode i : projection.path("turnItems")) items.put(i.path("id").asText(), i);
        for (JsonNode v : projection.path("visibleTurnItems")) {
            JsonNode i = v.path("item");
            if (i.has("id")) items.putIfAbsent(i.path("id").asText(), i);
        }
        return touched || run != null;
    }

    private boolean applyEvent(JsonNode event) {
        JsonNode payload = event.path("payload");
        switch (event.path("type").asText()) {
            case "run.created", "run.updated" -> {
                return upsertRun(payload);
            }
            case "message.updated" -> {
                messages.put(payload.path("id").asText(), payload);
                return belongsToRun(payload);
            }
            case "turn-item.updated" -> {
                items.put(payload.path("id").asText(), payload);
                return belongsToRun(payload);
            }
            default -> {
                return false;
            }
        }
    }

    private boolean upsertRun(JsonNode r) {
        if (!userMessageId.equals(r.path("userMessageId").asText())) return false;
        run = r;
        return true;
    }

    private boolean belongsToRun(JsonNode entity) {
        return run != null && run.path("id").asText().equals(entity.path("runId").asText());
    }

    public Optional<String> runId() {
        return run == null ? Optional.empty() : Optional.of(run.path("id").asText());
    }

    /** {@code preparing}, {@code queued}, {@code running}, {@code waiting}, … or empty before the run exists. */
    public Optional<String> status() {
        return run == null ? Optional.empty() : Optional.of(run.path("status").asText());
    }

    public boolean finished() {
        return status().map(TERMINAL::contains).orElse(false);
    }

    /**
     * The agent's answer: its last assistant message in this run. Agents often narrate between tool
     * calls ("Let me check the tests…"); the last message is the one that answers the request.
     */
    public String finalReply() {
        return runMessages().stream()
                .filter(m -> "assistant".equals(m.path("role").asText()))
                .map(m -> m.path("text").asText().strip())
                .filter(t -> !t.isEmpty())
                .reduce((first, second) -> second)
                .orElse("");
    }

    /** Tool steps of this run, oldest first: commands, file edits, searches, MCP tools, approvals. */
    public List<Activity> activities() {
        List<Activity> out = new ArrayList<>();
        for (JsonNode i : runItems()) {
            String text = describe(i);
            if (text != null) out.add(new Activity(i.path("id").asText(), i.path("ordinal").asInt(), text, i.path("status").asText()));
        }
        return out;
    }

    /** What the run is blocked on, when T3 is waiting for an approval or an answer. */
    public Optional<String> waitingOn() {
        for (JsonNode i : runItems()) {
            String type = i.path("type").asText();
            String status = i.path("status").asText();
            boolean open = status.equals("pending") || status.equals("waiting");
            if (open && type.equals("approval_request")) {
                String what = firstNonBlank(i.path("prompt").asText(null), i.path("reason").asText(null),
                        i.path("requestKind").asText("an action"));
                return Optional.of("approval for " + clip(what, 120));
            }
            if (open && type.equals("user_input_request")) return Optional.of("an answer to a question");
        }
        return "waiting".equals(status().orElse("")) ? Optional.of("input") : Optional.empty();
    }

    /** Approval requests of this run that are still waiting for a decision. */
    public List<Approval> pendingApprovals() {
        List<Approval> out = new ArrayList<>();
        for (JsonNode i : runItems()) {
            if (!"approval_request".equals(i.path("type").asText())) continue;
            String status = i.path("status").asText();
            if (!status.equals("pending") && !status.equals("waiting")) continue;
            List<ApprovalOption> options = new ArrayList<>();
            for (JsonNode o : i.path("options")) {
                options.add(new ApprovalOption(o.path("decision").asText(), o.path("label").asText(o.path("decision").asText()),
                        o.path("warning").asText(null)));
            }
            out.add(new Approval(i.path("requestId").asText(), i.path("requestKind").asText("permission"),
                    i.path("prompt").asText(null), i.path("reason").asText(null), options));
        }
        return out;
    }

    /** Questions this run is waiting for the user to answer. */
    public List<QuestionRequest> pendingQuestions() {
        List<QuestionRequest> out = new ArrayList<>();
        for (JsonNode i : runItems()) {
            if (!"user_input_request".equals(i.path("type").asText())) continue;
            String status = i.path("status").asText();
            if (!status.equals("pending") && !status.equals("waiting")) continue;
            List<Question> questions = new ArrayList<>();
            for (JsonNode q : i.path("questions")) {
                List<QuestionOption> options = new ArrayList<>();
                for (JsonNode o : q.path("options")) {
                    options.add(new QuestionOption(o.path("label").asText(), o.path("description").asText(""),
                            o.hasNonNull("value") ? o.get("value").asText() : null));
                }
                questions.add(new Question(q.path("id").asText(), q.path("header").asText(""), q.path("question").asText(""),
                        options, q.path("multiSelect").asBoolean(false), q.path("allowCustomAnswer").asBoolean(true)));
            }
            if (!questions.isEmpty()) out.add(new QuestionRequest(i.path("requestId").asText(), questions));
        }
        return out;
    }

    /** Why the run failed, from its error items, or empty. */
    public Optional<String> failure() {
        return runItems().stream()
                .filter(i -> "error".equals(i.path("type").asText()))
                .map(i -> i.path("failure").path("message").asText(""))
                .filter(m -> !m.isBlank())
                .reduce((first, second) -> second);
    }

    public Optional<JsonNode> failureDetails() {
        return runItems().stream()
                .filter(item -> "error".equals(item.path("type").asText()))
                .map(item -> item.path("failure"))
                .filter(JsonNode::isObject)
                .reduce((first, second) -> second);
    }

    private List<JsonNode> runMessages() {
        if (run == null) return List.of();
        String id = run.path("id").asText();
        return messages.values().stream().filter(m -> id.equals(m.path("runId").asText()))
                .sorted(Comparator.comparing(m -> m.path("createdAt").asText())).toList();
    }

    private List<JsonNode> runItems() {
        if (run == null) return List.of();
        String id = run.path("id").asText();
        return items.values().stream().filter(i -> id.equals(i.path("runId").asText()))
                .sorted(Comparator.comparingInt(i -> i.path("ordinal").asInt())).toList();
    }

    static String describe(JsonNode i) {
        return switch (i.path("type").asText()) {
            case "command_execution" -> {
                String cmd = i.path("input").asText("").strip().lines().findFirst().orElse("");
                String result = i.has("exitCode") ? (i.path("exitCode").asInt() == 0 ? " ✓" : " ✗ exit " + i.path("exitCode").asInt())
                        : i.path("outputIndicatesFailure").asBoolean(false) ? " ✗" : "";
                yield "$ " + clip(cmd, 90) + result;
            }
            case "file_change" -> {
                String delta = i.has("additions") || i.has("deletions")
                        ? " (+" + i.path("additions").asInt(0) + " −" + i.path("deletions").asInt(0) + ")" : "";
                yield "edited " + i.path("fileName").asText() + delta;
            }
            case "file_search" -> "searched files" + (i.hasNonNull("pattern") ? " for " + clip(i.path("pattern").asText(), 60) : "");
            case "web_search" -> "searched the web" + (i.path("patterns").size() > 0 ? ": " + clip(i.path("patterns").get(0).asText(), 60) : "");
            case "dynamic_tool" -> "used " + (i.hasNonNull("toolName") ? i.path("toolName").asText() : "a tool");
            case "approval_request" -> "asked for approval: " + clip(firstNonBlank(i.path("prompt").asText(null),
                    i.path("requestKind").asText("action")), 90);
            case "subagent" -> "started a subagent";
            case "error" -> "error: " + clip(i.path("failure").path("message").asText("unknown"), 120);
            default -> null; // messages, reasoning, checkpoints and bookkeeping aren't steps
        };
    }

    static String clip(String s, int max) {
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
