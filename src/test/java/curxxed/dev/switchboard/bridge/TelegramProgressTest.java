package curxxed.dev.switchboard.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import curxxed.dev.switchboard.app.RelayApp;
import curxxed.dev.switchboard.dispatch.Turn;
import curxxed.dev.switchboard.telegram.FakeTelegram;
import curxxed.dev.switchboard.telegram.TelegramApi;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class TelegramProgressTest {

    @Test
    void postsEditsThrottledAndDeletesWhenDone() throws Exception {
        try (FakeTelegram telegram = new FakeTelegram(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            telegram.addBot(new FakeTelegram.Bot("101:claude", 101, "ClaudeRelayBot", true));
            TelegramApi api = new TelegramApi(telegram.apiBase(), "101:claude", RelayApp.newHttpClient(executor), new ObjectMapper());
            Instant[] now = {Instant.parse("2026-10-25T12:00:00Z")};
            Clock clock = new Clock() {
                @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return now[0]; }
            };
            TelegramProgress progress = new TelegramProgress(Map.of("claude", api), clock);
            Turn turn = new Turn("c", "claude", 1, -100L, 42, "go", "Alex", null);

            ProgressReporter.Progress p = progress.start(turn, "Claude is starting…");
            p.update("Claude is working · 1s");            // too soon after posting: skipped
            now[0] = now[0].plus(Duration.ofSeconds(4));
            p.update("Claude is working · 5s\n• $ ls ✓");  // edited
            p.update("Claude is working · 5s\n• $ ls ✓");  // unchanged: skipped
            p.dismiss();

            List<String> methods = telegram.sent().stream().map(FakeTelegram.Sent::method).toList();
            assertEquals(List.of("sendMessage", "editMessageText", "deleteMessage"), methods);
            long statusId = telegram.sent("editMessageText").get(0).body().path("message_id").asLong();
            assertEquals(statusId, telegram.sent("deleteMessage").get(0).body().path("message_id").asLong(),
                    "the status message itself is deleted");
            assertEquals(42, telegram.sent("sendMessage").get(0).body().path("reply_parameters").path("message_id").asLong());
        }
    }
}
