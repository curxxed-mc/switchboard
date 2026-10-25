package curxxed.dev.switchboard.app;

import curxxed.dev.switchboard.t3.T3Auth;
import curxxed.dev.switchboard.t3.T3Session;
import curxxed.dev.switchboard.telegram.TelegramApi;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Warns the group before the T3 credential runs out. T3 has no refresh token, so renewal needs the
 * admin: mint a pairing token on the PC, put it in config.json, then {@code /reauth}.
 */
final class CredentialWatch implements Supervisor.Worker {

    static final Duration WARN_BEFORE = Duration.ofDays(3);
    static final Duration REPEAT = Duration.ofDays(1);
    static final Duration CHECK = Duration.ofHours(1);

    private final T3Session session;
    private final TelegramApi bot;
    private final long groupId;
    private final Clock clock;
    private Instant lastWarned;

    CredentialWatch(T3Session session, TelegramApi bot, long groupId, Clock clock) {
        this.session = session;
        this.bot = bot;
        this.groupId = groupId;
        this.clock = clock;
    }

    @Override
    public void run() throws Exception {
        while (true) {
            check();
            Thread.sleep(CHECK);
        }
    }

    /** Posts a warning if one is due. */
    void check() throws InterruptedException {
        Optional<String> warning = warning(session.expiresAt(), clock.instant());
        if (warning.isEmpty()) {
            lastWarned = null; // renewed: the next expiry warns afresh
            return;
        }
        if (lastWarned != null && Duration.between(lastWarned, clock.instant()).compareTo(REPEAT) < 0) return;
        Log.warn(warning.get().lines().findFirst().orElse(""));
        try {
            bot.sendMessage(groupId, warning.get(), null);
            lastWarned = clock.instant();
        } catch (IOException e) {
            Log.warn("could not post the credential warning: " + e.getMessage());
        }
    }

    static Optional<String> warning(Optional<Instant> expiresAt, Instant now) {
        if (expiresAt.isEmpty()) return Optional.empty();
        Duration left = Duration.between(now, expiresAt.get());
        if (left.compareTo(WARN_BEFORE) > 0) return Optional.empty();
        String when = left.isNegative() || left.isZero()
                ? "has expired, so agents can't run until it's renewed"
                : "expires in " + (left.toHours() >= 24 ? left.toDays() + " day" + (left.toDays() == 1 ? "" : "s")
                                                        : Math.max(1, left.toHours()) + " hour" + (left.toHours() == 1 ? "" : "s"))
                  + " (" + expiresAt.get().toString().substring(0, 10) + ")";
        return Optional.of("Switchboard's T3 credential " + when + ".\nTo renew, run this on the PC:\n" + T3Auth.PAIR_COMMAND
                + "\nthen put the token in config.json as t3.pairingToken and send /reauth here.");
    }
}
