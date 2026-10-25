package curxxed.dev.switchboard.t3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * HTTP surface of a T3 Code 0.1.x environment server, as defined in
 * {@code packages/contracts/src/environmentHttp.ts} (tag v0.1.2). Writes are not available over
 * HTTP in protocol v2; they go through the {@code /ws} RPC socket.
 */
public final class T3Http {

    public static final int ORCHESTRATION_PROTOCOL_VERSION = 2;
    public static final String ORCHESTRATION_PROTOCOL_HEADER = "x-t3-orchestration-protocol";

    static final String GRANT_TYPE_TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
    static final String TOKEN_TYPE_ACCESS = "urn:ietf:params:oauth:token-type:access_token";
    static final String TOKEN_TYPE_ENVIRONMENT_BOOTSTRAP = "urn:t3:params:oauth:token-type:environment-bootstrap";

    public record AccessToken(String accessToken, String tokenType, long expiresInSeconds, String scope) {
        @Override
        public String toString() {
            return "AccessToken[type=" + tokenType + ", expiresIn=" + expiresInSeconds + "s, scope=" + scope + "]";
        }
    }

    public static final class T3HttpException extends IOException {
        public final int status;

        T3HttpException(String what, int status, String body) {
            super(what + " failed: HTTP " + status + " " + abbreviate(body));
            this.status = status;
        }
    }

    private final URI origin;
    private final HttpClient client;
    private final ObjectMapper mapper;

    public T3Http(URI origin, HttpClient client, ObjectMapper mapper) {
        this.origin = origin;
        this.client = client;
        this.mapper = mapper;
    }

    public URI origin() {
        return origin;
    }

    /** Public; works without credentials and identifies the environment and protocol version. */
    public JsonNode environmentDescriptor() throws IOException, InterruptedException {
        return getJson("environment descriptor", "/.well-known/t3/environment", null, false);
    }

    public JsonNode authSession(String bearer) throws IOException, InterruptedException {
        return getJson("auth session", "/api/auth/session", bearer, false);
    }

    /**
     * Exchanges a {@code t3 pair} one-time credential for a scoped bearer token (OAuth token
     * exchange, form-encoded). The pairing credential is consumed by this call.
     */
    public AccessToken exchangePairingToken(String pairingToken, String scope, String clientLabel)
            throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", GRANT_TYPE_TOKEN_EXCHANGE);
        form.put("subject_token", pairingToken);
        form.put("subject_token_type", TOKEN_TYPE_ENVIRONMENT_BOOTSTRAP);
        form.put("requested_token_type", TOKEN_TYPE_ACCESS);
        if (scope != null) form.put("scope", scope);
        form.put("client_label", clientLabel);

        HttpRequest request = HttpRequest.newBuilder(origin.resolve("/oauth/token"))
                .timeout(Duration.ofSeconds(10))
                .header("content-type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new T3HttpException("pairing token exchange", response.statusCode(), response.body());
        }
        JsonNode body = mapper.readTree(response.body());
        return new AccessToken(body.path("access_token").asText(), body.path("token_type").asText(),
                body.path("expires_in").asLong(), body.path("scope").asText());
    }

    /** Short-lived ticket for the {@code wsTicket} query parameter, so the bearer stays out of URLs. */
    public String webSocketTicket(String bearer) throws IOException, InterruptedException {
        HttpRequest request = authorized(HttpRequest.newBuilder(origin.resolve("/api/auth/websocket-ticket")), bearer)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new T3HttpException("websocket ticket", response.statusCode(), response.body());
        }
        return mapper.readTree(response.body()).path("ticket").asText();
    }

    public JsonNode projects(String bearer) throws IOException, InterruptedException {
        return getJson("projects snapshot", "/api/projects", bearer, false);
    }

    public JsonNode shellSnapshot(String bearer) throws IOException, InterruptedException {
        return getJson("shell snapshot", "/api/orchestration/shell", bearer, true);
    }

    public JsonNode threadSnapshot(String bearer, String threadId) throws IOException, InterruptedException {
        String path = "/api/orchestration/threads/" + URLEncoder.encode(threadId, StandardCharsets.UTF_8);
        return getJson("thread snapshot", path, bearer, true);
    }

    public URI webSocketUri(String ticket) {
        String scheme = "https".equals(origin.getScheme()) ? "wss" : "ws";
        String query = "wsTicket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8)
                + "&orchestrationProtocol=" + ORCHESTRATION_PROTOCOL_VERSION;
        return URI.create(scheme + "://" + origin.getRawAuthority() + "/ws?" + query);
    }

    private JsonNode getJson(String what, String path, String bearer, boolean orchestration)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(origin.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("accept", "application/json")
                .GET();
        if (orchestration) {
            builder.header(ORCHESTRATION_PROTOCOL_HEADER, String.valueOf(ORCHESTRATION_PROTOCOL_VERSION));
        }
        HttpResponse<String> response = client.send(authorized(builder, bearer).build(),
                HttpResponse.BodyHandlers.ofString());
        String contentType = response.headers().firstValue("content-type").orElse("");
        // Unknown routes fall through to the web app's index.html with 200, so status alone proves nothing.
        if (response.statusCode() != 200 || !contentType.contains("json")) {
            throw new T3HttpException(what + " (" + path + ")", response.statusCode(), response.body());
        }
        return mapper.readTree(response.body());
    }

    private static HttpRequest.Builder authorized(HttpRequest.Builder builder, String bearer) {
        return bearer == null ? builder : builder.header("authorization", "Bearer " + bearer);
    }

    static String formEncode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    static String abbreviate(String s) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }
}
