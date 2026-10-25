package curxxed.dev.switchboard.gateway;

import curxxed.dev.switchboard.telegram.TelegramApi;

/**
 * Receives each accepted group message once, after deduplication, validation and ledger recording.
 * Called on the receiving bot's polling thread, so slow work belongs on another thread.
 */
@FunctionalInterface
public interface EnvelopeHandler {

    /** @param via the bot that received the message, for immediate replies such as command errors */
    void handle(MessageEnvelope envelope, TelegramApi via) throws Exception;

    /** A button tap in the group. The default just stops the tapper's spinner. */
    default void handleButton(ButtonPress press, TelegramApi via) throws Exception {
        via.answerCallbackQuery(press.queryId(), null, false);
    }
}
