package curxxed.dev.switchboard.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import curxxed.dev.switchboard.app.RelayApp;
import curxxed.dev.switchboard.dispatch.Turn;
import curxxed.dev.switchboard.gateway.AgentDirectory;
import curxxed.dev.switchboard.gateway.ButtonPress;
import curxxed.dev.switchboard.telegram.FakeTelegram;
import curxxed.dev.switchboard.telegram.TelegramApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalDeskTest {

    static final long GROUP = -1001234567890L;
    static final AgentDirectory DIRECTORY = new AgentDirectory(List.of(
            new AgentDirectory.AgentBot("claude", "Claude", 101, "ClaudeRelayBot"),
            new AgentDirectory.AgentBot("codex", "Codex", 102, "CodexRelayBot"),
            new AgentDirectory.AgentBot("gemini", "Gemini", 103, "GeminiRelayBot")));

    record Decision(String threadId, String requestId, String decision) {}

    private final List<Decision> decisions = new CopyOnWriteArrayList<>();
    private volatile Exception failNext;
    private FakeTelegram telegram;
    private ExecutorService executor;
    private TelegramApi codex;
    private ApprovalDesk desk;
    private final Turn turn = new Turn("c", "codex", 1, GROUP, 42, "clean up", "Alex", null);

    @BeforeEach
    void setUp() throws Exception {
        telegram = new FakeTelegram();
        telegram.addBot(new FakeTelegram.Bot("102:codex", 102, "CodexRelayBot", true));
        executor = Executors.newVirtualThreadPerTaskExecutor();
        codex = new TelegramApi(telegram.apiBase(), "102:codex", RelayApp.newHttpClient(executor), new ObjectMapper());
        desk = new ApprovalDesk(Map.of("codex", codex), (thread, request, decision) -> {
            Exception f = failNext;
            failNext = null;
            if (f != null) throw f;
            decisions.add(new Decision(thread, request, decision));
        }, DIRECTORY);
    }

    @AfterEach
    void tearDown() {
        executor.close();
        telegram.close();
    }

    private static RunTracker.Approval command(String requestId, List<RunTracker.ApprovalOption> options) {
        return new RunTracker.Approval(requestId, "command", "rm -rf build", "clean the build output", options);
    }

    private JsonNode posted() {
        return telegram.sent("sendMessage").get(telegram.sent("sendMessage").size() - 1).body();
    }

    private ButtonPress tap(JsonNode message, int button, boolean admin) {
        String data = message.path("reply_markup").path("inline_keyboard").get(button).get(0).path("callback_data").asText();
        return new ButtonPress("q" + System.nanoTime(), admin ? 555 : 777, admin ? "Alex" : "Guest", admin, GROUP, 9001, data);
    }

    private String lastAnswer() {
        List<FakeTelegram.Sent> answers = telegram.sent("answerCallbackQuery");
        return answers.get(answers.size() - 1).body().path("text").asText();
    }

    private String lastEdit() {
        List<FakeTelegram.Sent> edits = telegram.sent("editMessageText");
        return edits.get(edits.size() - 1).body().path("text").asText();
    }

    @Test
    void postsTheRequestWithDefaultButtonsUnderTheTrigger() {
        desk.sync(turn, "t1", List.of(command("r1", List.of())));

        JsonNode msg = posted();
        assertEquals("Codex wants approval to run a command:\nrm -rf build\n\nWhy: clean the build output", msg.path("text").asText());
        assertEquals(42, msg.path("reply_parameters").path("message_id").asLong());
        JsonNode rows = msg.path("reply_markup").path("inline_keyboard");
        assertEquals(List.of("Approve", "Approve for this session", "Deny"),
                List.of(rows.get(0).get(0).path("text").asText(), rows.get(1).get(0).path("text").asText(),
                        rows.get(2).get(0).path("text").asText()));
        assertTrue(rows.get(0).get(0).path("callback_data").asText().length() <= 64, "Telegram's callback data limit");

        desk.sync(turn, "t1", List.of(command("r1", List.of())));
        assertEquals(1, telegram.sent("sendMessage").size(), "an open request is announced once");
    }

    @Test
    void usesT3sOwnOptionsAndShowsTheirWarnings() {
        desk.sync(turn, "t1", List.of(command("r1", List.of(
                new RunTracker.ApprovalOption("accept", "Yes, run it", null),
                new RunTracker.ApprovalOption("acceptAlways", "Always allow rm", "Applies to every future rm command"),
                new RunTracker.ApprovalOption("decline", "No", null)))));
        JsonNode msg = posted();
        assertTrue(msg.path("text").asText().endsWith("\n\n• Always allow rm: Applies to every future rm command"), msg.toString());
        assertEquals("Always allow rm", msg.path("reply_markup").path("inline_keyboard").get(1).get(0).path("text").asText());
    }

    @Test
    void onlyTheAdminsTapCounts() throws Exception {
        desk.sync(turn, "t1", List.of(command("r1", List.of())));
        JsonNode msg = posted();

        desk.press(tap(msg, 0, false), codex);
        assertEquals("Only the relay administrator can approve.", lastAnswer());
        assertTrue(telegram.sent("answerCallbackQuery").get(0).body().path("show_alert").asBoolean());
        assertTrue(decisions.isEmpty());

        desk.press(tap(msg, 1, true), codex);
        assertEquals(List.of(new Decision("t1", "r1", "acceptForSession")), decisions);
        assertEquals("Approved", lastAnswer());
        String edited = lastEdit();
        assertTrue(edited.startsWith("Codex wanted approval to run a command:"), edited);
        assertTrue(edited.endsWith("Approved by Alex (Approve for this session)"), edited);
        assertFalse(telegram.sent("editMessageText").get(0).body().has("reply_markup"), "the buttons are removed");
        assertEquals(0, desk.open());

        desk.press(tap(msg, 0, true), codex);
        assertEquals("This approval is no longer open.", lastAnswer());
        assertEquals(1, decisions.size(), "a second tap can't send a second decision");
    }

    @Test
    void denyIsRecordedAsADenial() throws Exception {
        desk.sync(turn, "t1", List.of(command("r1", List.of())));
        desk.press(tap(posted(), 2, true), codex);
        assertEquals("decline", decisions.get(0).decision());
        assertEquals("Denied", lastAnswer());
        assertTrue(lastEdit().endsWith("Denied by Alex (Deny)"));
    }

    @Test
    void aRefusedDecisionCanBeRetried() throws Exception {
        desk.sync(turn, "t1", List.of(command("r1", List.of())));
        JsonNode msg = posted();
        failNext = new IOException("request already resolved");
        desk.press(tap(msg, 0, true), codex);
        assertTrue(lastAnswer().startsWith("T3 didn't accept that: request already resolved"), lastAnswer());
        assertEquals(1, desk.open());
        desk.press(tap(msg, 0, true), codex);
        assertEquals(1, decisions.size());
    }

    @Test
    void requestsSettledElsewhereLoseTheirButtons() {
        desk.sync(turn, "t1", List.of(command("r1", List.of()), command("r2", List.of())));
        desk.sync(turn, "t1", List.of(command("r2", List.of()))); // r1 approved in the T3 app
        assertTrue(lastEdit().endsWith("\n\nSettled in T3 Code."), lastEdit());
        assertEquals(1, desk.open());

        desk.sync(new Turn("c2", "codex", 1, GROUP, 50, "x", "Alex", null), "t-other", List.of());
        assertEquals(1, desk.open(), "another thread's sync doesn't touch this thread's requests");

        desk.close("t1", "the run was stopped");
        assertTrue(lastEdit().endsWith("No longer needed: the run was stopped."), lastEdit());
        assertEquals(0, desk.open());
    }

    @Test
    void unknownButtonsJustStopTheSpinner() throws Exception {
        desk.press(new ButtonPress("q1", 555, "Alex", true, GROUP, 1, "something-else"), codex);
        desk.press(new ButtonPress("q2", 555, "Alex", true, GROUP, 1, "ap:deadbeef:0"), codex);
        assertEquals("This approval is no longer open.", lastAnswer());
        assertTrue(decisions.isEmpty());
    }

    @Test
    void describesEachKind() {
        RunTracker.Approval files = new RunTracker.Approval("r", "file-change", "src/Main.java", null, List.of());
        assertEquals("Gemini wants approval to change files:\nsrc/Main.java", desk.describe("gemini", files, false));
        RunTracker.Approval bare = new RunTracker.Approval("r", "permission", null, null, List.of());
        assertEquals("Claude wanted approval for a permission:", desk.describe("claude", bare, true));
    }
}
