package curxxed.dev.switchboard.t3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Client for T3's {@code /ws} Effect RPC socket (JSON serialization). Wire format, verified live
 * against T3 0.1.2:
 * <ul>
 *   <li>request: {@code {"_tag":"Request","id":"7","tag":"<method>","payload":{…},"headers":[]}}</li>
 *   <li>stream values: {@code {"_tag":"Chunk","requestId":"7","values":[…]}}, each answered with
 *       {@code {"_tag":"Ack","requestId":"7"}} to receive more</li>
 *   <li>end: {@code {"_tag":"Exit","requestId":"7","exit":{"_tag":"Success","value":…}}} or a
 *       {@code Failure} with a {@code cause} list</li>
 *   <li>cancel: {@code {"_tag":"Interrupt","requestId":"7"}}</li>
 * </ul>
 * One socket is shared by every caller. If it drops, everything in flight fails and the next call
 * reconnects with a fresh ticket.
 */
public final class T3RpcClient implements AutoCloseable {

    /** A failed RPC, carrying the server's error tag and message. */
    public static final class T3RpcException extends IOException {
        public final String errorTag;

        public T3RpcException(String method, String errorTag, String message) {
            super(method + " failed: " + (errorTag == null ? "" : errorTag + ": ") + message);
            this.errorTag = errorTag;
        }
    }

    /** A live stream subscription. Closing it interrupts the stream on the server. */
    public interface Subscription extends AutoCloseable {
        /** Completes when the stream ends: normally, with an error, or when the socket drops. */
        CompletableFuture<Void> done();

        @Override
        void close();
    }

    private record Pending(String method, CompletableFuture<JsonNode> result, Consumer<JsonNode> onValue,
                           long connection) {}

    /** One opened socket. The number lets a late close event fail only that socket's requests. */
    private record Connection(WebSocket ws, long number) {
        boolean open() {
            return !ws.isInputClosed() && !ws.isOutputClosed();
        }
    }

    private final T3Http http;
    private final Supplier<String> bearer;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final ReentrantLock connectLock = new ReentrantLock();
    private final ReentrantLock sendLock = new ReentrantLock();
    private final AtomicLong connections = new AtomicLong();
    private volatile Connection connection;
    private volatile boolean closed;

    /** @param bearer read on every (re)connect, so a {@code /reauth} applies to the next socket */
    public T3RpcClient(T3Http http, Supplier<String> bearer, HttpClient client, ObjectMapper mapper) {
        this.http = http;
        this.bearer = bearer;
        this.client = client;
        this.mapper = mapper;
    }

    /** A request with a single result. */
    public JsonNode call(String method, JsonNode payload, Duration timeout) throws IOException, InterruptedException {
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        String id = send(method, payload, result, null);
        try {
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            interrupt(id);
            throw new IOException(method + " timed out after " + timeout.toSeconds() + "s");
        } catch (ExecutionException e) {
            throw unwrap(e);
        } catch (InterruptedException e) {
            interrupt(id);
            throw e;
        }
    }

    /**
     * A streaming request. {@code onValue} runs on the socket's listener thread, once per value,
     * in order, so it must be quick (e.g. hand off to a queue).
     */
    public Subscription subscribe(String method, JsonNode payload, Consumer<JsonNode> onValue)
            throws IOException, InterruptedException {
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        String id = send(method, payload, result, onValue);
        CompletableFuture<Void> done = result.thenApply(v -> (Void) null);
        return new Subscription() {
            @Override
            public CompletableFuture<Void> done() {
                return done;
            }

            @Override
            public void close() {
                if (!result.isDone()) interrupt(id);
                pending.remove(id);
            }
        };
    }

    private String send(String method, JsonNode payload, CompletableFuture<JsonNode> result,
                        Consumer<JsonNode> onValue) throws IOException, InterruptedException {
        String id = Long.toString(nextId.getAndIncrement());
        ObjectNode frame = mapper.createObjectNode();
        frame.put("_tag", "Request");
        frame.put("id", id);
        frame.put("tag", method);
        frame.set("payload", payload);
        frame.putArray("headers");
        Connection conn = connected();
        pending.put(id, new Pending(method, result, onValue, conn.number()));
        try {
            sendFrame(conn, frame);
        } catch (IOException | RuntimeException e) {
            pending.remove(id);
            throw e;
        }
        return id;
    }

    private void interrupt(String id) {
        ObjectNode frame = mapper.createObjectNode().put("_tag", "Interrupt").put("requestId", id);
        try {
            sendFrame(connected(), frame);
        } catch (IOException | InterruptedException | RuntimeException ignored) {
            // The socket is gone, so the server has dropped the request anyway.
        }
    }

