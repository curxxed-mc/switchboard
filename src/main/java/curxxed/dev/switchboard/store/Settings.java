package curxxed.dev.switchboard.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Admin-controlled switches that persist across restarts: interaction mode and active project. */
public final class Settings {

    public enum Mode { MANUAL, AUTONOMOUS }

    static final String MODE = "mode";
    static final String ACTIVE_PROJECT = "active_project_id";

    private final Database db;

    public Settings(Database db) {
        this.db = db;
    }

    /** Defaults to MANUAL: agents only act when the admin targets them. */
    public Mode mode() throws SQLException {
        return get(MODE).map(Mode::valueOf).orElse(Mode.MANUAL);
    }

    public void setMode(Mode mode) throws SQLException {
        put(MODE, mode.name());
    }

    public Optional<String> activeProjectId() throws SQLException {
        return get(ACTIVE_PROJECT);
    }

    public void setActiveProjectId(String projectId) throws SQLException {
        put(ACTIVE_PROJECT, projectId);
    }

    /** Back to the project named in config.json. */
    public void clearActiveProjectId() throws SQLException {
        db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM settings WHERE key = ?")) {
                ps.setString(1, ACTIVE_PROJECT);
                ps.executeUpdate();
            }
            return null;
        });
    }

    Optional<String> get(String key) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM settings WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
                }
            }
        });
    }

    void put(String key, String value) throws SQLException {
        db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
