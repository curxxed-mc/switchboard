package curxxed.dev.switchboard.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class UsageStore {
    public record Identity(String agent, String provider, String model) {}
    public record Limit(Identity identity, String window, Instant observedAt, Instant resetsAt, boolean reminded) {}

    private final Database db;

    public UsageStore(Database db) {
        this.db = db;
    }

    public void record(Identity identity, String window, Instant observedAt, Instant resetsAt) throws SQLException {
        db.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO usage_limits (agent_key, provider_id, model, window_name, observed_at, resets_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (agent_key, provider_id, model, window_name) DO UPDATE SET
                        observed_at = excluded.observed_at, resets_at = excluded.resets_at, reminded = 0
                    """)) {
                bindIdentity(statement, identity);
                statement.setString(4, window);
                statement.setString(5, observedAt.toString());
                statement.setString(6, resetsAt == null ? null : resetsAt.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public List<Limit> limits(Identity identity) throws SQLException {
        return db.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM usage_limits WHERE agent_key = ? AND provider_id = ? AND model = ?
                    ORDER BY window_name
                    """)) {
                bindIdentity(statement, identity);
                List<Limit> limits = new ArrayList<>();
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        String reset = results.getString("resets_at");
                        limits.add(new Limit(identity, results.getString("window_name"),
                                Instant.parse(results.getString("observed_at")),
                                reset == null ? null : Instant.parse(reset), results.getBoolean("reminded")));
                    }
                }
                return limits;
            }
        });
    }

    public void clear(Identity identity) throws SQLException {
        db.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM usage_limits WHERE agent_key = ? AND provider_id = ? AND model = ?")) {
                bindIdentity(statement, identity);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void markReminded(Limit limit) throws SQLException {
        db.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE usage_limits SET reminded = 1
                    WHERE agent_key = ? AND provider_id = ? AND model = ? AND window_name = ? AND observed_at = ?
                    """)) {
                bindIdentity(statement, limit.identity());
                statement.setString(4, limit.window());
                statement.setString(5, limit.observedAt().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static void bindIdentity(PreparedStatement statement, Identity identity) throws SQLException {
        statement.setString(1, identity.agent());
        statement.setString(2, identity.provider());
        statement.setString(3, identity.model());
    }
}
