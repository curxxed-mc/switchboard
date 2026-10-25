package curxxed.dev.switchboard.t3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import curxxed.dev.switchboard.app.RelayApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Runs T3Auth against a local stand-in for T3's {@code /oauth/token}. */
class T3AuthTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC);
    private final List<String> exchangeBodies = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ExecutorService executor;
    private T3Http http;

    @TempDir
    Path stateDir;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth/token", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchangeBodies.add(body);
            boolean ok = body.contains("subject_token=GOOD");
            byte[] reply = (ok
                    ? "{\"access_token\":\"bearer-1\",\"issued_token_type\":\"urn:ietf:params:oauth:token-type:access_token\","
                      + "\"token_type\":\"Bearer\",\"expires_in\":2591999,\"scope\":\"orchestration:read orchestration:operate\"}"
                    : "{\"_tag\":\"EnvironmentAuthInvalidError\",\"reason\":\"invalid_credential\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(ok ? 200 : 401, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        executor = Executors.newVirtualThreadPerTaskExecutor();
        URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        http = new T3Http(origin, RelayApp.newHttpClient(executor), mapper);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        executor.close();
    }

    @Test
    void exchangesPairingTokenOnceThenReusesStoredBearer() throws Exception {
        CredentialStore store = new CredentialStore(stateDir, mapper);
        T3Auth auth = new T3Auth(http, store, clock);

        T3Auth.Bearer first = auth.obtain("GOOD");
        assertTrue(first.exchanged());
        assertEquals("bearer-1", first.token());
        assertEquals(clock.instant().getEpochSecond() + 2591999, store.load().orElseThrow().expiresAtEpochSecond());

        T3Auth.Bearer second = auth.obtain("GOOD");
        assertFalse(second.exchanged(), "a spent pairing token must not be exchanged again");
        assertEquals("bearer-1", second.token());
        assertEquals(1, exchangeBodies.size());
        assertTrue(exchangeBodies.get(0).contains("scope=orchestration%3Aread+orchestration%3Aoperate"));
    }

    @Test
    void storedBearerForAnotherOriginIsNotReused() throws Exception {
        CredentialStore store = new CredentialStore(stateDir, mapper);
        store.save(new CredentialStore.StoredCredential("old", T3Auth.RELAY_SCOPE, 0, "http://127.0.0.1:3774"));

        T3Auth.T3AuthException e = assertThrows(T3Auth.T3AuthException.class,
                () -> new T3Auth(http, store, clock).obtain(null));
        assertTrue(e.getMessage().contains("belongs to http://127.0.0.1:3774"), e.getMessage());
        assertTrue(e.getMessage().contains("t3 pair"), e.getMessage());
    }

    @Test
    void expiredBearerIsReplacedByExchange() throws Exception {
        CredentialStore store = new CredentialStore(stateDir, mapper);
        store.save(new CredentialStore.StoredCredential("stale", T3Auth.RELAY_SCOPE,
                clock.instant().getEpochSecond() - 10, http.origin().toString()));

        T3Auth.Bearer bearer = new T3Auth(http, store, clock).obtain("GOOD");
        assertTrue(bearer.exchanged());
        assertEquals("bearer-1", store.load().orElseThrow().accessToken());
    }

    @Test
    void rejectedPairingTokenSurfacesServerError() {
        CredentialStore store = new CredentialStore(stateDir, mapper);
        T3Http.T3HttpException e = assertThrows(T3Http.T3HttpException.class,
                () -> new T3Auth(http, store, clock).obtain("BAD"));
        assertEquals(401, e.status);
        assertTrue(e.getMessage().contains("invalid_credential"), e.getMessage());
    }

    @Test
    void exampleConfigPlaceholderCountsAsUnset() {
        assertFalse(T3Auth.isUsablePairingToken("paste output of: ~/.t3/bin/t3 pair ..."));
        assertFalse(T3Auth.isUsablePairingToken("  "));
        assertFalse(T3Auth.isUsablePairingToken(null));
        assertTrue(T3Auth.isUsablePairingToken("2FF7F9U85YYH"));
    }
}
