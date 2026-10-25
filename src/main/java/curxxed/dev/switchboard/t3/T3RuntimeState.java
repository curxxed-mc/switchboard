package curxxed.dev.switchboard.t3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The {@code server-runtime.json} a live T3 server writes next to its database. This is how
 * {@code t3 pair} finds a running server, and it's more reliable than a hardcoded port: Alpha and
 * Beta builds listen on different ports (3774 and 3773 on this machine).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record T3RuntimeState(int version, long pid, String host, int port, String origin,
                             String startedAt, String serverVersion) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static Path defaultPath() {
        String home = System.getenv("T3CODE_HOME");
        Path base = home != null && !home.isBlank() ? Path.of(home) : Path.of(System.getProperty("user.home"), ".t3");
        return base.resolve("userdata").resolve("server-runtime.json");
    }

    public static T3RuntimeState parse(String json) throws IOException {
        T3RuntimeState state = MAPPER.readValue(json, T3RuntimeState.class);
        if (state.version() != 1) {
            throw new IOException("Unsupported server-runtime.json version " + state.version());
        }
        if (state.origin() == null || state.origin().isBlank()) {
            throw new IOException("server-runtime.json has no origin");
        }
        return state;
    }

    public static Optional<T3RuntimeState> read(Path path) throws IOException {
        if (!Files.exists(path)) return Optional.empty();
        return Optional.of(parse(Files.readString(path)));
    }

    /**
     * The T3 origin to talk to: {@code configuredBaseUrl} if set, else the live server recorded in
     * {@code server-runtime.json}.
     */
    public static URI resolveOrigin(String configuredBaseUrl) throws IOException {
        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) return URI.create(configuredBaseUrl);
        Path path = defaultPath();
        T3RuntimeState state = read(path).orElseThrow(() -> new IOException("No " + path + "; is T3 Code running?"));
        if (!state.processAlive()) {
            throw new IOException(path + " names pid " + state.pid() + ", which is not running; is T3 Code running?");
        }
        return state.originUri();
    }

    /** A crashed server can leave the file behind, so the recorded pid must still be running. */
    public boolean processAlive() {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    public URI originUri() {
        return URI.create(origin);
    }
}
