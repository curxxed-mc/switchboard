package curxxed.dev.switchboard.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** Audit trail of agent turns: admitted, denied, and how each one ended. */
public final class InvocationLog {

    public enum Status { RUNNING, COMPLETED, FAILED, CANCELLED, DENIED, ABANDONED }

    public record Row(long id, String chainId, String agentKey, int depth, String status, String responseHash) {}

    private final Database db;
    private final Clock clock;

    public InvocationLog(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** @return the new row's id */
    public long start(String chainId, String agentKey, int depth, long chatId, long triggerMessageId)
            throws SQLException {
        return insert(chainId, agentKey, depth, chatId, triggerMessageId, Status.RUNNING.name().toLowerCase(), false);
    }

    /** Records a turn that was never started, with the reason in its status, e.g. {@code denied:agent_busy}. */
    public void denied(String chainId, String agentKey, int depth, long chatId, long triggerMessageId, String reason)
            throws SQLException {
        insert(chainId, agentKey, depth, chatId, triggerMessageId, "denied:" + reason.toLowerCase(), true);
    }

    public void finish(long id, Status status, String responseHash) throws SQLException {
        db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE invocations SET status = ?, response_hash = ?, finished_at = ? WHERE id = ?")) {
                ps.setString(1, status.name().toLowerCase());
                ps.setString(2, responseHash);
                ps.setLong(3, clock.millis());
                ps.setLong(4, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** At startup: turns left running by a previous process can no longer be tracked. */
    public int abandonRunning() throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE invocations SET status = 'abandoned', finished_at = ? WHERE status = 'running'")) {
                ps.setLong(1, clock.millis());
                return ps.executeUpdate();
            }
        });
    }

    public List<Row> chain(String chainId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM invocations WHERE chain_id = ? ORDER BY id")) {
                ps.setString(1, chainId);
                List<Row> rows = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Row(rs.getLong("id"), rs.getString("chain_id"), rs.getString("agent_key"),
                                rs.getInt("depth"), rs.getString("status"), rs.getString("response_hash")));
                    }
                }
                return rows;
            }
        });
    }

    private long insert(String chainId, String agentKey, int depth, long chatId, long triggerMessageId, String status,
                        boolean finished) throws SQLException {
        long now = clock.millis();
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO invocations (chain_id, agent_key, depth, trigger_chat_id, trigger_message_id,
                                             status, started_at, finished_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, chainId);
                ps.setString(2, agentKey);
                ps.setInt(3, depth);
                ps.setLong(4, chatId);
                ps.setLong(5, triggerMessageId);
                ps.setString(6, status);
                ps.setLong(7, now);
                if (finished) ps.setLong(8, now);
                else ps.setNull(8, Types.INTEGER);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }
}