    private void sendFrame(Connection conn, ObjectNode frame) throws IOException, InterruptedException {
        String text = mapper.writeValueAsString(frame);
        sendLock.lock(); // java.net.http.WebSocket allows one outstanding send at a time
        try {
            conn.ws().sendText(text, true).get(10, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("T3 socket send failed: " + e.getMessage());
        } finally {
            sendLock.unlock();
        }
    }

    private Connection connected() throws IOException, InterruptedException {
        if (closed) throw new IOException("T3 RPC client is closed");
        Connection conn = connection;
        if (conn != null && conn.open()) return conn;
        connectLock.lock();
        try {
            conn = connection;
            if (conn != null && conn.open()) return conn;
            String ticket;
            try {
                ticket = http.webSocketTicket(bearer.get());
            } catch (T3Http.T3HttpException e) {
                if (e.status == 401) {
                    throw new IOException("T3 rejected Switchboard's credential (expired or revoked). Pair again with "
                            + "t3 pair, put the token in config.json as t3.pairingToken, then send /reauth");
                }
                throw e;
            }
            long number = connections.incrementAndGet();
            WebSocket ws;
            try {
                ws = client.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(http.webSocketUri(ticket), new Listener(number))
                        .get(15, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                throw new IOException("could not open T3 socket: " + e.getMessage());
            }
            conn = new Connection(ws, number);
            connection = conn;
            return conn;
        } finally {
            connectLock.unlock();
        }
    }

    private void onFrame(Connection conn, JsonNode frame) {
        String tag = frame.path("_tag").asText();
        String requestId = frame.path("requestId").asText(null);
        Pending entry = requestId == null ? null : pending.get(requestId);
        switch (tag) {
            case "Chunk" -> {
                if (entry == null) return;
                if (entry.onValue() != null) {
                    for (JsonNode value : frame.path("values")) {
                        try {
                            entry.onValue().accept(value);
                        } catch (RuntimeException e) {
                            entry.result().completeExceptionally(e);
                        }
                    }
                }
                ObjectNode ack = mapper.createObjectNode().put("_tag", "Ack").put("requestId", requestId);
                Thread.startVirtualThread(() -> {
                    try {
                        sendFrame(conn, ack);
                    } catch (IOException | InterruptedException ignored) {
                        // Connection loss is reported through the close handler.
                    }
                });
            }
            case "Exit" -> {
                if (entry == null) return;
                pending.remove(requestId);
                JsonNode exit = frame.path("exit");
                if ("Success".equals(exit.path("_tag").asText())) {
                    entry.result().complete(exit.path("value"));
                } else {
                    entry.result().completeExceptionally(failure(entry.method(), exit.path("cause")));
                }
            }
            default -> { } // Pong, ClientProtocolError and future additions
        }
    }

    /** Turns an Effect {@code Cause} (a list of Fail, Die and Interrupt entries) into an exception. */
    T3RpcException failure(String method, JsonNode cause) {
        JsonNode first = null;
        for (JsonNode c : cause.isArray() ? cause : mapper.createArrayNode().add(cause)) {
            if ("Fail".equals(c.path("_tag").asText())) {
                JsonNode error = c.path("error");
                return new T3RpcException(method, error.path("_tag").asText(null),
                        error.path("message").asText(error.toString()));
            }
            if (first == null) first = c;
        }
        if (first == null) return new T3RpcException(method, null, "unknown failure");
        String tag = first.path("_tag").asText();
        return new T3RpcException(method, tag, tag.equals("Interrupt") ? "interrupted"
                : first.path("defect").asText(first.toString()));
    }

    /** Fails requests sent on connection {@code number}, or all of them when it's null. */
    private void fail(Long number, String why) {
        IOException e = new IOException("T3 socket closed: " + why);
        pending.entrySet().removeIf(entry -> {
            if (number != null && entry.getValue().connection() != number) return false;
            entry.getValue().result().completeExceptionally(e);
            return true;
        });
    }

    private static IOException unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        return cause instanceof IOException io ? io : new IOException(cause);
    }

    @Override
    public void close() {
        closed = true;
        Connection conn = connection;
        if (conn != null) conn.ws().abort();
        fail(null, "client closed");
    }

    private final class Listener implements WebSocket.Listener {
        private final long number;
        private final StringBuilder partial = new StringBuilder();

        Listener(long number) {
            this.number = number;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                try {
                    onFrame(new Connection(webSocket, number), mapper.readTree(text));
                } catch (IOException ignored) {
                    // Not JSON: nothing we can route it to.
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            fail(number, statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail(number, String.valueOf(error.getMessage()));
        }
    }
}
