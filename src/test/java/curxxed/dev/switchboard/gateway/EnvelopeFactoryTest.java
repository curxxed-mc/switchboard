package curxxed.dev.switchboard.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EnvelopeFactoryTest {

    static final long GROUP = -1001234567890L;
    static final long ADMIN = 555;

    private final ObjectMapper mapper = new ObjectMapper();
    private final EnvelopeFactory factory = new EnvelopeFactory(MentionParserTest.DIRECTORY, ADMIN);

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s.replace('\'', '"'));
    }

    @Test
    void adminMessageWithMentions() throws Exception {
        MessageEnvelope e = factory.fromMessage(json("""
                {'message_id': 10, 'date': 1792929600, 'chat': {'id': %d, 'type': 'supergroup'},
                 'from': {'id': 555, 'is_bot': false, 'first_name': 'Alex', 'username': 'relay_admin'},
                 'text': '@Codex write tests, @Gemini review'}""".formatted(GROUP)), "claude");

        assertEquals(10, e.messageId());
        assertTrue(e.sender().isAdmin());
        assertTrue(e.sender().isHuman());
        assertEquals("Alex", e.sender().displayName());
        assertEquals(List.of("codex", "gemini"), List.copyOf(e.targetAgents()));
        assertNull(e.command());
        assertEquals(0, e.depth());
        assertEquals("tg:" + GROUP + ":10", e.chainId());
        assertEquals("claude", e.receivedBy());
    }

    @Test
    void replyToAnAgentBotTargetsThatAgent() throws Exception {
        MessageEnvelope e = factory.fromMessage(json("""
                {'message_id': 11, 'date': 1, 'chat': {'id': %d},
                 'from': {'id': 777, 'is_bot': false, 'first_name': 'Guest'},
                 'reply_to_message': {'message_id': 9, 'from': {'id': 102, 'is_bot': true, 'first_name': 'Codex'}},
                 'text': 'why did that fail?'}""".formatted(GROUP)), "gemini");

        assertFalse(e.sender().isAdmin());
        assertEquals(9, e.replyTo().messageId());
        assertEquals("codex", e.replyTo().agentKey());
        assertEquals(Set.of("codex"), e.targetAgents());
    }

    @Test
    void forumTopicRootIsNotAReply() throws Exception {
        MessageEnvelope e = factory.fromMessage(json("""
                {'message_id': 50, 'date': 1, 'chat': {'id': %d}, 'from': {'id': 555, 'first_name': 'A'},
                 'is_topic_message': true, 'message_thread_id': 3,
                 'reply_to_message': {'message_id': 3, 'from': {'id': 101, 'is_bot': true}},
                 'text': 'hello'}""".formatted(GROUP)), "claude");
        assertNull(e.replyTo());
        assertTrue(e.targetAgents().isEmpty());
    }

    @Test
    void anonymousAdminIsNeverTheAdmin() throws Exception {
        MessageEnvelope e = factory.fromMessage(json("""
                {'message_id': 12, 'date': 1, 'chat': {'id': %d},
                 'from': {'id': 1087968824, 'is_bot': true, 'first_name': 'Group'},
                 'sender_chat': {'id': %d, 'title': 'Relay HQ'}, 'text': '/stopall'}""".formatted(GROUP, GROUP)), "codex");
        assertFalse(e.sender().isAdmin());
        assertEquals("Relay HQ", e.sender().displayName());
        assertEquals("stopall", e.command().name());
    }

    @Test
    void commandsAreParsedFromTextOnly() throws Exception {
        MessageEnvelope captioned = factory.fromMessage(json("""
                {'message_id': 13, 'date': 1, 'chat': {'id': %d}, 'from': {'id': 555, 'first_name': 'A'},
                 'photo': [{}], 'caption': '/mode autonomous @Claude'}""".formatted(GROUP)), "claude");
        assertNull(captioned.command(), "a captioned photo is not a command");
        assertEquals("/mode autonomous @Claude", captioned.text());
        assertEquals(Set.of("claude"), captioned.mentionedAgents());

        MessageEnvelope sticker = factory.fromMessage(json("""
                {'message_id': 14, 'date': 1, 'chat': {'id': %d}, 'from': {'id': 555, 'first_name': 'A'},
                 'sticker': {}}""".formatted(GROUP)), "claude");
        assertEquals("[sticker]", sticker.text());
    }

    @Test
    void commandParsing() {
        Command c = Command.parse("/model codex gpt-6-astra").orElseThrow();
        assertEquals("model", c.name());
        assertEquals("codex gpt-6-astra", c.args());
        assertNull(c.addressedTo());
        assertTrue(c.adminOnly());

        Command addressed = Command.parse("  /StopAll@ClaudeRelayBot  ").orElseThrow();
        assertEquals("stopall", addressed.name());
        assertEquals("ClaudeRelayBot", addressed.addressedTo());
        assertEquals("", addressed.args());

        Command multiline = Command.parse("/discuss 3\nplan the refactor").orElseThrow();
        assertEquals("3\nplan the refactor", multiline.args());

        assertFalse(Command.parse("/agents").orElseThrow().adminOnly());
        assertTrue(Command.parse("not /a command").isEmpty());
        assertTrue(Command.parse("/").isEmpty());
        assertTrue(Command.parse("// comment").isEmpty());
    }
}
