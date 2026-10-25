package curxxed.dev.switchboard.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import curxxed.dev.switchboard.t3.T3Auth;
import curxxed.dev.switchboard.t3.T3Session;
import curxxed.dev.switchboard.telegram.FakeTelegram;
import curxxed.dev.switchboard.telegram.TelegramApi;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class CredentialWatchTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    @Test
    void quietUntilThreeDaysLeft() {
        assertTrue(CredentialWatch.warning(Optional.of(NOW.plus(Duration.ofDays(10))), NOW).isEmpty());
        assertTrue(CredentialWatch.warning(Optional.empty(), NOW).isEmpty(), "no expiry reported, nothing to warn about");
        String soon = CredentialWatch.warning(Optional.of(NOW.plus(Duration.ofDays(2))), NOW).orElseThrow();
        assertTrue(soon.startsWith("Switchboard's T3 credential expires in 2 days (2026-11-22)."), soon);
        assertTrue(soon.contains("t3 pair --label switchboard") && soon.endsWith("send /reauth here."), soon);
        assertTrue(CredentialWatch.warning(Optional.of(NOW.plus(Duration.ofHours(5))), NOW).orElseThrow()
                .contains("expires in 5 hours"));
        assertTrue(CredentialWatch.warning(Optional.of(NOW.minusSeconds(1)), NOW).orElseThrow()
                .contains("has expired, so agents can't run until it's renewed"));
    }

    @Test
    void warnsTheGroupAtMostOncePerDay() throws Exception {
        try (FakeTelegram telegram = new FakeTelegram(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            telegram.addBot(new FakeTelegram.Bot("101:claude", 101, "ClaudeRelayBot", true));
            TelegramApi bot = new TelegramApi(telegram.apiBase(), "101:claude", RelayApp.newHttpClient(executor), new ObjectMapper());
            Instant[] now = {NOW};
            Clock clock = new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return now[0]; }
            };
            T3Session session = new T3Session(null,
                    new T3Auth.Bearer("t", false, "s", NOW.plus(Duration.ofDays(2)).getEpochSecond()));
            CredentialWatch watch = new CredentialWatch(session, bot, -100L, clock);

            watch.check();
            watch.check();
            assertEquals(1, telegram.sent("sendMessage").size(), "repeat checks within a day stay quiet");
            now[0] = now[0].plus(Duration.ofHours(25));
            watch.check();
            assertEquals(2, telegram.sent("sendMessage").size(), "a day later it reminds again");
            assertEquals(-100L, telegram.sent("sendMessage").get(0).body().path("chat_id").asLong());
        }
    }
}
