package curxxed.dev.switchboard.bridge;

import curxxed.dev.switchboard.config.AppConfig;
import curxxed.dev.switchboard.dispatch.Turn;
import curxxed.dev.switchboard.store.MessageLedger;
import curxxed.dev.switchboard.store.MessageLedger.SenderKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PromptBuilderTest {

    private final PromptBuilder builder = new PromptBuilder(new AppConfig(
            null, null,
            Map.of("claude", new AppConfig.Agent("1:a", "claudeAgent", "claude-opus-5-5"),
                   "codex", new AppConfig.Agent("2:b", "codex", "gpt-6.1-sol"),
                   "gemini", new AppConfig.Agent("3:c", "antigravity", "gemini-3.8-flash-high")),
            null));

    private static MessageLedger.Entry entry(long id, SenderKind kind, String name, String text) {
        return new MessageLedger.Entry(-100L, id, kind, 1L, name, kind == SenderKind.AGENT ? "codex" : null, null, text, Instant.EPOCH);
    }

    @Test
    void firstTurnIntroducesTheGroupAndLabelsSpeakers() {
        Turn turn = new Turn("c", "claude", 1, -100L, 12, "@Claude refactor the parser", "Alex", null);
        String p = builder.build(turn, List.of(
                entry(10, SenderKind.HUMAN, "Alex", "we need a faster parser"),
                entry(11, SenderKind.AGENT, "Codex", "I can write benchmarks"),
                entry(11, SenderKind.SYSTEM, "relay", "Stopped this chain: example."),
                entry(12, SenderKind.HUMAN, "Alex", "@Claude refactor the parser")),
                new PromptBuilder.Context("Scratch", "You share the folder /tmp/x.", false, 4, true));

        assertTrue(p.startsWith("You are Claude, one of 3 AI coding agents"), p);
        assertTrue(p.contains("Project: Scratch. You share the folder /tmp/x."));
        assertTrue(p.contains("[human] Alex: we need a faster parser"));
        assertTrue(p.contains("[agent] Codex: I can write benchmarks"));
        assertTrue(p.contains("[relay] relay: Stopped this chain"));
        assertTrue(p.contains("Hand-offs are off"));
        assertTrue(p.endsWith("Request from Alex (the admin):\n@Claude refactor the parser\n"), p);
        assertEquals(1, p.split("refactor the parser", -1).length - 1, "the request appears once, not again in the history");
    }

    @Test
    void laterTurnsSkipTheIntroAndShowRemainingHops() {
        Turn turn = new Turn("c", "gemini", 3, -100L, 30, "Tests pass. @Gemini review please", "Codex", "codex");
        String p = builder.build(turn, List.of(), new PromptBuilder.Context("Ridgeline", "ws", true, 2, false));
        assertFalse(p.contains("You are Gemini"));
        assertTrue(p.startsWith("Hand-offs are live: mentioning another agent starts its turn. This chain allows 2 more hand-off(s)."), p);
        assertTrue(p.contains("Codex handed this to you:\nTests pass."));
        assertFalse(p.contains("Recent group conversation"));
    }

    @Test
    void noHopsLeftTellsTheAgentToFinish() {
        Turn turn = new Turn("c", "claude", 5, -100L, 30, "over to you", "Codex", "codex");
        String p = builder.build(turn, List.of(), new PromptBuilder.Context("X", "ws", true, 0, false));
        assertTrue(p.contains("no hand-offs left, so finish the work yourself"));
    }

    @Test
    void longHistoryLinesAreClipped() {
        Turn turn = new Turn("c", "claude", 1, -100L, 99, "go", "Alex", null);
        String p = builder.build(turn, List.of(entry(1, SenderKind.HUMAN, "Alex", "z".repeat(5000))),
                new PromptBuilder.Context("X", "ws", false, 0, false));
        assertTrue(p.length() < 1500, "length " + p.length());
    }

    @Test
    void detectsGitRepositoriesAndTheirBranch(@TempDir Path dir) throws Exception {
        assertNull(ProjectResolver.gitBranch(dir), "a plain folder");
        assertNull(ProjectResolver.gitBranch(dir.resolve("missing")));
        run(dir, "git", "init", "-q", "-b", "trunk");
        assertNull(ProjectResolver.gitBranch(dir), "no commits yet, so nothing to base a worktree on");
        run(dir, "git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "--allow-empty", "-m", "init");
        assertEquals("trunk", ProjectResolver.gitBranch(dir));
        Files.createDirectories(dir.resolve("sub"));
        assertEquals("trunk", ProjectResolver.gitBranch(dir.resolve("sub")));
    }

    private static void run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
    }
}
