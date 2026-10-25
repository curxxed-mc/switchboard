package curxxed.dev.switchboard.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import curxxed.dev.switchboard.app.Log;
import curxxed.dev.switchboard.app.Supervisor;
import curxxed.dev.switchboard.store.UpdateLedger;
import curxxed.dev.switchboard.telegram.TelegramApi;
import curxxed.dev.switchboard.telegram.TelegramApi.TelegramException;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.OptionalLong;

/**
 * One bot's long-polling loop. Expected Telegram failures (rate limits, conflicts, network drops)
 * are handled here with backoff. Only a rejected token ends the worker.
 */
public final class BotInstance implements Supervisor.Worker {

    static final Duration NETWORK_BACKOFF = Duration.ofSeconds(5);
    static final Duration CONFLICT_BACKOFF = Duration.ofSeconds(30);

    private final String agentKey;
    private final TelegramApi api;
    private final UpdateLedger updates;
    private final Gateway gateway;
    private final BotHealth health;
    private final int pollTimeoutSeconds;

    public BotInstance(String agentKey, TelegramApi api, UpdateLedger updates, Gateway gateway, BotHealth health) {
        this(agentKey, api, updates, gateway, health, TelegramApi.POLL_TIMEOUT_SECONDS);
    }

    BotInstance(String agentKey, TelegramApi api, UpdateLedger updates, Gateway gateway, BotHealth health,
                int pollTimeoutSeconds) {
        this.agentKey = agentKey;
        this.api = api;
        this.updates = updates;
        this.gateway = gateway;
        this.health = health;
        this.pollTimeoutSeconds = pollTimeoutSeconds;
    }

    public String agentKey() {
        return agentKey;
    }

    @Override
    public void run() throws Exception {
        OptionalLong offset = updates.nextOffset(agentKey);
        boolean firstStart = offset.isEmpty();
        Log.info(agentKey + " polling" + (offset.isPresent() ? " from update " + offset.getAsLong() : " (first start)"));
        while (true) {
            JsonNode batch;
            try {
                if (firstStart) {
                    offset = skipBacklog();
                    firstStart = false;
                }
                batch = api.getUpdates(offset, pollTimeoutSeconds, Gateway.ALLOWED_UPDATES, 100);
                health.polled(agentKey);
            } catch (TelegramException e) {
                if (e.errorCode == 401 || e.errorCode == 404) {
                    Log.warn(agentKey + " bot token rejected by Telegram (" + e.getMessage() + "); this bot stays offline");
                    health.failed(agentKey, "token rejected by Telegram", true);
                    return;
                }
                health.failed(agentKey, e.getMessage(), false);
                Duration wait = backoffFor(e);
                Log.warn(agentKey + " " + e.getMessage() + "; retrying in " + wait.toSeconds() + "s");
                Thread.sleep(wait);
                continue;
            } catch (IOException e) {
                health.failed(agentKey, e.getMessage(), false);
                Log.warn(agentKey + " " + e.getMessage() + "; retrying in " + NETWORK_BACKOFF.toSeconds() + "s");
                Thread.sleep(NETWORK_BACKOFF);
                continue;
            }
            for (JsonNode update : batch) {
                gateway.process(agentKey, api, update);
                long next = update.path("update_id").asLong() + 1;
                updates.advanceOffset(agentKey, next);
                offset = OptionalLong.of(next);
            }
        }
    }

    /**
     * On a bot's very first start, whatever Telegram has queued predates the relay. Confirm it
     * unseen rather than letting old messages start agent runs.
     */
    private OptionalLong skipBacklog() throws IOException, InterruptedException, SQLException {
        JsonNode last = api.getUpdates(OptionalLong.of(-1), 0, Gateway.ALLOWED_UPDATES, 1);
        if (last.isEmpty()) return OptionalLong.empty();
        long next = last.get(last.size() - 1).path("update_id").asLong() + 1;
        updates.advanceOffset(agentKey, next);
        Log.info(agentKey + " first start: skipped queued updates before " + next);
        return OptionalLong.of(next);
    }

    static Duration backoffFor(TelegramException e) {
        if (e.retryAfter.isPresent()) return Duration.ofSeconds(Math.max(1, e.retryAfter.getAsLong()));
        // 409: a webhook is set, or another process is polling with this token.
        if (e.errorCode == 409) return CONFLICT_BACKOFF;
        return NETWORK_BACKOFF;
    }
}
