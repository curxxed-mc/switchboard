package curxxed.dev.switchboard.store;

import curxxed.dev.switchboard.store.MessageLedger.Entry;
import curxxed.dev.switchboard.store.MessageLedger.SenderKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StoresTest {

    private static final long GROUP = -1001234567890L;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC);
    private Database db;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    @Test
    void migrationsReachLatestVersionAndReopenIsIdempotent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("relay.db");
        int version;
        try (Database first = Database.open(file)) {
            version = first.schemaVersion();
            assertTrue(version >= 1);
        }
        try (Database second = Database.open(file)) {
            assertEquals(version, second.schemaVersion());
        }
    }

    @Test
    void newDatabaseAndItsWalAreOwnerOnly(@TempDir Path dir) throws Exception {
        assumeTrue(Files.getFileAttributeView(dir, PosixFileAttributeView.class) != null, "POSIX file system");
        Path file = dir.resolve("relay.db");
        try (Database opened = Database.open(file)) {
            assertTrue(opened.schemaVersion() >= 1);
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
            Path wal = dir.resolve("relay.db-wal");
            assertTrue(Files.exists(wal), "migrations write through the WAL");
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(wal)));
        }
    }

    @Test
    void existingReadableDatabaseIsTightened(@TempDir Path dir) throws Exception {
        assumeTrue(Files.getFileAttributeView(dir, PosixFileAttributeView.class) != null, "POSIX file system");
        Path file = dir.resolve("relay.db");
        try (Database first = Database.open(file)) {
            assertTrue(first.schemaVersion() >= 1);
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        try (Database second = Database.open(file)) {
            assertTrue(second.schemaVersion() >= 1);
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
    }

    @Test
    void refusesDatabaseFromNewerBuild(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("relay.db");
        try (Database first = Database.open(file)) {
            first.read(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("PRAGMA user_version = 999");
                }
                return null;
            });
        }
        SQLException e = assertThrows(SQLException.class, () -> Database.open(file));
        assertTrue(e.getMessage().contains("newer"), e.getMessage());
    }

    @Test
    void transactionRollsBackOnFailure() throws Exception {
        Settings settings = new Settings(db);
        assertThrows(IllegalStateException.class, () -> db.transaction(c -> {
            settings.put("k", "v");
            throw new IllegalStateException("boom");
        }));
        assertTrue(settings.get("k").isEmpty());
    }

    @Test
    void ledgerReturnsMostRecentOldestFirstAndIgnoresRepeats() throws Exception {
        MessageLedger ledger = new MessageLedger(db);
        for (int i = 1; i <= 5; i++) {
            boolean human = i % 2 == 1;
            assertTrue(ledger.record(new Entry(GROUP, i, human ? SenderKind.HUMAN : SenderKind.AGENT,
                    human ? 111L : 222L, human ? "admin" : "ClaudeBot", human ? null : "claude",
                    i > 1 ? (long) i - 1 : null, "msg " + i, clock.instant())));
        }
        assertFalse(ledger.record(new Entry(GROUP, 3, SenderKind.HUMAN, 111L, "admin", null, null, "dup", clock.instant())));

        List<Entry> recent = ledger.recent(GROUP, 3);
        assertEquals(List.of("msg 3", "msg 4", "msg 5"), recent.stream().map(Entry::text).toList());
        assertEquals(SenderKind.AGENT, recent.get(1).senderKind());
        assertEquals("claude", recent.get(1).agentKey());
        assertEquals(3L, recent.get(1).replyToMessageId());
        assertNull(ledger.recent(GROUP, 5).get(0).replyToMessageId());
        assertTrue(ledger.recent(-1L, 10).isEmpty());
    }

    @Test
    void agentStoreRoundTrips() throws Exception {
        AgentStore agents = new AgentStore(db, clock);

        agents.saveIdentity(new AgentStore.BotIdentity("codex", 42L, "CodexBot", "Codex"));
        agents.saveIdentity(new AgentStore.BotIdentity("codex", 42L, "CodexRelayBot", "Codex"));
        assertEquals("CodexRelayBot", agents.identity("codex").orElseThrow().username());
        assertTrue(agents.identity("gemini").isEmpty());

        assertTrue(agents.modelOverride("codex").isEmpty());
        agents.setModelOverride("codex", "gpt-6-astra");
        assertEquals("gpt-6-astra", agents.modelOverride("codex").orElseThrow());
        agents.setModelOverride("codex", null);
        assertTrue(agents.modelOverride("codex").isEmpty());

        agents.saveThread(new AgentStore.AgentThread("claude", "proj-1", "thread-a", null, null));
        agents.saveThread(new AgentStore.AgentThread("claude", "proj-1", "thread-b", "/wt/x", "relay/claude"));
        AgentStore.AgentThread t = agents.thread("claude", "proj-1").orElseThrow();
        assertEquals("thread-b", t.t3ThreadId());
        assertEquals("/wt/x", t.worktreePath());
        assertTrue(agents.thread("codex", "proj-1").isEmpty());
    }

    @Test
    void settingsDefaultToManualAndPersist() throws Exception {
        Settings settings = new Settings(db);
        assertEquals(Settings.Mode.MANUAL, settings.mode());
        settings.setMode(Settings.Mode.AUTONOMOUS);
        assertEquals(Settings.Mode.AUTONOMOUS, settings.mode());
        assertTrue(settings.activeProjectId().isEmpty());
        settings.setActiveProjectId("edc8ee99");
        assertEquals("edc8ee99", settings.activeProjectId().orElseThrow());
    }
}
