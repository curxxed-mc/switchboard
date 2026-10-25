package curxxed.dev.switchboard.gateway;

import curxxed.dev.switchboard.telegram.TelegramApi;

/** Handles inline-button taps, such as approval decisions. */
@FunctionalInterface
public interface ButtonHandler {

    /** @param via the bot that owns the tapped button, for answering the tap */
    void press(ButtonPress press, TelegramApi via) throws Exception;
}
