package curxxed.dev.switchboard.gateway;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MentionParserTest {

    static final AgentDirectory DIRECTORY = new AgentDirectory(List.of(
            new AgentDirectory.AgentBot("claude", "Claude", 101, "ClaudeRelayBot"),
            new AgentDirectory.AgentBot("codex", "Codex", 102, "CodexRelayBot"),
            new AgentDirectory.AgentBot("gemini", "Gemini", 103, "GeminiRelayBot")));

    private final MentionParser parser = new MentionParser(DIRECTORY);

    private Set<String> parse(String text) {
        return parser.mentionedAgents(text);
    }

    @Test
    void shortAliasesInAnyCase() {
        assertEquals(Set.of("claude"), parse("@Claude please review"));
        assertEquals(Set.of("codex"), parse("hey @CODEX"));
        assertEquals(Set.of("gemini"), parse("@gemini?"));
    }

    @Test
    void realBotUsernames() {
        assertEquals(Set.of("codex"), parse("/ask @CodexRelayBot run the tests"));
        assertEquals(Set.of("claude"), parse("@clauderelaybot"));
    }

    @Test
    void preservesOrderOfFirstAppearanceAndDedupes() {
        assertEquals(List.of("gemini", "claude", "codex"),
                List.copyOf(parse("@Gemini draft it, @Claude review, @Codex test, then @Gemini again")));
    }

    @Test
    void punctuationAroundMentions() {
        assertEquals(Set.of("claude", "codex", "gemini"), parse("(@Claude), @Codex: and \"@Gemini\"."));
        assertEquals(Set.of("claude"), parse("@Claude's turn"));
    }

    @Test
    void ignoresEmailsAndLongerHandles() {
        assertTrue(parse("mail me at ops@claude.dev").isEmpty());
        assertTrue(parse("@Claudette and @Codexer are people").isEmpty());
        assertTrue(parse("@@Claude").isEmpty());
        assertTrue(parse("@Claude@Codex").isEmpty());
    }

    @Test
    void broadcastReachesEveryAgentOnlyWhenAllowed() {
        assertEquals(List.of("claude", "codex", "gemini"), List.copyOf(parser.mentionedAgents("@everyone ping", true)));
        assertEquals(List.of("gemini", "claude", "codex"),
                List.copyOf(parser.mentionedAgents("@Gemini lead, @ALL else help", true)), "explicit mentions keep their order");
        assertTrue(parse("@everyone ping").isEmpty(), "agent replies can't broadcast");
        assertTrue(parse("@all hands").isEmpty());
        assertTrue(parser.mentionedAgents("`@everyone` in code", true).isEmpty());
        assertTrue(parser.mentionedAgents("@allofyou @everyones", true).isEmpty());
    }

    @Test
    void ignoresUnknownNames() {
        assertTrue(parse("@alice @bob @GPT").isEmpty());
    }

    @Test
    void ignoresMentionsInsideCode() {
        assertTrue(parse("run `notify(@Codex)` later").isEmpty());
        assertTrue(parse("""
                Here is the log:
                ```
                @Gemini said hi
                @Codex too
                ```
                """).isEmpty());
        assertEquals(Set.of("claude"), parse("```\n@Codex\n```\nover to @Claude"));
    }

    @Test
    void unterminatedFenceHidesTheRest() {
        assertTrue(parse("```java\n// @Codex fix this").isEmpty());
    }

    @Test
    void nullAndEmptyText() {
        assertTrue(parse(null).isEmpty());
        assertTrue(parse("").isEmpty());
    }

    @Test
    void directoryRejectsAliasCollisionsAndSharedBots() {
        assertThrows(IllegalArgumentException.class, () -> new AgentDirectory(List.of(
                new AgentDirectory.AgentBot("claude", "Claude", 1, "codex"),
                new AgentDirectory.AgentBot("codex", "Codex", 2, "CodexRelayBot"))));
        assertThrows(IllegalArgumentException.class, () -> new AgentDirectory(List.of(
                new AgentDirectory.AgentBot("claude", "Claude", 7, "A_bot"),
                new AgentDirectory.AgentBot("codex", "Codex", 7, "B_bot"))));
    }
}
