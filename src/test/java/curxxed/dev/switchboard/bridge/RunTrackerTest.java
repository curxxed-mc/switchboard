package curxxed.dev.switchboard.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import curxxed.dev.switchboard.store.AgentStore;
import curxxed.dev.switchboard.t3.T3RpcClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RunTrackerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s.replace('\'', '"'));
    }

    private JsonNode event(String type, String payload) throws Exception {
        return json("{'kind': 'event', 'sequence': 1, 'event': {'type': '" + type + "', 'payload': " + payload + "}}");
    }

    private static String run(String id, String userMessageId, String status) {
        return "{'id': '" + id + "', 'threadId': 't', 'userMessageId': '" + userMessageId + "', 'status': '" + status + "'}";
    }

    private static String message(String id, String runId, String role, String text, String createdAt) {
        return "{'id': '" + id + "', 'runId': '" + runId + "', 'role': '" + role + "', 'text': '" + text
                + "', 'streaming': false, 'createdAt': '" + createdAt + "'}";
    }

    @Test
    void followsOnlyTheRunStartedByOurMessage() throws Exception {
        RunTracker t = new RunTracker("m-ours");
        t.accept(json("""
                {'kind': 'snapshot', 'snapshotSequence': 5, 'projection': {
                  'runs': [%s, %s],
                  'messages': [%s],
                  'turnItems': []}}""".formatted(run("r-old", "m-old", "completed"), run("r1", "m-ours", "queued"),
                message("x", "r-old", "assistant", "old answer", "2026-10-25T10:00:00Z"))));
        assertEquals("r1", t.runId().orElseThrow());
        assertEquals("queued", t.status().orElseThrow());
        assertFalse(t.finished());
        assertEquals("", t.finalReply(), "another run's reply must not leak in");
    }

    @Test
    void eventsAdvanceTheRunAndTheLastAssistantMessageIsTheReply() throws Exception {
        RunTracker t = new RunTracker("m1");
        assertFalse(t.accept(json("{'kind': 'synchronized'}")));
        assertTrue(t.accept(event("run.created", run("r1", "m1", "starting"))));
        t.accept(event("run.updated", run("r1", "m1", "running")));
        t.accept(event("message.updated", message("a1", "r1", "assistant", "Let me look at the tests.", "2026-10-25T12:00:01Z")));
        t.accept(event("turn-item.updated", """
                {'id': 'i1', 'runId': 'r1', 'ordinal': 1, 'status': 'completed', 'type': 'command_execution',
                 'input': './gradlew test\\n--info', 'exitCode': 0}"""));
        t.accept(event("turn-item.updated", """
                {'id': 'i2', 'runId': 'r1', 'ordinal': 2, 'status': 'completed', 'type': 'file_change',
                 'fileName': 'src/Parser.java', 'additions': 12, 'deletions': 3}"""));
        t.accept(event("turn-item.updated", """
                {'id': 'i3', 'runId': 'r1', 'ordinal': 3, 'status': 'completed', 'type': 'dynamic_tool',
                 'toolName': 'blender.get_scene_info', 'input': {}}"""));
        t.accept(event("message.updated", message("a2", "r1", "assistant", "Fixed the parser; all 41 tests pass.", "2026-10-25T12:00:09Z")));
        t.accept(event("message.updated", message("u1", "r1", "user", "the prompt", "2026-10-25T12:00:00Z")));
        assertFalse(t.finished());
        t.accept(event("run.updated", run("r1", "m1", "completed")));

        assertTrue(t.finished());
        assertEquals("Fixed the parser; all 41 tests pass.", t.finalReply());
        assertEquals(List.of("$ ./gradlew test ✓", "edited src/Parser.java (+12 −3)", "used blender.get_scene_info"),
                t.activities().stream().map(RunTracker.Activity::text).toList());
    }

    @Test
    void itemsArrivingBeforeTheRunAreKept() throws Exception {
        RunTracker t = new RunTracker("m1");
        t.accept(event("message.updated", message("a1", "r1", "assistant", "early", "2026-10-25T12:00:01Z")));
        assertEquals("", t.finalReply());
        t.accept(event("run.updated", run("r1", "m1", "completed")));
        assertEquals("early", t.finalReply());
    }

    @Test
    void reportsApprovalWaits() throws Exception {
        RunTracker t = new RunTracker("m1");
        t.accept(event("run.updated", run("r1", "m1", "waiting")));
        t.accept(event("turn-item.updated", """
                {'id': 'i1', 'runId': 'r1', 'ordinal': 1, 'status': 'pending', 'type': 'approval_request',
                 'requestId': 'q', 'requestKind': 'command', 'prompt': 'rm -rf build'}"""));
        assertEquals("approval for rm -rf build", t.waitingOn().orElseThrow());
        String shown = T3AgentRunner.render("Codex", t, Duration.ofSeconds(75));
        assertTrue(shown.startsWith("Codex is waiting · 1m 15s"), shown);
        assertTrue(shown.contains("Waiting for your approval (buttons below)."), shown);
        RunTracker.Approval a = t.pendingApprovals().get(0);
        assertEquals("q", a.requestId());
        assertEquals("command", a.kind());
        assertTrue(a.options().isEmpty(), "none offered, so the desk uses its defaults");
    }

    @Test
    void failuresCarryTheProviderMessage() throws Exception {
        RunTracker t = new RunTracker("m1");
        t.accept(event("run.updated", run("r1", "m1", "failed")));
        t.accept(event("turn-item.updated", """
                {'id': 'e', 'runId': 'r1', 'ordinal': 4, 'status': 'failed', 'type': 'error',
                 'failure': {'class': 'usage_limit', 'message': 'Usage limit reached', 'code': null, 'retryable': true}}"""));
        assertTrue(t.finished());
        assertEquals("Usage limit reached", t.failure().orElseThrow());
    }

    @Test
    void progressShowsTheLatestStepsOnly() throws Exception {
        RunTracker t = new RunTracker("m1");
        t.accept(event("run.updated", run("r1", "m1", "running")));
        for (int i = 1; i <= 9; i++) {
            t.accept(event("turn-item.updated", """
                    {'id': 'i%d', 'runId': 'r1', 'ordinal': %d, 'status': 'completed', 'type': 'file_change', 'fileName': 'f%d'}"""
                    .formatted(i, i, i)));
        }
        String shown = T3AgentRunner.render("Gemini", t, Duration.ofSeconds(5));
        assertTrue(shown.contains("(3 earlier steps)"), shown);
        assertTrue(shown.contains("• edited f9") && !shown.contains("• edited f3\n"), shown);
    }

    @Test
    void longCommandsAreClippedAndFailuresMarked() throws Exception {
        JsonNode item = json("{'type': 'command_execution', 'input': '" + "x".repeat(200) + "', 'exitCode': 2}");
        String text = RunTracker.describe(item);
        assertTrue(text.endsWith("✗ exit 2"));
        assertTrue(text.length() < 110, text);
        assertNull(RunTracker.describe(json("{'type': 'reasoning', 'text': 'hmm'}")));
    }

    @Test
    void formatsDurations() {
        assertEquals("9s", T3AgentRunner.format(Duration.ofSeconds(9)));
        assertEquals("2m 05s", T3AgentRunner.format(Duration.ofSeconds(125)));
    }

    @Test
    void workspaceTextReflectsWhatT3SetUp() {
        var scratch = new ProjectResolver.Project("p", "No project", "/home/u/.t3/scratch", null);
        var own = new AgentStore.AgentThread("claude", "p", "t", "/home/u/.t3/scratch/2026-10-25-x", null);
        assertEquals("You work in your own folder /home/u/.t3/scratch/2026-10-25-x; the other agents have their own.",
                T3AgentRunner.workspaceText(scratch, Optional.of(own)), "seen live: T3 gives scratch threads private folders");

        var shared = new AgentStore.AgentThread("claude", "p", "t", "/home/u/.t3/scratch", null);
        assertTrue(T3AgentRunner.workspaceText(scratch, Optional.of(shared)).startsWith("You share the folder"));

        var repo = new ProjectResolver.Project("r", "Ridgeline", "/src/ridgeline", "main");
        var wt = new AgentStore.AgentThread("codex", "r", "t", "/wt/codex", "relay-codex");
        assertEquals("You work in your own folder /wt/codex, a git worktree on branch relay-codex; the other agents have their own.",
                T3AgentRunner.workspaceText(repo, Optional.of(wt)));
        assertTrue(T3AgentRunner.workspaceText(repo, Optional.empty()).contains("own git worktree of /src/ridgeline (based on main)"));
    }

    @Test
    void recognisesDeletedThreads() {
        var gone = new T3RpcClient.T3RpcException("orchestration.subscribeThread", "OrchestrationV2GetThreadProjectionError",
                "Thread abc not found");
        assertTrue(T3AgentRunner.threadGone(gone));
    }
}
