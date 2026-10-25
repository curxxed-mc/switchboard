package curxxed.dev.switchboard.dispatch;

/**
 * One agent turn: what to work on and where its reply goes.
 *
 * @param depth              1 when a human asked; n + 1 when a depth-n agent's reply asked
 * @param triggerMessageId   the Telegram message the reply should answer
 * @param triggerText        the request: the human's message or the asking agent's reply
 * @param requestedBy        display name of whoever asked
 * @param requestedByAgent   agent key when another agent asked, else null
 */
public record Turn(String chainId, String agentKey, int depth, long chatId, long triggerMessageId,
                   String triggerText, String requestedBy, String requestedByAgent) {

    public boolean humanDirect() {
        return requestedByAgent == null;
    }
}
