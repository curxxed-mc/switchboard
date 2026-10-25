package curxxed.dev.switchboard.gateway;

/**
 * Someone tapped an inline button under one of the bots' messages.
 *
 * @param queryId   pass to {@code answerCallbackQuery} to stop the tapper's spinner
 * @param fromAdmin true only for the configured administrator
 * @param messageId the message the button sits under
 * @param data      the button's callback data
 */
public record ButtonPress(String queryId, long fromUserId, String fromName, boolean fromAdmin, long chatId,
                          long messageId, String data) {}
