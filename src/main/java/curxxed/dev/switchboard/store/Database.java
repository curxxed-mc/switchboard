package curxxed.dev.switchboard.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * The relay's SQLite database. SQLite allows one writer at a time, so a single connection guarded
 * by a {@link ReentrantLock} is simpler than a pool and avoids {@code SQLITE_BUSY}. A lock rather
 * than {@code synchronized} lets waiting virtual threads unmount instead of pinning their carrier
 * on Java 21.
 */
public final class Database implements AutoCloseable {

    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    /**
     * Index i holds the migration that brings {@code user_version} from i to i + 1. Append only.
     * {@code --} comments are stripped, then statements are split on {@code ;}, so string literals
     * must not contain semicolons.
     */
    private static final List<String> MIGRATIONS = List.of(
            """
            CREATE TABLE processed_updates (
                bot_key      TEXT    NOT NULL,
                update_id    INTEGER NOT NULL,
                processed_at INTEGER NOT NULL,
                PRIMARY KEY (bot_key, update_id)
            );
            CREATE INDEX processed_updates_age ON processed_updates (processed_at);

            -- Every bot in the group receives its own copy of each message under a different update_id.
            -- The first bot to claim (chat, message, kind) handles it; the others drop their copy.
            CREATE TABLE processed_messages (
                chat_id      INTEGER NOT NULL,
                message_id   INTEGER NOT NULL,
                kind         TEXT    NOT NULL,
                claimed_by   TEXT    NOT NULL,
                processed_at INTEGER NOT NULL,
                PRIMARY KEY (chat_id, message_id, kind)
            );
            CREATE INDEX processed_messages_age ON processed_messages (processed_at);

            CREATE TABLE bot_offsets (
                bot_key     TEXT    PRIMARY KEY,
                next_offset INTEGER NOT NULL
            );

            CREATE TABLE bot_identities (
                bot_key     TEXT    PRIMARY KEY,
                bot_user_id INTEGER NOT NULL,
                username    TEXT    NOT NULL,
                first_name  TEXT    NOT NULL,
                updated_at  INTEGER NOT NULL
            );

            -- Group ledger: what humans and agents said, in order, for prompt context.
            CREATE TABLE messages (
                id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id             INTEGER NOT NULL,
                message_id          INTEGER NOT NULL,
                sender_kind         TEXT    NOT NULL CHECK (sender_kind IN ('human', 'agent', 'system')),
                sender_id           INTEGER,
                sender_name         TEXT    NOT NULL,
                agent_key           TEXT,
                reply_to_message_id INTEGER,
                text                TEXT    NOT NULL,
                created_at          INTEGER NOT NULL,
                UNIQUE (chat_id, message_id)
            );
            CREATE INDEX messages_chat_order ON messages (chat_id, id);

            -- Per-agent runtime state that survives restarts, such as a /model override.
            CREATE TABLE agent_state (
                agent_key  TEXT    PRIMARY KEY,
                model      TEXT,
                updated_at INTEGER NOT NULL
            );

            -- One T3 thread per (agent, project): the agent keeps its conversation and worktree.
            CREATE TABLE agent_threads (
                agent_key     TEXT    NOT NULL,
                project_id    TEXT    NOT NULL,
                t3_thread_id  TEXT    NOT NULL UNIQUE,
                worktree_path TEXT,
                branch        TEXT,
                created_at    INTEGER NOT NULL,
                updated_at    INTEGER NOT NULL,
                PRIMARY KEY (agent_key, project_id)
            );

            -- One row per agent turn: the audit trail for depth limits and loop breaking.
            CREATE TABLE invocations (
                id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                chain_id           TEXT    NOT NULL,
                agent_key          TEXT    NOT NULL,
                depth              INTEGER NOT NULL,
                trigger_chat_id    INTEGER NOT NULL,
                trigger_message_id INTEGER NOT NULL,
                t3_thread_id       TEXT,
                t3_run_id          TEXT,
                status             TEXT    NOT NULL,
                response_hash      TEXT,
                started_at         INTEGER NOT NULL,
                finished_at        INTEGER
            );
            CREATE INDEX invocations_chain ON invocations (chain_id);
            CREATE INDEX invocations_agent_recent ON invocations (agent_key, started_at);

            CREATE TABLE settings (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            """,
            """
            CREATE TABLE usage_limits (
                agent_key TEXT NOT NULL,
                provider_id TEXT NOT NULL,
                model TEXT NOT NULL,
                window_name TEXT NOT NULL,
                observed_at TEXT NOT NULL,
                resets_at TEXT,
                reminded INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (agent_key, provider_id, model, window_name)
            );
            """
    );

    private final Connection connection;
    private final ReentrantLock lock = new ReentrantLock();

    private Database(Connection connection) {
        this.connection = connection;
    }

    public static Database open(Path file) throws IOException, SQLException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        return openUrl("jdbc:sqlite:" + file);
    }

    /** Private in-memory database, for tests. */
    public static Database inMemory() throws SQLException {
        return openUrl("jdbc:sqlite::memory:");
    }

    private static Database openUrl(String url) throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA synchronous = NORMAL");
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
        }
        Database db = new Database(connection);
        db.migrate();
        return db;
    }

    public int schemaVersion() throws SQLException {
        return read(c -> {
            try (Statement st = c.createStatement(); var rs = st.executeQuery("PRAGMA user_version")) {
                return rs.getInt(1);
            }
        });
    }

    private void migrate() throws SQLException {
        int current = schemaVersion();
        if (current > MIGRATIONS.size()) {
            throw new SQLException("Database schema v" + current + " is newer than this build (v" + MIGRATIONS.size() + ")");
        }
        for (int version = current; version < MIGRATIONS.size(); version++) {
            String sql = MIGRATIONS.get(version);
            int next = version + 1;
            transaction(c -> {
                try (Statement st = c.createStatement()) {
                    for (String statement : stripLineComments(sql).split(";")) {
                        if (!statement.isBlank()) st.execute(statement);
                    }
                    st.execute("PRAGMA user_version = " + next);
                }
                return null;
            });
        }
    }

    static String stripLineComments(String sql) {
        return sql.lines().map(line -> {
            int comment = line.indexOf("--");
            return comment < 0 ? line : line.substring(0, comment);
        }).collect(Collectors.joining("\n"));
    }

    /** Runs {@code work} with exclusive use of the connection, in autocommit mode. */
    public <T> T read(SqlWork<T> work) throws SQLException {
        lock.lock();
        try {
            return work.apply(connection);
        } finally {
            lock.unlock();
        }
    }

    /** Runs {@code work} atomically: committed if it returns, rolled back if it throws. */
    public <T> T transaction(SqlWork<T> work) throws SQLException {
        lock.lock();
        try {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() throws SQLException {
        lock.lock();
        try {
            connection.close();
        } finally {
            lock.unlock();
        }
    }
}
