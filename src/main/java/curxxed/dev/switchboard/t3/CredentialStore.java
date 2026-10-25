package curxxed.dev.switchboard.t3;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Optional;

/**
 * Persists the bearer token obtained from the one-shot pairing exchange. Pairing credentials are
 * consumed on first use, so losing this file means minting a new one with {@code t3 pair}.
 */
public final class CredentialStore {

    /** @param expiresAtEpochSecond 0 when the server reported no expiry */
    public record StoredCredential(String accessToken, String scope, long expiresAtEpochSecond, String origin) {
        public boolean expired(Instant now) {
            return expiresAtEpochSecond > 0 && now.getEpochSecond() >= expiresAtEpochSecond - 60;
        }
    }

    private final Path file;
    private final ObjectMapper mapper;

    public CredentialStore(Path stateDir, ObjectMapper mapper) {
        this.file = stateDir.resolve("t3-credential.json");
        this.mapper = mapper;
    }

    public Optional<StoredCredential> load() throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        return Optional.of(mapper.readValue(Files.readString(file), StoredCredential.class));
    }

    public void save(StoredCredential credential) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(tmp, mapper.writeValueAsString(credential));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public Path path() {
        return file;
    }
}
