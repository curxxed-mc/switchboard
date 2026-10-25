package curxxed.dev.switchboard.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;

/**
 * Minimal Telegram Bot API client for one bot. The token is part of every request URL, so neither
 * the URL nor the token ever appears in exceptions or logs.
 */
public final class TelegramApi {

    /** Long-poll window. The HTTP timeout must exceed it. */
    public static final int POLL_TIMEOUT_SECONDS = 50;

    public record BotUser(long id, String username, String firstName, boolean canReadAllGroupMessages) {}

    public static final class TelegramException extends IOException {
        public final int errorCode;
        /** Seconds to wait, for 429 Too Many Requests. */
        public final OptionalLong retryAfter;
        /** Set when a group was upgraded to a supergroup and has a new id. */
        public final OptionalLong migrateToChatId;

        TelegramException(String method, int errorCode, String description, OptionalLong retryAfter,
                          OptionalLong migrateToChatId) {
            super(method + " failed: " + errorCode + " " + description);
            this.errorCode = errorCode;
            this.retryAfter = retryAfter;
            this.migrateToChatId = migrateToChatId;
        }
    }

    private final URI base;
    private final HttpClient http;
    private final ObjectMapper mapper;

    /** @param apiBase e.g. {@code https://api.telegram.org} */
    public TelegramApi(String apiBase, String botToken, HttpClient http, ObjectMapper mapper) {
        this.base = URI.create(apiBase.replaceAll("/+$", "") + "/bot" + botToken + "/");
        this.http = http;
        this.mapper = mapper;
    }

    public BotUser getMe() throws IOException, InterruptedException {
        JsonNode r = call("getMe", mapper.createObjectNode(), Duration.ofSeconds(15));
        return new BotUser(r.path("id").asLong(), r.path("username").asText(), r.path("first_name").asText(),
                r.path("can_read_all_group_messages").asBoolean(false));
    }

    /**
     * Long-polls for updates. Passing {@code offset} confirms every update below it, and Telegram
     * won't send those again.
     */
    public JsonNode getUpdates(OptionalLong offset, int timeoutSeconds, List<String> allowedUpdates, Integer limit)
            throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        offset.ifPresent(o -> body.put("offset", o));
        body.put("timeout", timeoutSeconds);
        if (limit != null) body.put("limit", limit);
        body.set("allowed_updates", mapper.valueToTree(allowedUpdates));
        return call("getUpdates", body, Duration.ofSeconds(timeoutSeconds + 15L));
    }

    /** @return the sent message's id */
    public long sendMessage(long chatId, String text, Long replyToMessageId) throws IOException, InterruptedException {
        return sendMessage(chatId, text, replyToMessageId, null);
    }

    /** One inline button: its label and the data Telegram sends back when it's tapped (at most 64 bytes). */
    public record Button(String text, String callbackData) {}

    /**
     * @param buttons rows of inline buttons under the message, or null for none
     * @return the sent message's id
     */
    public long sendMessage(long chatId, String text, Long replyToMessageId, List<List<Button>> buttons)
            throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        if (buttons != null) body.set("reply_markup", keyboard(buttons));
        body.put("chat_id", chatId);
        body.put("text", text);
        if (replyToMessageId != null) {
            body.putObject("reply_parameters").put("message_id", replyToMessageId).put("allow_sending_without_reply", true);
        }
        body.putObject("link_preview_options").put("is_disabled", true);
        return call("sendMessage", body, Duration.ofSeconds(20)).path("message_id").asLong();
    }

    /** Replaces the text. Any inline buttons are removed, since no {@code reply_markup} is sent. */
    public void editMessageText(long chatId, long messageId, String text) throws IOException, InterruptedException {
        editMessageText(chatId, messageId, text, null);
    }

    /** Replaces the text and the buttons; null {@code buttons} removes them. */
    public void editMessageText(long chatId, long messageId, String text, List<List<Button>> buttons)
            throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        if (buttons != null) body.set("reply_markup", keyboard(buttons));
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", text);
        body.putObject("link_preview_options").put("is_disabled", true);
        try {
            call("editMessageText", body, Duration.ofSeconds(20));
        } catch (TelegramException e) {
            // Re-sending identical text is harmless; Telegram reports it as an error anyway.
            if (!(e.errorCode == 400 && e.getMessage().contains("message is not modified"))) throw e;
        }
    }

    private ObjectNode keyboard(List<List<Button>> buttons) {
        ObjectNode markup = mapper.createObjectNode();
        var rows = markup.putArray("inline_keyboard");
        for (List<Button> row : buttons) {
            var r = rows.addArray();
            for (Button b : row) r.addObject().put("text", b.text()).put("callback_data", b.callbackData());
        }
        return markup;
    }

    /**
     * Acknowledges a button tap; Telegram shows the tapper a spinner until this is called.
     *
     * @param alert true for a dialog the tapper must dismiss, false for a brief toast
     */
    public void answerCallbackQuery(String callbackQueryId, String text, boolean alert) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        body.put("callback_query_id", callbackQueryId);
        if (text != null) body.put("text", text);
        body.put("show_alert", alert);
        call("answerCallbackQuery", body, Duration.ofSeconds(15));
    }

    /** Bots may delete their own messages in groups for 48 hours. */
    public void deleteMessage(long chatId, long messageId) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        call("deleteMessage", body, Duration.ofSeconds(20));
    }

    private JsonNode call(String method, ObjectNode body, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(method))
                .timeout(timeout)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // HttpClient exceptions can echo the request URI, which contains the token.
            throw new IOException(method + " transport error: " + e.getClass().getSimpleName());
        }
        JsonNode json;
        try {
            json = mapper.readTree(response.body());
        } catch (IOException e) {
            throw new TelegramException(method, response.statusCode(), "non-JSON response", OptionalLong.empty(),
                    OptionalLong.empty());
        }
        if (!json.path("ok").asBoolean(false)) {
            JsonNode params = json.path("parameters");
            throw new TelegramException(method, json.path("error_code").asInt(response.statusCode()),
                    json.path("description").asText("unknown error"),
                    params.has("retry_after") ? OptionalLong.of(params.get("retry_after").asLong()) : OptionalLong.empty(),
                    params.has("migrate_to_chat_id") ? OptionalLong.of(params.get("migrate_to_chat_id").asLong())
                            : OptionalLong.empty());
        }
        return json.path("result");
    }
}
