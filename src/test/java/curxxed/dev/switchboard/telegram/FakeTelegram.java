package curxxed.dev.switchboard.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A stand-in Bot API server for tests. It keeps one update queue per bot token and honours
 * {@code getUpdates} offset semantics, including {@code offset=-1}, which forgets older updates.
 */
public final class FakeTelegram implements AutoCloseable {

    public record Bot(String token, long id, String username, boolean canReadAll) {}

    public record Sent(String token, String method, JsonNode body) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final Map<String, Bot> bots = new ConcurrentHashMap<>();
    private final Map<String, List<JsonNode>> queues = new ConcurrentHashMap<>();
    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final AtomicLong nextMessageId = new AtomicLong(9000);

    public FakeTelegram() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public String apiBase() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void addBot(Bot bot) {
        bots.put(bot.token(), bot);
        queues.put(bot.token(), new CopyOnWriteArrayList<>());
    }

    /** Queues an update for one bot. */
    public void deliver(String token, long updateId, JsonNode payload) {
        ObjectNode update = ((ObjectNode) payload.deepCopy()).put("update_id", updateId);
        queues.get(token).add(update);
    }

    public List<Sent> sent() {
        return sent;
    }

    public List<Sent> sent(String method) {
        return sent.stream().filter(s -> s.method().equals(method)).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        // Path: /bot<token>/<method>
        String[] parts = exchange.getRequestURI().getPath().split("/");
        String token = parts.length > 1 && parts[1].startsWith("bot") ? parts[1].substring(3) : "";
        String method = parts.length > 2 ? parts[2] : "";
        JsonNode body = mapper.readTree(exchange.getRequestBody().readAllBytes());
        Bot bot = bots.get(token);
        ObjectNode reply = mapper.createObjectNode();
        if (bot == null) {
            reply.put("ok", false).put("error_code", 401).put("description", "Unauthorized");
            respond(exchange, 401, reply);
            return;
        }
        reply.put("ok", true);
        switch (method) {
            case "getMe" -> reply.putObject("result").put("id", bot.id()).put("is_bot", true)
                    .put("first_name", bot.username()).put("username", bot.username())
                    .put("can_read_all_group_messages", bot.canReadAll());
            case "getUpdates" -> reply.set("result", updates(token, body));
            case "answerCallbackQuery" -> {
                sent.add(new Sent(token, method, body));
                reply.put("result", true);
            }
            case "deleteMessage" -> {
                sent.add(new Sent(token, method, body));
                reply.put("result", true);
            }
            case "sendMessage", "editMessageText" -> {
                sent.add(new Sent(token, method, body));
                reply.putObject("result").put("message_id", nextMessageId.incrementAndGet());
            }
            default -> {
                reply.put("ok", false).put("error_code", 404).put("description", "Not Found");
                respond(exchange, 404, reply);
                return;
            }
        }
        respond(exchange, 200, reply);
    }

    private ArrayNode updates(String token, JsonNode body) {
        List<JsonNode> queue = queues.get(token);
        long offset = body.path("offset").asLong(0);
        if (offset < 0) {
            // Bot API: a negative offset returns the last |offset| updates and forgets the rest.
            int keep = (int) Math.min(-offset, queue.size());
            List<JsonNode> tail = new ArrayList<>(queue.subList(queue.size() - keep, queue.size()));
            queue.clear();
            queue.addAll(tail);
            return mapper.valueToTree(tail);
        }
        queue.removeIf(u -> u.path("update_id").asLong() < offset);
        long deadline = System.currentTimeMillis() + Math.min(body.path("timeout").asLong(0), 1) * 1000;
        while (queue.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int limit = body.path("limit").asInt(100);
        return mapper.valueToTree(queue.stream().limit(limit).toList());
    }

    private void respond(HttpExchange exchange, int status, JsonNode reply) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(reply);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
