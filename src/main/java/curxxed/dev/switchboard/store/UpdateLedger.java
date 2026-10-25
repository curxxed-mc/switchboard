package curxxed.dev.switchboard.store;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * Idempotency for Telegram updates arriving through three bots.
 *
 * <p>Two levels:
 * <ol>
 *   <li>{@code (bot, update_id)}: the same bot sees the same update again, for example when the
 *       process restarts before {@code getUpdates} confirms the offset.</li>
 *   <li>{@code (chat, message_id, kind)}: a group message reaches every bot under a different
 *       {@code update_id}. Only the first bot to claim it handles it.</li>
 * </ol>
 * Both checks run in one transaction, so concurrent bots can't both win a message.
 *
 * <p>Claims are recorded before handling: a crash mid-handling drops that message rather than
 * replaying it. At-most-once is deliberate, since a replay could start a duplicate agent run.
 */
public final class UpdateLedger {

    public enum Claim {
        /** This bot is the first to see the message: handle it. */
        FIRST,
        /** This bot already processed this update_id. */
        DUPLICATE_UPDATE,
        /** Another bot already claimed the message. */
        CLAIMED_BY_OTHER_BOT
    }

    /** What a Telegram update refers to. {@code messageId == null} for updates that carry no message. */
    public record UpdateRef(String botKey, long updateId, Long chatId, Long messageId, String kind) {
        public static UpdateRef ofMessage(String botKey, long updateId, long chatId, long messageId, String kind) {
            return new UpdateRef(botKey, updateId, chatId, messageId, kind);
        }

        public static UpdateRef withoutMessage(String botKey, long updateId, String kind) {
            return new UpdateRef(botKey, updateId, null, null, kind);
        }
    }

    private final Database db;
    private final Clock clock;

    public UpdateLedger(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public Claim claim(UpdateRef ref) throws SQLException {
        long now = clock.millis();
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO processed_updates (bot_key, update_id, processed_at) VALUES (?, ?, ?)")) {
                ps.setString(1, ref.botKey());
                ps.setLong(2, ref.updateId());
                ps.setLong(3, now);
                if (ps.executeUpdate() == 0) return Claim.DUPLICATE_UPDATE;
            }
            if (ref.messageId() == null) return Claim.FIRST;
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT OR IGNORE INTO processed_messages (chat_id, message_id, kind, claimed_by, processed_at)
                    VALUES (?, ?, ?, ?, ?)""")) {
                ps.setLong(1, ref.chatId());
                ps.setLong(2, ref.messageId());
                ps.setString(3, ref.kind());
                ps.setString(4, ref.botKey());
                ps.setLong(5, now);
                return ps.executeUpdate() == 0 ? Claim.CLAIMED_BY_OTHER_BOT : Claim.FIRST;
            }
        });
    }

    /** The next {@code getUpdates} offset for a bot, or empty on first start. */
    public OptionalLong nextOffset(String botKey) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT next_offset FROM bot_offsets WHERE bot_key = ?")) {
                ps.setString(1, botKey);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
                }
            }
        });
    }

    /** Records progress. Offsets only move forward, so a stale writer can't rewind a bot. */
    public void advanceOffset(String botKey, long nextOffset) throws SQLException {
        db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO bot_offsets (bot_key, next_offset) VALUES (?, ?)
                    ON CONFLICT (bot_key) DO UPDATE SET next_offset = max(next_offset, excluded.next_offset)""")) {
                ps.setString(1, botKey);
                ps.setLong(2, nextOffset);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Drops dedup rows older than {@code cutoff}. Telegram keeps undelivered updates for 24 hours,
     * so any cutoff well past that is safe.
     *
     * @return rows removed
     */
    public int pruneBefore(Instant cutoff) throws SQLException {
        long millis = cutoff.toEpochMilli();
        return db.transaction(c -> {
            int removed = 0;
            for (String table : new String[] {"processed_updates", "processed_messages"}) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE processed_at < ?")) {
                    ps.setLong(1, millis);
                    removed += ps.executeUpdate();
                }
            }
            return removed;
        });
    }
}
