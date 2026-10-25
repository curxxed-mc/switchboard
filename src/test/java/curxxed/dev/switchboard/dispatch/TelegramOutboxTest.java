package curxxed.dev.switchboard.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import curxxed.dev.switchboard.app.RelayApp;
import curxxed.dev.switchboard.store.Database;
import curxxed.dev.switchboard.store.MessageLedger;
import curxxed.dev.switchboard.telegram.FakeTelegram;
import curxxed.dev.switchboard.telegram.TelegramApi;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class TelegramOutboxTest {

    @Test
    void shortTextIsOneChunk() {
        assertEquals(List.of("hello"), TelegramOutbox.split("hello"));
    }

    @Test
    void longTextSplitsAtParagraphsUnderTheLimit() {
        String para = "x".repeat(3000);
        List<String> chunks = TelegramOutbox.split(para + "\n\n" + para + "\n\n" + "tail");
        assertEquals(2, chunks.size());
        assertEquals(para, chunks.get(0));
        assertTrue(chunks.stream().allMatch(c -> c.length() <= TelegramOutbox.MAX_CHUNK));
        assertEquals(para + "\n\ntail", chunks.get(1));
    }

    @Test
    void fallsBackToWordsThenHardCuts() {
        String words = ("word ".repeat(1000)).strip(); // 4999 chars, no newlines
        List<String> chunks = TelegramOutbox.split(words);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).endsWith("word") && chunks.get(1).startsWith("word"));

        String blob = "y".repeat(9000);
        List<String> cut = TelegramOutbox.split(blob);
        assertEquals(List.of(4000, 4000, 1000), cut.stream().map(String::length).toList());
        assertEquals(blob, String.join("", cut));
    }

    @Test
    void postsAsTheAgentsBotThreadsChunksAndRecordsThem() throws Exception {
        try (FakeTelegram telegram = new FakeTelegram(); Database db = Database.inMemory();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            telegram.addBot(new FakeTelegram.Bot("102:codex", 102, "CodexRelayBot", true));
            TelegramApi codex = new TelegramApi(telegram.apiBase(), "102:codex", RelayApp.newHttpClient(executor), new ObjectMapper());
            MessageLedger ledger = new MessageLedger(db);
            TelegramOutbox outbox = new TelegramOutbox(Map.of("codex", codex), DispatcherTest.DIRECTORY, ledger, Clock.systemUTC());

            long first = outbox.postReply("codex", -100L, 42, "a".repeat(3000) + "\n\n" + "b".repeat(3000));
            outbox.notice("codex", -100L, first, "Stopped this chain: example.");

            List<FakeTelegram.Sent> sent = telegram.sent("sendMessage");
            assertEquals(3, sent.size());
            assertEquals(42, sent.get(0).body().path("reply_parameters").path("message_id").asLong());
            assertEquals(first, sent.get(1).body().path("reply_parameters").path("message_id").asLong(),
                    "the continuation replies to the first chunk");
            assertTrue(sent.stream().allMatch(s -> s.token().equals("102:codex")));

            List<MessageLedger.Entry> recorded = ledger.recent(-100L, 10);
            assertEquals(List.of(MessageLedger.SenderKind.AGENT, MessageLedger.SenderKind.AGENT, MessageLedger.SenderKind.SYSTEM),
                    recorded.stream().map(MessageLedger.Entry::senderKind).toList());
            assertEquals("codex", recorded.get(0).agentKey());
            assertEquals(102L, recorded.get(0).senderId());
            assertEquals("relay", recorded.get(2).senderName());
        }
    }
}
