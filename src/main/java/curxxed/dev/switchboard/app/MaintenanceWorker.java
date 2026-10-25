package curxxed.dev.switchboard.app;

import curxxed.dev.switchboard.store.UpdateLedger;

import java.time.Clock;
import java.time.Duration;

/** Hourly housekeeping: prunes dedup rows once Telegram can no longer redeliver those updates. */
final class MaintenanceWorker implements Supervisor.Worker {

    /** Telegram drops undelivered updates after 24h; a week leaves a wide margin. */
    static final Duration DEDUP_RETENTION = Duration.ofDays(7);
    static final Duration INTERVAL = Duration.ofHours(1);

    private final UpdateLedger updates;
    private final Clock clock;

    MaintenanceWorker(UpdateLedger updates, Clock clock) {
        this.updates = updates;
        this.clock = clock;
    }

    @Override
    public void run() throws Exception {
        while (true) {
            int removed = updates.pruneBefore(clock.instant().minus(DEDUP_RETENTION));
            if (removed > 0) Log.info("pruned " + removed + " dedup rows older than " + DEDUP_RETENTION.toDays() + "d");
            Thread.sleep(INTERVAL);
        }
    }
}
