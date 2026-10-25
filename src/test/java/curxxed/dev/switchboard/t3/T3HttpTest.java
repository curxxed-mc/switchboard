package curxxed.dev.switchboard.t3;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class T3HttpTest {

    private final T3Http http = new T3Http(URI.create("http://127.0.0.1:3773"), HttpClient.newHttpClient(), new ObjectMapper());

    @Test
    void webSocketUriCarriesTicketAndProtocol() {
        URI uri = http.webSocketUri("a b+c");
        assertEquals("ws://127.0.0.1:3773/ws?wsTicket=a+b%2Bc&orchestrationProtocol=2", uri.toString());
    }

    @Test
    void secureOriginUsesWss() {
        T3Http tls = new T3Http(URI.create("https://pc.tailnet.ts.net:8443"), HttpClient.newHttpClient(), new ObjectMapper());
        assertTrue(tls.webSocketUri("t").toString().startsWith("wss://pc.tailnet.ts.net:8443/ws?"));
    }

    @Test
    void formEncodingEscapesUrnsAndSpaces() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", T3Http.GRANT_TYPE_TOKEN_EXCHANGE);
        form.put("scope", T3Auth.RELAY_SCOPE);
        assertEquals("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange"
                + "&scope=orchestration%3Aread+orchestration%3Aoperate", T3Http.formEncode(form));
    }

    @Test
    void credentialStoreRoundTripsWithOwnerOnlyPermissions(@TempDir Path dir) throws Exception {
        CredentialStore store = new CredentialStore(dir, new ObjectMapper());
        assertTrue(store.load().isEmpty());

        var credential = new CredentialStore.StoredCredential("tok", T3Auth.RELAY_SCOPE, 2_000_000_000L, "http://127.0.0.1:3773");
        store.save(credential);

        assertEquals(credential, store.load().orElseThrow());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.path())));
    }

    @Test
    void credentialExpiresOneMinuteEarly() {
        Instant now = Instant.ofEpochSecond(1_000);
        assertFalse(new CredentialStore.StoredCredential("t", "s", 1_061, "o").expired(now));
        assertTrue(new CredentialStore.StoredCredential("t", "s", 1_060, "o").expired(now));
        assertFalse(new CredentialStore.StoredCredential("t", "s", 0, "o").expired(now), "0 means no expiry");
    }
}
