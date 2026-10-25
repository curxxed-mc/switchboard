package curxxed.dev.switchboard.dispatch;

/** Posts to the group as a particular agent's bot. */
public interface Outbox {

    /**
     * Posts an agent's reply, splitting it if it's too long for one message, and records it in
     * the ledger as that agent's.
     *
     * @return the id of the first message posted
     */
    long postReply(String agentKey, long chatId, long replyTo, String text) throws Exception;

    /** Posts a relay notice, such as a stopped loop or a busy agent, through that agent's bot. */
    void notice(String agentKey, long chatId, long replyTo, String text) throws Exception;
}
