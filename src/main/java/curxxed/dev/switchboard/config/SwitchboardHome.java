package curxxed.dev.switchboard.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SwitchboardHome {
    private SwitchboardHome() {}

    public static Path root() {
        return Path.of(System.getProperty("user.home"), ".switchboard");
    }

    public static void initialize() throws IOException {
        initialize(root());
    }

    public static void initialize(Path root) throws IOException {
        for (String directory : List.of("configs", "skills", "mcps", "mds", "state")) {
            Files.createDirectories(root.resolve(directory));
        }
    }

    public static Path defaultConfig(Path root, Path workingDirectory) {
        Path global = root.resolve("configs/config.json");
        Path local = workingDirectory.resolve("config.json");
        return Files.exists(global) || !Files.exists(local) ? global : local;
    }

    public static Path state() {
        return root().resolve("state");
    }
}
