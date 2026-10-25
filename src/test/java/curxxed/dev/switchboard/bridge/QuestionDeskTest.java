package curxxed.dev.switchboard.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import curxxed.dev.switchboard.app.RelayApp;
import curxxed.dev.switchboard.dispatch.Turn;
import curxxed.dev.switchboard.gateway.AgentDirectory;
import curxxed.dev.switchboard.gateway.ButtonPress;
import curxxed.dev.switchboard.gateway.MessageEnvelope;
import curxxed.dev.switchboard.telegram.FakeTelegram;
import curxxed.dev.switchboard.telegram.TelegramApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class QuestionDeskTest {

    static final long GROUP = -1001234567890L;
    static final AgentDirectory DIRECTORY = new AgentDirectory(List.of(
            new AgentDirectory.AgentBot("claude", "Claude", 101, "ClaudeRelayBot"),
            new AgentDirectory.AgentBot("codex", "Codex", 102, "CodexRelayBot"),
            new AgentDirectory.AgentBot("gemini", "Gemini", 103, "GeminiRelayBot")));

    record Answer(String threadId, String requestId, Map<String, Object> answers) {}

    private final List<Answer> submitted = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile Exception failNext;
    private FakeTelegram telegram;
    private ExecutorService executor;
    private TelegramApi claude;
    private QuestionDesk desk;
    private final Turn turn = new Turn("c", "claude", 1, GROUP, 42, "set up the db", "Alex", null);

    @BeforeEach
    void setUp() throws Exception {
        telegram = new FakeTelegram();
        telegram.addBot(new FakeTelegram.Bot("101:claude", 101, "ClaudeRelayBot", true));
        executor = Executors.newVirtualThreadPerTaskExecutor();
        claude = new TelegramApi(telegram.apiBase(), "101:claude", RelayApp.newHttpClient(executor), mapper);
        desk = new QuestionDesk(Map.of("claude", claude), (thread, request, answers) -> {
            Exception f = failNext;
            failNext = null;
            if (f != null) throw f;
            submitted.add(new Answer(thread, request, answers));
        }, DIRECTORY);
    }

    @AfterEach
    void tearDown() {
        executor.close();
        telegram.close();
    }

    private static RunTracker.QuestionOption opt(String label, String value) {
        return new RunTracker.QuestionOption(label, label + " description", value);
    }

    private static RunTracker.Question single(String id) {
        return new RunTracker.Question(id, "Database", "Which database should I use?",
                List.of(opt("Postgres", "pg"), opt("SQLite", null)), false, true);
    }

    private List<JsonNode> posts() {
        return telegram.sent("sendMessage").stream().map(FakeTelegram.Sent::body).toList();
    }

    private String lastEdit() {
        List<FakeTelegram.Sent> edits = telegram.sent("editMessageText");
        return edits.get(edits.size() - 1).body().path("text").asText();
    }

    private ButtonPress tap(JsonNode message, int row, boolean admin) {
        String data = message.path("reply_markup").path("inline_keyboard").get(row).get(0).path("callback_data").asText();
        return new ButtonPress("q" + System.nanoTime(), admin ? 555 : 777, admin ? "Alex" : "Guest", admin, GROUP, 1, data);
    }

    /** The id the fake Telegram assigned to the n-th sendMessage (they count up from 9001). */
    private long messageIdOfPost(int n) {
        return 9001 + n;
    }

    private MessageEnvelope reply(long toMessageId, String text, boolean admin) {
        return new MessageEnvelope(GROUP, 500, Instant.now(),
                new MessageEnvelope.Sender(admin ? 555 : 777, admin ? "Alex" : "Guest", null, false, admin, null), text,
                Set.of(), new MessageEnvelope.ReplyRef(toMessageId, 101L, "claude"), null, 0, "tg:x", "claude");
    }

    @Test
    void singleChoiceIsSubmittedWithTheOptionValue() throws Exception {
        desk.sync(turn, "t1", List.of(new RunTracker.QuestionRequest("r1", List.of(single("db")))));

        JsonNode q = posts().get(0);
        assertTrue(q.path("text").asText().startsWith("Claude asks: Database\nWhich database should I use?"), q.toString());
        assertTrue(q.path("text").asText().endsWith("Tap an option, or reply to this message with your own answer."));
        assertEquals(42, q.path("reply_parameters").path("message_id").asLong());

        desk.press(tap(q, 1, false), claude);
        assertTrue(submitted.isEmpty(), "a non-admin tap does nothing");

        desk.press(tap(q, 0, true), claude);
        assertEquals(List.of(new Answer("t1", "r1", Map.of("db", "pg"))), submitted, "the option's value, not its label");
        assertTrue(lastEdit().endsWith("Answered by Alex: Postgres"), lastEdit());
        assertEquals(0, desk.open());
    }

    @Test
    void anOptionWithoutAValueIsAnsweredWithItsLabel() throws Exception {
        desk.sync(turn, "t1", List.of(new RunTracker.QuestionRequest("r1", List.of(single("db")))));
        desk.press(tap(posts().get(0), 1, true), claude);
        assertEquals(Map.of("db", "SQLite"), submitted.get(0).answers());
    }

    @Test
    void multiSelectTogglesThenSubmitsAList() throws Exception {
        RunTracker.Question q = new RunTracker.Question("langs", "Languages", "Which should I support?",
                List.of(opt("Java", "java"), opt("Kotlin", "kt"), opt("Scala", "scala")), true, false);
        desk.sync(turn, "t1", List.of(new RunTracker.QuestionRequest("r1", List.of(q))));
        JsonNode msg = posts().get(0);
        assertEquals("Done", msg.path("reply_markup").path("inline_keyboard").get(3).get(0).path("text").asText());

        desk.press(tap(msg, 3, true), claude); // Done with nothing chosen
        assertTrue(submitted.isEmpty());
        desk.press(tap(msg, 0, true), claude);
        desk.press(tap(msg, 2, true), claude);
        desk.press(tap(msg, 0, true), claude); // untick Java
        desk.press(tap(msg, 1, true), claude);
        JsonNode redrawn = telegram.sent("editMessageText").get(telegram.sent("editMessageText").size() - 1).body();
        assertEquals("✓ Kotlin", redrawn.path("reply_markup").path("inline_keyboard").get(1).get(0).path("text").asText());
        assertEquals("Java", redrawn.path("reply_markup").path("inline_keyboard").get(0).get(0).path("text").asText());

        desk.press(tap(msg, 3, true), claude);
        assertEquals(Map.of("langs", List.of("scala", "kt")), submitted.get(0).answers());
        assertTrue(lastEdit().endsWith("Answered by Alex: Scala, Kotlin"), lastEdit());
    }

    @Test
    void severalQuestionsAreSubmittedTogetherAndRepliesCountAsAnswers() throws Exception {
        RunTracker.Question name = new RunTracker.Question("name", "Name", "What should the service be called?",
                List.of(), false, true);
        desk.sync(turn, "t1", List.of(new RunTracker.QuestionRequest("r1", List.of(single("db"), name))));
        assertEquals(2, posts().size());
        assertTrue(posts().get(0).path("text").asText().startsWith("Claude asks (1/2): "));
        assertTrue(posts().get(1).path("text").asText().endsWith("Reply to this message with your answer."));
        assertFalse(posts().get(1).has("reply_markup"), "no options, no buttons");

        desk.press(tap(posts().get(0), 0, true), claude);
        assertTrue(submitted.isEmpty(), "waits for the second question");
        assertTrue(lastEdit().endsWith("Your answer: Postgres. Waiting for the other questions."), lastEdit());

        assertFalse(desk.consumeReply(reply(messageIdOfPost(1), "ledger", false)), "a guest's reply isn't an answer");
        assertTrue(desk.consumeReply(reply(messageIdOfPost(1), "  ledger-svc ", true)));
        assertEquals(Map.of("db", "pg", "name", "ledger-svc"), submitted.get(0).answers());
        assertFalse(desk.consumeReply(reply(messageIdOfPost(1), "again", true)), "answered questions don't swallow replies");
    }

    @Test
    void customAnswersAreRefusedWhenNotAllowed() throws Exception {
        RunTracker.Question strict = new RunTracker.Question("env", "Env", "Which environment?",
                List.of(opt("staging", null), opt("prod", null)), false, false);
        desk.sync(turn, "t1", List.of(new RunTracker.QuestionRequest("r1", List.of(strict))));
        assertFalse(posts().get(0).path("text").asText().contains("reply to this message"));

        assertTrue(desk.consumeReply(reply(messageIdOfPost(0), "dev", true)), "consumed, so it doesn't start a turn");
        assertTrue(submitted.isEmpty());
        assertEquals("That question needs one of its listed options; tap a button instead.",
                posts().get(posts().size() - 1).path("text").asText());
    }

    @Test
    void aRejectedAnswerCanBeGivenAgain() throws Exception {
        desk.sync(turn, "t1", List.of(new RunTracker.QuestionRequest("r1", List.of(single("db")))));
        failNext = new IOException("request expired");
        desk.press(tap(posts().get(0), 0, true), claude);
        assertTrue(posts().get(posts().size() - 1).path("text").asText().startsWith("T3 didn't accept the answer: request expired"));
        assertEquals(1, desk.open());
        desk.press(tap(posts().get(0), 1, true), claude);
        assertEquals(Map.of("db", "SQLite"), submitted.get(0).answers());
    }

    @Test
    void questionsSettledElsewhereOrLeftOpenAreClosed() {
        desk.sync(turn, "t1", List.of(new RunTracker.QuestionRequest("r1", List.of(single("db")))));
        desk.sync(turn, "t1", List.of());
        assertTrue(lastEdit().endsWith("Answered in T3 Code."), lastEdit());
        assertFalse(telegram.sent("editMessageText").get(0).body().has("reply_markup"));

        desk.sync(turn, "t1", List.of(new RunTracker.QuestionRequest("r2", List.of(single("db")))));
        desk.close("t1", "the run was stopped");
        assertTrue(lastEdit().endsWith("No longer needed: the run was stopped."), lastEdit());
        assertEquals(0, desk.open());
    }

    @Test
    void trackerParsesUserInputRequests() throws Exception {
        RunTracker t = new RunTracker("m1");
        t.accept(mapper.readTree("""
                {"kind": "event", "event": {"type": "run.updated", "payload": {"id": "r1", "userMessageId": "m1", "status": "waiting"}}}"""));
        t.accept(mapper.readTree("""
                {"kind": "event", "event": {"type": "turn-item.updated", "payload": {
                  "id": "i1", "runId": "r1", "ordinal": 1, "status": "pending", "type": "user_input_request",
                  "requestId": "req-9", "questions": [
                    {"id": "db", "header": "Database", "question": "Which?", "multiSelect": true, "allowCustomAnswer": false,
                     "options": [{"label": "Postgres", "description": "relational", "value": "pg"},
                                 {"label": "SQLite", "description": "embedded"}]}]}}}"""));
        RunTracker.QuestionRequest q = t.pendingQuestions().get(0);
        assertEquals("req-9", q.requestId());
        RunTracker.Question first = q.questions().get(0);
        assertTrue(first.multiSelect());
        assertFalse(first.allowCustom());
        assertEquals("pg", first.options().get(0).answerValue());
        assertEquals("SQLite", first.options().get(1).answerValue());
        assertTrue(T3AgentRunner.render("Claude", t, Duration.ofSeconds(3)).contains("Waiting for your answer"));
    }
}
