package curxxed.dev.switchboard.t3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Phase 1 verification: walks every protocol step the relay depends on against the live T3 server
 * and reports what actually answered. Read-only apart from the one-time pairing exchange.
 */
public final class T3Probe {

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final PrintStream out;

    public T3Probe(HttpClient client, ObjectMapper mapper, PrintStream out) {
        this.client = client;
        this.mapper = mapper;
        this.out = out;
    }

    /** @return true when every step passed */
    public boolean run(String configuredBaseUrl, String pairingToken, Path stateDir)
            throws IOException, InterruptedException {
        URI origin = resolveOrigin(configuredBaseUrl);
        if (origin == null) return false;
        T3Http http = new T3Http(origin, client, mapper);

        JsonNode descriptor = http.environmentDescriptor();
        int protocol = descriptor.path("orchestrationProtocolVersion").asInt(-1);
        step(protocol == T3Http.ORCHESTRATION_PROTOCOL_VERSION, "environment descriptor",
                "serverVersion=" + descriptor.path("serverVersion").asText() + " orchestrationProtocol=" + protocol);
        if (protocol != T3Http.ORCHESTRATION_PROTOCOL_VERSION) return false;

        JsonNode anonymous = http.authSession(null);
        step(true, "auth policy", anonymous.path("auth").path("policy").asText()
                + " sessionMethods=" + anonymous.path("auth").path("sessionMethods"));

        CredentialStore store = new CredentialStore(stateDir, mapper);
        String bearer = obtainBearer(http, store, pairingToken);
        if (bearer == null) return false;

        JsonNode session = http.authSession(bearer);
        boolean authenticated = session.path("authenticated").asBoolean(false);
        step(authenticated, "bearer session", "scopes=" + session.path("scopes"));
        if (!authenticated) return false;

        JsonNode projects = http.projects(bearer);
        step(true, "GET /api/projects", "fields=" + fieldNames(projects));

        JsonNode shell = http.shellSnapshot(bearer);
        step(true, "GET /api/orchestration/shell", "fields=" + fieldNames(shell));

        return probeWebSocket(http, bearer);
    }

    private URI resolveOrigin(String configuredBaseUrl) throws IOException {
        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
            step(true, "origin", configuredBaseUrl + " (from config)");
            return URI.create(configuredBaseUrl);
        }
        Path path = T3RuntimeState.defaultPath();
        Optional<T3RuntimeState> state = T3RuntimeState.read(path);
        if (state.isEmpty()) {
            step(false, "origin", "no " + path + "; is T3 Code running?");
            return null;
        }
        T3RuntimeState s = state.get();
        boolean alive = s.processAlive();
        step(alive, "origin", s.origin() + " pid=" + s.pid() + " serverVersion=" + s.serverVersion()
                + (alive ? "" : " (process not running, stale file)"));
        return alive ? s.originUri() : null;
    }

    private String obtainBearer(T3Http http, CredentialStore store, String pairingToken)
            throws IOException, InterruptedException {
        try {
            T3Auth.Bearer bearer = new T3Auth(http, store, Clock.systemUTC()).obtain(pairingToken);
            step(true, "credential", bearer.exchanged()
                    ? "pairing token exchanged, scope=" + bearer.scope() + ", saved to " + store.path()
                    : "reusing " + store.path());
            return bearer.token();
        } catch (T3Auth.T3AuthException | T3Http.T3HttpException e) {
            step(false, "credential", e.getMessage());
            return null;
        }
    }

    private boolean probeWebSocket(T3Http http, String bearer) throws IOException, InterruptedException {
        try (T3RpcClient rpc = new T3RpcClient(http, () -> bearer, client, mapper)) {
            // Read-only RPC with an empty payload: proves the ticket, upgrade, framing and Exit envelope.
            JsonNode archived = rpc.call("orchestration.getArchivedShellSnapshot", mapper.createObjectNode(),
                    Duration.ofSeconds(10));
            step(true, "RPC orchestration.getArchivedShellSnapshot", "fields=" + fieldNames(archived));
            return true;
        } catch (T3RpcClient.T3RpcException e) {
            step(false, "RPC orchestration.getArchivedShellSnapshot", e.getMessage());
            return false;
        }
    }

    private void step(boolean ok, String name, String detail) {
        out.println((ok ? "[ ok ] " : "[FAIL] ") + name + ": " + detail);
    }

    private static String fieldNames(JsonNode node) {
        StringBuilder sb = new StringBuilder("[");
        node.fieldNames().forEachRemaining(n -> sb.append(sb.length() > 1 ? "," : "").append(n));
        return sb.append("]").toString();
    }
}
