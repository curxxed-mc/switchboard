package curxxed.dev.switchboard.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import curxxed.dev.switchboard.app.Log;
import curxxed.dev.switchboard.config.AppConfig;
import curxxed.dev.switchboard.store.MessageLedger;
import curxxed.dev.switchboard.store.UpdateLedger;
import curxxed.dev.switchboard.store.UpdateLedger.Claim;
import curxxed.dev.switchboard.store.UpdateLedger.UpdateRef;
import curxxed.dev.switchboard.telegram.TelegramApi;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The pipeline every bot's updates go through, in order:
 * <ol>
 *   <li>only the configured group is accepted</li>
 *   <li>per-bot and cross-bot deduplication</li>
 *   <li>recording to the message ledger</li>
 *   <li>dropping stale backlog, foreign-bot commands, and admin commands from non-admins</li>
 *   <li>handing off to the {@link EnvelopeHandler}</li>
 * </ol>
 */
public final class Gateway {

    /**
     * Update types requested from Telegram. Edits are left out: an edit must not re-run an agent.
     * {@code callback_query} is a button tap (approvals); it goes only to the bot that owns the button.
     */
    public static final List<String> ALLOWED_UPDATES = List.of("message", "my_chat_member", "callback_query");

    private final AppConfig.Telegram config;
    private final UpdateLedger updates;
    private final MessageLedger ledger;
    private final AgentDirectory directory;
    private final EnvelopeFactory envelopes;
    private final EnvelopeHandler handler;
    private final Clock clock;
    /** Foreign chats already warned about, so a spammy chat doesn't flood the log. */
    private final Set<Long> warnedChats = ConcurrentHashMap.newKeySet();
    private final Set<Long> warnedMigrations = ConcurrentHashMap.newKeySet();

    public Gateway(AppConfig.Telegram config, UpdateLedger updates, MessageLedger ledger, AgentDirectory directory,
                   EnvelopeHandler handler, Clock clock) {
        this.config = config;
        this.updates = updates;
        this.ledger = ledger;
        this.directory = directory;
        this.envelopes = new EnvelopeFactory(directory, config.adminUserId());
        this.handler = handler;
        this.clock = clock;
    }

    public void process(String botKey, TelegramApi api, JsonNode update) throws SQLException, InterruptedException {
        long updateId = update.path("update_id").asLong();
        if (update.has("my_chat_member")) {
            if (updates.claim(UpdateRef.withoutMessage(botKey, updateId, "my_chat_member")) == Claim.FIRST) {
                logMembership(botKey, update.get("my_chat_member"));
            }
            return;
        }
        if (update.has("callback_query")) {
            if (updates.claim(UpdateRef.withoutMessage(botKey, updateId, "callback_query")) == Claim.FIRST) {
                button(botKey, api, update.get("callback_query"));
            }
            return;
        }
        JsonNode message = update.path("message");
        if (message.isMissingNode()) {
            updates.claim(UpdateRef.withoutMessage(botKey, updateId, "other"));
            return;
        }
        long chatId = message.path("chat").path("id").asLong();
        if (chatId != config.groupId()) {
            updates.claim(UpdateRef.withoutMessage(botKey, updateId, "foreign_chat"));
            if (warnedChats.add(chatId)) {
                Log.warn(botKey + " ignoring chat " + chatId + " (" + message.path("chat").path("type").asText()
                        + "); only group " + config.groupId() + " is allowed");
            }
            return;
        }
        if (message.has("migrate_to_chat_id")) {
            // Every bot gets this service message; warn once and skip the basic-group warning below.
            updates.claim(UpdateRef.withoutMessage(botKey, updateId, "migration"));
            if (warnedMigrations.add(chatId)) {
                Log.warn("group " + chatId + " was upgraded to supergroup " + message.get("migrate_to_chat_id").asLong()
                        + "; set telegram.groupId to the new id and restart");
            }
            return;
        }
        if ("group".equals(message.path("chat").path("type").asText())) {
            // Basic groups number messages separately for each bot, so one message arrives with three
            // unrelated ids: cross-bot dedup, replies and ordering all break. Only supergroups share ids.
            updates.claim(UpdateRef.withoutMessage(botKey, updateId, "basic_group"));
            if (warnedChats.add(chatId)) {
                Log.warn("chat " + chatId + " is a basic group, where each bot sees different message ids, so the"
                        + " relay cannot work there. Upgrade it to a supergroup (e.g. group settings -> Chat history"
                        + " for new members -> Visible), then set telegram.groupId to the new -100... id");
            }
            return;
        }
        long messageId = message.path("message_id").asLong();
        if (updates.claim(UpdateRef.ofMessage(botKey, updateId, chatId, messageId, "message")) != Claim.FIRST) return;

        MessageEnvelope envelope = envelopes.fromMessage(message, botKey);
        ledger.record(new MessageLedger.Entry(chatId, messageId,
                envelope.sender().agentKey() != null ? MessageLedger.SenderKind.AGENT : MessageLedger.SenderKind.HUMAN,
                envelope.sender().userId(), envelope.sender().displayName(), envelope.sender().agentKey(),
                envelope.replyTo() == null ? null : envelope.replyTo().messageId(), envelope.text(), envelope.sentAt()));

        Duration age = Duration.between(envelope.sentAt(), clock.instant());
        if (age.compareTo(config.staleAfter()) > 0) {
            Log.info(botKey + " recorded stale message " + messageId + " (" + age.toSeconds() + "s old) without acting on it");
            return;
        }
        Command command = envelope.command();
        if (command != null && command.addressedTo() != null && directory.resolveUsername(command.addressedTo()).isEmpty()) {
            return; // /cmd@SomeOtherBot
        }
        if (command != null && command.adminOnly() && !envelope.sender().isAdmin()) {
            reply(api, envelope, "Only the relay administrator can use /" + command.name() + ".");
            return;
        }
        try {
            handler.handle(envelope, api);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            Log.warn("handler failed for message " + messageId + ": " + e);
        }
    }

    private void button(String botKey, TelegramApi api, JsonNode query) throws InterruptedException {
        JsonNode from = query.path("from");
        long chatId = query.path("message").path("chat").path("id").asLong();
        ButtonPress press = new ButtonPress(query.path("id").asText(), from.path("id").asLong(),
                (from.path("first_name").asText("") + " " + from.path("last_name").asText("")).strip(),
                from.path("id").asLong() == config.adminUserId(), chatId,
                query.path("message").path("message_id").asLong(), query.path("data").asText(""));
        try {
            if (chatId != config.groupId()) {
                api.answerCallbackQuery(press.queryId(), null, false);
                return;
            }
            handler.handleButton(press, api);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            Log.warn(botKey + " button handling failed: " + e);
        }
    }

    private void logMembership(String botKey, JsonNode change) {
        long chatId = change.path("chat").path("id").asLong();
        String status = change.path("new_chat_member").path("status").asText();
        String where = chatId == config.groupId() ? "the relay group" : "foreign chat " + chatId;
        Log.info(botKey + " is now '" + status + "' in " + where);
    }

    static void reply(TelegramApi api, MessageEnvelope to, String text) throws InterruptedException {
        try {
            api.sendMessage(to.chatId(), text, to.messageId());
        } catch (IOException e) {
            Log.warn("could not reply in chat " + to.chatId() + ": " + e.getMessage());
        }
    }
}
