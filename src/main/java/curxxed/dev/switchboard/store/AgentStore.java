package curxxed.dev.switchboard.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Optional;

/** Bot identities, per-agent state, and the T3 thread each agent works in. */
public final class AgentStore {

    public record BotIdentity(String botKey, long botUserId, String username, String firstName) {}

    public record AgentThread(String agentKey, String projectId, String t3ThreadId, String worktreePath,
                              String branch) {}

    private final Database db;
    private final Clock clock;

    public AgentStore(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** Stores what {@code getMe} reported, so mention parsing knows each bot's real @username. */
    public void saveIdentity(BotIdentity identity) throws SQLException {
        db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO bot_identities (bot_key, bot_user_id, username, first_name, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (bot_key) DO UPDATE SET bot_user_id = excluded.bot_user_id,
                        username = excluded.username, first_name = excluded.first_name,
                        updated_at = excluded.updated_at""")) {
                ps.setString(1, identity.botKey());
                ps.setLong(2, identity.botUserId());
                ps.setString(3, identity.username());
                ps.setString(4, identity.firstName());
                ps.setLong(5, clock.millis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<BotIdentity> identity(String botKey) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM bot_identities WHERE bot_key = ?")) {
                ps.setString(1, botKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(new BotIdentity(rs.getString("bot_key"), rs.getLong("bot_user_id"),
                            rs.getString("username"), rs.getString("first_name")));
                }
            }
        });
    }

    /** The /model override for an agent, or empty to use the configured default. */
    public Optional<String> modelOverride(String agentKey) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT model FROM agent_state WHERE agent_key = ?")) {
                ps.setString(1, agentKey);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
                }
            }
        });
    }

    /** @param model null clears the override */
    public void setModelOverride(String agentKey, String model) throws SQLException {
        db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO agent_state (agent_key, model, updated_at) VALUES (?, ?, ?)
                    ON CONFLICT (agent_key) DO UPDATE SET model = excluded.model, updated_at = excluded.updated_at""")) {
                ps.setString(1, agentKey);
                ps.setString(2, model);
                ps.setLong(3, clock.millis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<AgentThread> thread(String agentKey, String projectId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM agent_threads WHERE agent_key = ? AND project_id = ?")) {
                ps.setString(1, agentKey);
                ps.setString(2, projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(new AgentThread(rs.getString("agent_key"), rs.getString("project_id"),
                            rs.getString("t3_thread_id"), rs.getString("worktree_path"), rs.getString("branch")));
                }
            }
        });
    }

    /** Forgets a thread that no longer exists in T3, so the next turn launches a new one. */
    public void deleteThread(String agentKey, String projectId) throws SQLException {
        db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM agent_threads WHERE agent_key = ? AND project_id = ?")) {
                ps.setString(1, agentKey);
                ps.setString(2, projectId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void saveThread(AgentThread thread) throws SQLException {
        long now = clock.millis();
        db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO agent_threads
                        (agent_key, project_id, t3_thread_id, worktree_path, branch, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (agent_key, project_id) DO UPDATE SET t3_thread_id = excluded.t3_thread_id,
                        worktree_path = excluded.worktree_path, branch = excluded.branch,
                        updated_at = excluded.updated_at""")) {
                ps.setString(1, thread.agentKey());
                ps.setString(2, thread.projectId());
                ps.setString(3, thread.t3ThreadId());
                ps.setString(4, thread.worktreePath());
                ps.setString(5, thread.branch());
                ps.setLong(6, now);
                ps.setLong(7, now);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
