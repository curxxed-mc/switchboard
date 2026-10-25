package curxxed.dev.switchboard.gateway;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A group message normalized for routing, independent of which bot received it.
 *
 * @param mentionedAgents agents addressed with {@code @name} in the text, in order of appearance
 * @param replyTo         the message this one replies to, or null
 * @param command         the parsed bot command, or null for ordinary messages
 * @param depth           0 for a human message; n for the nth agent turn in an autonomous cascade
 * @param chainId         shared by every message in one cascade, rooted at the human message
 * @param receivedBy      agent key of the bot whose copy was processed
 */
public record MessageEnvelope(long chatId, long messageId, Instant sentAt, Sender sender, String text,
                              Set<String> mentionedAgents, ReplyRef replyTo, Command command, int depth,
                              String chainId, String receivedBy) {

    /**
     * @param agentKey set when the sender is one of the relay's own agent bots
     * @param isAdmin  true only for the configured administrator, never for anonymous admins
     */
    public record Sender(long userId, String displayName, String username, boolean isBot, boolean isAdmin,
                         String agentKey) {
        public boolean isHuman() {
            return !isBot;
        }
    }

    /** @param agentKey set when replying to one of the relay's agent bots */
    public record ReplyRef(long messageId, Long senderUserId, String agentKey) {}

    public MessageEnvelope {
        mentionedAgents = Collections.unmodifiableSet(new LinkedHashSet<>(mentionedAgents));
    }

    /** Agents this message is aimed at: explicit mentions plus the agent being replied to. */
    public Set<String> targetAgents() {
        Set<String> targets = new LinkedHashSet<>(mentionedAgents);
        if (replyTo != null && replyTo.agentKey() != null) targets.add(replyTo.agentKey());
        return targets;
    }

    public static String rootChainId(long chatId, long messageId) {
        return "tg:" + chatId + ":" + messageId;
    }
}
