package curxxed.dev.switchboard.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The group conversation record, used to give agents recent context that separates humans from agents. */
public final class MessageLedger {

    public enum SenderKind { HUMAN, AGENT, SYSTEM }

    /**
     * @param senderId Telegram user id (the bot's id for agent messages)
     * @param agentKey set for agent messages: claude, codex or gemini
     */
    public record Entry(long chatId, long messageId, SenderKind senderKind, Long senderId, String senderName,
                        String agentKey, Long replyToMessageId, String text, Instant createdAt) {}

    private final Database db;

    public MessageLedger(Database db) {
        this.db = db;
    }

    /** @return false if the message was already recorded */
    public boolean record(Entry e) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT OR IGNORE INTO messages
                        (chat_id, message_id, sender_kind, sender_id, sender_name, agent_key,
                         reply_to_message_id, text, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setLong(1, e.chatId());
                ps.setLong(2, e.messageId());
                ps.setString(3, e.senderKind().name().toLowerCase());
                setNullableLong(ps, 4, e.senderId());
                ps.setString(5, e.senderName());
                ps.setString(6, e.agentKey());
                setNullableLong(ps, 7, e.replyToMessageId());
                ps.setString(8, e.text());
                ps.setLong(9, e.createdAt().toEpochMilli());
                return ps.executeUpdate() == 1;
            }
        });
    }

    /**
     * The last {@code limit} messages in the chat, oldest first. Ordered by Telegram's message id,
     * which increases monotonically within a chat, because concurrent bots don't record messages
     * in arrival order.
     */
    public List<Entry> recent(long chatId, int limit) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM messages WHERE chat_id = ? ORDER BY message_id DESC LIMIT ?")) {
                ps.setLong(1, chatId);
                ps.setInt(2, limit);
                List<Entry> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(read(rs));
                }
                Collections.reverse(out);
                return out;
            }
        });
    }

    private static Entry read(ResultSet rs) throws SQLException {
        return new Entry(
                rs.getLong("chat_id"),
                rs.getLong("message_id"),
                SenderKind.valueOf(rs.getString("sender_kind").toUpperCase()),
                nullableLong(rs, "sender_id"),
                rs.getString("sender_name"),
                rs.getString("agent_key"),
                nullableLong(rs, "reply_to_message_id"),
                rs.getString("text"),
                Instant.ofEpochMilli(rs.getLong("created_at")));
    }

    static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER);
        else ps.setLong(index, value);
    }

    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}
