package curxxed.dev.switchboard.store;

import curxxed.dev.switchboard.store.UpdateLedger.Claim;
import curxxed.dev.switchboard.store.UpdateLedger.UpdateRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class UpdateLedgerTest {

    private static final long GROUP = -1001234567890L;

    private MutableClock clock;
    private Database db;
    private UpdateLedger ledger;

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock(Instant.parse("2026-10-25T12:00:00Z"));
        db = Database.inMemory();
        ledger = new UpdateLedger(db, clock);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    @Test
    void sameUpdateTwiceFromOneBotIsDuplicate() throws Exception {
        UpdateRef ref = UpdateRef.ofMessage("claude", 500, GROUP, 42, "message");
        assertEquals(Claim.FIRST, ledger.claim(ref));
        assertEquals(Claim.DUPLICATE_UPDATE, ledger.claim(ref));
    }

    @Test
    void sameGroupMessageAcrossThreeBotsIsHandledOnce() throws Exception {
        // Each bot sees the human's message under its own update_id.
        assertEquals(Claim.FIRST, ledger.claim(UpdateRef.ofMessage("claude", 500, GROUP, 42, "message")));
        assertEquals(Claim.CLAIMED_BY_OTHER_BOT, ledger.claim(UpdateRef.ofMessage("codex", 9001, GROUP, 42, "message")));
        assertEquals(Claim.CLAIMED_BY_OTHER_BOT, ledger.claim(UpdateRef.ofMessage("gemini", 77, GROUP, 42, "message")));
    }

    @Test
    void redeliveryToLosingBotStaysDuplicateNotClaimed() throws Exception {
        ledger.claim(UpdateRef.ofMessage("claude", 500, GROUP, 42, "message"));
        UpdateRef codex = UpdateRef.ofMessage("codex", 9001, GROUP, 42, "message");
        assertEquals(Claim.CLAIMED_BY_OTHER_BOT, ledger.claim(codex));
        assertEquals(Claim.DUPLICATE_UPDATE, ledger.claim(codex));
    }

    @Test
    void anEditIsDistinctFromTheOriginalMessage() throws Exception {
        assertEquals(Claim.FIRST, ledger.claim(UpdateRef.ofMessage("claude", 500, GROUP, 42, "message")));
        assertEquals(Claim.FIRST, ledger.claim(UpdateRef.ofMessage("codex", 9002, GROUP, 42, "edited_message")));
    }

    @Test
    void sameMessageIdInDifferentChatsIsDistinct() throws Exception {
        assertEquals(Claim.FIRST, ledger.claim(UpdateRef.ofMessage("claude", 1, GROUP, 42, "message")));
        assertEquals(Claim.FIRST, ledger.claim(UpdateRef.ofMessage("claude", 2, -100999L, 42, "message")));
    }

    @Test
    void updatesWithoutAMessageDedupeOnUpdateIdOnly() throws Exception {
        UpdateRef ref = UpdateRef.withoutMessage("gemini", 12, "my_chat_member");
        assertEquals(Claim.FIRST, ledger.claim(ref));
        assertEquals(Claim.DUPLICATE_UPDATE, ledger.claim(ref));
        assertEquals(Claim.FIRST, ledger.claim(UpdateRef.withoutMessage("claude", 12, "my_chat_member")));
    }

    @Test
    void concurrentBotsRacingForOneMessageProduceExactlyOneWinner() throws Exception {
        String[] bots = {"claude", "codex", "gemini"};
        int messages = 200;
        ConcurrentHashMap<Long, List<String>> winners = new ConcurrentHashMap<>();
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int b = 0; b < bots.length; b++) {
                String bot = bots[b];
                long updateBase = (b + 1) * 100_000L;
                futures.add(executor.submit(() -> {
                    go.await();
                    for (long m = 1; m <= messages; m++) {
                        if (ledger.claim(UpdateRef.ofMessage(bot, updateBase + m, GROUP, m, "message")) == Claim.FIRST) {
                            winners.computeIfAbsent(m, k -> new CopyOnWriteArrayList<>()).add(bot);
                        }
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : futures) f.get();
        }
        assertEquals(messages, winners.size());
        winners.forEach((m, w) -> assertEquals(1, w.size(), "message " + m + " won by " + w));
    }

    @Test
    void claimsSurviveRestart(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("relay.db");
        try (Database first = Database.open(file)) {
            UpdateLedger l = new UpdateLedger(first, clock);
            assertEquals(Claim.FIRST, l.claim(UpdateRef.ofMessage("claude", 500, GROUP, 42, "message")));
            l.advanceOffset("claude", 501);
        }
        try (Database second = Database.open(file)) {
            UpdateLedger l = new UpdateLedger(second, clock);
            assertEquals(Claim.DUPLICATE_UPDATE, l.claim(UpdateRef.ofMessage("claude", 500, GROUP, 42, "message")));
            assertEquals(Claim.CLAIMED_BY_OTHER_BOT, l.claim(UpdateRef.ofMessage("codex", 9001, GROUP, 42, "message")));
            assertEquals(501, l.nextOffset("claude").orElseThrow());
        }
    }

    @Test
    void offsetsOnlyMoveForward() throws Exception {
        assertTrue(ledger.nextOffset("claude").isEmpty());
        ledger.advanceOffset("claude", 600);
        ledger.advanceOffset("claude", 550);
        assertEquals(600, ledger.nextOffset("claude").orElseThrow());
        ledger.advanceOffset("claude", 601);
        assertEquals(601, ledger.nextOffset("claude").orElseThrow());
    }

    @Test
    void pruneRemovesOnlyOldRows() throws Exception {
        ledger.claim(UpdateRef.ofMessage("claude", 1, GROUP, 1, "message"));
        clock.advance(Duration.ofDays(8));
        ledger.claim(UpdateRef.ofMessage("claude", 2, GROUP, 2, "message"));

        int removed = ledger.pruneBefore(clock.instant().minus(Duration.ofDays(7)));

        assertEquals(2, removed, "one processed_updates row and one processed_messages row");
        assertEquals(Claim.DUPLICATE_UPDATE, ledger.claim(UpdateRef.ofMessage("claude", 2, GROUP, 2, "message")));
        assertEquals(Claim.FIRST, ledger.claim(UpdateRef.ofMessage("claude", 1, GROUP, 1, "message")));
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
