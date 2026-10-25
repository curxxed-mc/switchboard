package curxxed.dev.switchboard.gateway;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** Builds a {@link MessageEnvelope} from a Telegram {@code message} object. */
public final class EnvelopeFactory {

    /**
     * Telegram's placeholder sender for "remain anonymous" group admins. The real sender is
     * hidden, so such messages can never count as coming from the configured admin.
     */
    public static final long GROUP_ANONYMOUS_BOT = 1087968824L;

    private final AgentDirectory directory;
    private final MentionParser mentions;
    private final long adminUserId;

    public EnvelopeFactory(AgentDirectory directory, long adminUserId) {
        this.directory = directory;
        this.mentions = new MentionParser(directory);
        this.adminUserId = adminUserId;
    }

    public MessageEnvelope fromMessage(JsonNode message, String receivedBy) {
        long chatId = message.path("chat").path("id").asLong();
        long messageId = message.path("message_id").asLong();
        String text = contentOf(message);
        return new MessageEnvelope(
                chatId,
                messageId,
                Instant.ofEpochSecond(message.path("date").asLong()),
                senderOf(message),
                text,
                // Gateway envelopes are always from humans (Telegram never relays bot messages to bots).
                mentions.mentionedAgents(text, true),
                replyOf(message),
                Command.parse(message.path("text").asText(null)).orElse(null),
                0,
                MessageEnvelope.rootChainId(chatId, messageId),
                receivedBy);
    }

    private MessageEnvelope.Sender senderOf(JsonNode message) {
        JsonNode from = message.path("from");
        long userId = from.path("id").asLong();
        boolean anonymous = userId == GROUP_ANONYMOUS_BOT || message.has("sender_chat");
        String name = (from.path("first_name").asText("") + " " + from.path("last_name").asText("")).strip();
        if (anonymous) name = message.path("sender_chat").path("title").asText("Anonymous admin");
        String agentKey = directory.byUserId(userId).map(AgentDirectory.AgentBot::agentKey).orElse(null);
        return new MessageEnvelope.Sender(userId, name.isEmpty() ? "user " + userId : name,
                from.path("username").asText(null), from.path("is_bot").asBoolean(false),
                !anonymous && userId == adminUserId, agentKey);
    }

    private MessageEnvelope.ReplyRef replyOf(JsonNode message) {
        JsonNode reply = message.path("reply_to_message");
        if (reply.isMissingNode() || reply.isNull()) return null;
        // In forum groups every topic message "replies" to the topic's root; that isn't a real reply.
        if (message.path("is_topic_message").asBoolean(false)
                && reply.path("message_id").asLong() == message.path("message_thread_id").asLong(-1)) {
            return null;
        }
        Long senderId = reply.path("from").has("id") ? reply.path("from").path("id").asLong() : null;
        String agentKey = senderId == null ? null
                : directory.byUserId(senderId).map(AgentDirectory.AgentBot::agentKey).orElse(null);
        return new MessageEnvelope.ReplyRef(reply.path("message_id").asLong(), senderId, agentKey);
    }

    /** The message text or media caption. Other content becomes a short placeholder. */
    static String contentOf(JsonNode message) {
        if (message.hasNonNull("text")) return message.get("text").asText();
        if (message.hasNonNull("caption")) return message.get("caption").asText();
        for (String kind : new String[] {"photo", "document", "voice", "video", "sticker", "audio", "poll", "location"}) {
            if (message.has(kind)) return "[" + kind + "]";
        }
        return "[non-text message]";
    }
}
