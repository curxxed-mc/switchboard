package curxxed.dev.switchboard.bridge;

import curxxed.dev.switchboard.app.Log;
import curxxed.dev.switchboard.dispatch.Turn;
import curxxed.dev.switchboard.telegram.TelegramApi;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Posts one status message per turn through the agent's own bot, edits it as the run progresses,
 * and deletes it when the run ends (the reply, or a failure notice, takes its place). Edits are
 * throttled (Telegram rate-limits edits per chat), and a failed edit never fails the turn. Status messages aren't recorded in the ledger: they're scaffolding, not conversation.
 */
public final class TelegramProgress implements ProgressReporter {

    static final Duration MIN_EDIT_INTERVAL = Duration.ofSeconds(3);

    private final Map<String, TelegramApi> bots;
    private final Clock clock;

    public TelegramProgress(Map<String, TelegramApi> bots, Clock clock) {
        this.bots = bots;
        this.clock = clock;
    }

    @Override
    public Progress start(Turn turn, String initialText) {
        TelegramApi api = bots.get(turn.agentKey());
        long messageId;
        try {
            messageId = api.sendMessage(turn.chatId(), initialText, turn.triggerMessageId());
        } catch (Exception e) {
            Log.warn("could not post progress for " + turn.agentKey() + ": " + e.getMessage());
            return NONE.start(turn, initialText);
        }
        return new Progress() {
            private String shown = initialText;
            private Instant lastEdit = clock.instant();

            @Override
            public void update(String text) {
                if (text.equals(shown) || Duration.between(lastEdit, clock.instant()).compareTo(MIN_EDIT_INTERVAL) < 0) return;
                edit(text);
            }

            @Override
            public void finish(String text) {
                if (!text.equals(shown)) edit(text);
            }

            @Override
            public void dismiss() {
                try {
                    api.deleteMessage(turn.chatId(), messageId);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    Log.warn("could not remove progress message for " + turn.agentKey() + ": " + e.getMessage());
                }
            }

            private void edit(String text) {
                try {
                    api.editMessageText(turn.chatId(), messageId, text);
                    shown = text;
                    lastEdit = clock.instant();
                } catch (TelegramApi.TelegramException e) {
                    // On 429, back off until Telegram allows edits again; the next update catches up.
                    if (e.retryAfter.isPresent()) lastEdit = clock.instant().plusSeconds(e.retryAfter.getAsLong());
                    else Log.warn("progress edit failed for " + turn.agentKey() + ": " + e.getMessage());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    Log.warn("progress edit failed for " + turn.agentKey() + ": " + e.getMessage());
                }
            }
        };
    }
}
