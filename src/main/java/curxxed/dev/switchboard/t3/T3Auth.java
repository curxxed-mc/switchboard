package curxxed.dev.switchboard.t3;

import java.io.IOException;
import java.time.Clock;
import java.util.Optional;

/**
 * Gets a bearer token for the relay: the stored one if it's still valid for this origin, otherwise
 * by exchanging the configured one-time pairing token.
 */
public final class T3Auth {

    /** Least privilege for the relay: read threads, and start, steer and interrupt runs. */
    public static final String RELAY_SCOPE = "orchestration:read orchestration:operate";

    public static final String PAIR_COMMAND = "~/.t3/bin/t3 pair --label switchboard --ttl 10m "
            + "--scope orchestration:read --scope orchestration:operate";

    /**
     * @param exchanged            true when a pairing token was spent to get this bearer
     * @param expiresAtEpochSecond 0 when T3 reported no expiry
     */
    public record Bearer(String token, boolean exchanged, String scope, long expiresAtEpochSecond) {
        @Override
        public String toString() {
            return "Bearer[exchanged=" + exchanged + ", scope=" + scope + "]";
        }
    }

    public static final class T3AuthException extends IOException {
        T3AuthException(String message) {
            super(message);
        }
    }

    private final T3Http http;
    private final CredentialStore store;
    private final Clock clock;

    public T3Auth(T3Http http, CredentialStore store, Clock clock) {
        this.http = http;
        this.store = store;
        this.clock = clock;
    }

    public Bearer obtain(String pairingToken) throws IOException, InterruptedException {
        Optional<CredentialStore.StoredCredential> stored = store.load();
        if (stored.isPresent() && !stored.get().expired(clock.instant())
                && http.origin().toString().equals(stored.get().origin())) {
            return new Bearer(stored.get().accessToken(), false, stored.get().scope(), stored.get().expiresAtEpochSecond());
        }
        if (!isUsablePairingToken(pairingToken)) {
            String why = stored.isEmpty() ? "No stored T3 credential"
                    : stored.get().expired(clock.instant()) ? "The stored T3 credential has expired"
                    : "The stored T3 credential belongs to " + stored.get().origin();
            throw new T3AuthException(why + " and t3.pairingToken is not set. Mint one with:\n  " + PAIR_COMMAND);
        }
        return exchange(pairingToken);
    }

    /** Spends {@code pairingToken} on a new bearer and stores it, replacing any stored one. */
    public Bearer exchange(String pairingToken) throws IOException, InterruptedException {
        if (!isUsablePairingToken(pairingToken)) throw new T3AuthException("t3.pairingToken is not set");
        T3Http.AccessToken token = http.exchangePairingToken(pairingToken.trim(), RELAY_SCOPE, "switchboard");
        long expiresAt = token.expiresInSeconds() > 0 ? clock.instant().getEpochSecond() + token.expiresInSeconds() : 0;
        store.save(new CredentialStore.StoredCredential(token.accessToken(), token.scope(), expiresAt,
                http.origin().toString()));
        return new Bearer(token.accessToken(), true, token.scope(), expiresAt);
    }

    static boolean isUsablePairingToken(String token) {
        // The example config holds an instruction string; treat it as unset.
        return token != null && !token.isBlank() && !token.startsWith("paste ");
    }
}
