package curxxed.dev.switchboard.dispatch;

import curxxed.dev.switchboard.gateway.AgentDirectory;
import curxxed.dev.switchboard.store.MessageLedger;
import curxxed.dev.switchboard.telegram.TelegramApi;
import curxxed.dev.switchboard.telegram.TelegramApi.TelegramException;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Posts through the agent bots and records everything posted in the ledger. Telegram never sends
 * a bot's own messages back as updates, so this is the only way agent replies reach the ledger.
 */
public final class TelegramOutbox implements Outbox {

    /** Telegram's limit is 4096 UTF-16 units; stay under it with margin. */
    static final int MAX_CHUNK = 4000;
    static final Duration MAX_RATE_LIMIT_WAIT = Duration.ofSeconds(30);

    private final Map<String, TelegramApi> bots;
    private final AgentDirectory directory;
    private final MessageLedger ledger;
    private final Clock clock;

    public TelegramOutbox(Map<String, TelegramApi> bots, AgentDirectory directory, MessageLedger ledger, Clock clock) {
        this.bots = bots;
        this.directory = directory;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Override
    public long postReply(String agentKey, long chatId, long replyTo, String text) throws Exception {
        long first = -1;
        long previous = replyTo;
        for (String chunk : split(text.isBlank() ? "(no reply text)" : text)) {
            long id = send(agentKey, chatId, previous, chunk);
            record(chatId, id, MessageLedger.SenderKind.AGENT, agentKey, previous, chunk);
            if (first < 0) first = id;
            previous = id; // thread the continuation under the previous chunk
        }
        return first;
    }

    @Override
    public void notice(String agentKey, long chatId, long replyTo, String text) throws Exception {
        long id = send(agentKey, chatId, replyTo, text);
        record(chatId, id, MessageLedger.SenderKind.SYSTEM, agentKey, replyTo, text);
    }

    private long send(String agentKey, long chatId, long replyTo, String text) throws IOException, InterruptedException {
        TelegramApi api = bots.get(agentKey);
        try {
            return api.sendMessage(chatId, text, replyTo);
        } catch (TelegramException e) {
            if (e.retryAfter.isEmpty() || Duration.ofSeconds(e.retryAfter.getAsLong()).compareTo(MAX_RATE_LIMIT_WAIT) > 0) {
                throw e;
            }
            Thread.sleep(Duration.ofSeconds(e.retryAfter.getAsLong()));
            return api.sendMessage(chatId, text, replyTo);
        }
    }

    private void record(long chatId, long messageId, MessageLedger.SenderKind kind, String agentKey, long replyTo,
                        String text) throws Exception {
        AgentDirectory.AgentBot bot = directory.byKey(agentKey).orElseThrow();
        String name = kind == MessageLedger.SenderKind.SYSTEM ? "relay" : bot.displayName();
        ledger.record(new MessageLedger.Entry(chatId, messageId, kind, bot.botUserId(), name,
                kind == MessageLedger.SenderKind.AGENT ? agentKey : null, replyTo, text, clock.instant()));
    }

    /** Splits at paragraph, then line, then word boundaries; hard-cuts only a single giant word. */
    static List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        String rest = text;
        while (rest.length() > MAX_CHUNK) {
            int cut = lastBreak(rest, "\n\n");
            if (cut < 0) cut = lastBreak(rest, "\n");
            if (cut < 0) cut = lastBreak(rest, " ");
            if (cut < 0) cut = MAX_CHUNK;
            chunks.add(rest.substring(0, cut).stripTrailing());
            rest = rest.substring(cut).stripLeading();
        }
        if (!rest.isEmpty()) chunks.add(rest);
        return chunks;
    }

    /** Last {@code separator} within the chunk limit, ignoring breaks in the first half. */
    private static int lastBreak(String text, String separator) {
        int at = text.lastIndexOf(separator, MAX_CHUNK);
        return at >= MAX_CHUNK / 2 ? at : -1;
    }
}
