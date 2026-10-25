package curxxed.dev.switchboard.t3;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * The relay's current T3 credential. Everything that talks to T3 reads the bearer from here on
 * each call, so {@code /reauth} can swap in a new one without a restart. T3 offers no refresh
 * token: a new bearer needs a new one-time pairing token, minted with {@code t3 pair} on the PC.
 */
public final class T3Session {

    private final T3Auth auth;
    private volatile T3Auth.Bearer bearer;

    public T3Session(T3Auth auth, T3Auth.Bearer initial) {
        this.auth = auth;
        this.bearer = initial;
    }

    public String bearer() {
        return bearer.token();
    }

    public Optional<Instant> expiresAt() {
        long at = bearer.expiresAtEpochSecond();
        return at > 0 ? Optional.of(Instant.ofEpochSecond(at)) : Optional.empty();
    }

    /** Exchanges a fresh pairing token and switches every T3 caller to the new bearer. */
    public synchronized T3Auth.Bearer reauth(String pairingToken) throws IOException, InterruptedException {
        T3Auth.Bearer fresh = auth.exchange(pairingToken);
        bearer = fresh;
        return fresh;
    }
}
