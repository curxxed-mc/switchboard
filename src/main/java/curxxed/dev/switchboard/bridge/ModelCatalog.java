package curxxed.dev.switchboard.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The models T3 Code offers per provider driver, read from the manifest T3 caches in its state
 * directory ({@code userdata/model-manifest.json}). It's re-read on every lookup, so T3's own
 * refreshes show up without restarting the relay.
 */
public final class ModelCatalog {

    public record Model(String slug, String name, String status) {}

    /** Outcome of resolving what someone typed, such as {@code astra} or {@code gpt-6-astra}. */
    public sealed interface Resolution permits Found, Ambiguous, Unknown {}

    public record Found(Model model) implements Resolution {}

    public record Ambiguous(List<Model> candidates) implements Resolution {}

    /** @param available what the driver does offer, possibly empty if the manifest is unreadable */
    public record Unknown(List<Model> available) implements Resolution {}

    private final Path manifest;
    private final ObjectMapper mapper;

    public ModelCatalog(Path manifest, ObjectMapper mapper) {
        this.manifest = manifest;
        this.mapper = mapper;
    }

    /** The manifest of the T3 install the relay talks to (same machine, same T3 home). */
    public static Path defaultManifest() {
        String home = System.getenv("T3CODE_HOME");
        Path base = home != null && !home.isBlank() ? Path.of(home) : Path.of(System.getProperty("user.home"), ".t3");
        return base.resolve("userdata").resolve("model-manifest.json");
    }

    /**
     * T3 names instances after their driver, optionally with a suffix:
     * {@code claudeAgent}, {@code antigravity}, {@code codex_0a1b2c3d-…}.
     */
    public static String driverOf(String instanceId) {
        int cut = instanceId.indexOf('_');
        return cut < 0 ? instanceId : instanceId.substring(0, cut);
    }

    public List<Model> models(String driver) {
        List<Model> out = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(Files.readString(manifest));
            for (JsonNode m : root.path("manifest").path("providers").path(driver).path("models")) {
                out.add(new Model(m.path("slug").asText(), m.path("name").asText(m.path("slug").asText()),
                        m.path("status").asText("")));
            }
        } catch (IOException e) {
            // Unreadable manifest: callers see an empty catalog.
        }
        return out;
    }

    public Resolution resolve(String driver, String query) {
        List<Model> models = models(driver);
        String q = query.strip().toLowerCase(Locale.ROOT);
        for (Model m : models) if (m.slug().equalsIgnoreCase(q)) return new Found(m);
        for (Model m : models) if (m.name().equalsIgnoreCase(q)) return new Found(m);
        List<Model> partial = models.stream()
                .filter(m -> m.slug().toLowerCase(Locale.ROOT).contains(q) || m.name().toLowerCase(Locale.ROOT).contains(q))
                .toList();
        if (partial.size() == 1) return new Found(partial.get(0));
        // "sol" matches several generations; prefer the ones T3 marks current.
        List<Model> current = partial.stream().filter(m -> m.status().equals("current")).toList();
        if (current.size() == 1) return new Found(current.get(0));
        if (!partial.isEmpty()) return new Ambiguous(partial);
        return new Unknown(models);
    }
}
