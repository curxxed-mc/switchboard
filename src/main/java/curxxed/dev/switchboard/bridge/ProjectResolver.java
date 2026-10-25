package curxxed.dev.switchboard.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import curxxed.dev.switchboard.t3.T3Http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Finds the T3 project agents work in and decides, per project, worktree versus shared folder. */
public final class ProjectResolver {

    /**
     * @param gitBranch the checked-out branch when the folder is a git repository (and so gets
     *                  per-agent worktrees), else null
     */
    public record Project(String id, String title, String workspaceRoot, String gitBranch) {
        public boolean usesWorktrees() {
            return gitBranch != null;
        }
    }

    private final T3Http http;
    private final Supplier<String> bearer;

    public ProjectResolver(T3Http http, Supplier<String> bearer) {
        this.http = http;
        this.bearer = bearer;
    }

    /** Titles of T3's projects, for listing. */
    public List<String> titles() throws IOException, InterruptedException {
        return live().stream().map(p -> p.path("title").asText()).toList();
    }

    private List<JsonNode> live() throws IOException, InterruptedException {
        List<JsonNode> live = new ArrayList<>();
        for (JsonNode p : http.projects(bearer.get()).path("projects")) {
            if (p.path("deletedAt").isNull() || p.path("deletedAt").isMissingNode()) live.add(p);
        }
        return live;
    }

    /** @param idOrTitle a project id, or a title (case-insensitive) */
    public Project resolve(String idOrTitle) throws IOException, InterruptedException {
        List<JsonNode> live = live();
        Optional<JsonNode> match = live.stream().filter(p -> p.path("id").asText().equals(idOrTitle)).findFirst()
                .or(() -> live.stream().filter(p -> p.path("title").asText().equalsIgnoreCase(idOrTitle)).findFirst());
        if (match.isEmpty()) {
            throw new IOException("No T3 project with id or title '" + idOrTitle + "'. Projects: "
                    + live.stream().map(p -> p.path("title").asText()).toList());
        }
        JsonNode p = match.get();
        String root = p.path("workspaceRoot").asText();
        return new Project(p.path("id").asText(), p.path("title").asText(), root, gitBranch(Path.of(root)));
    }

    /**
     * The checked-out branch if {@code root} is a git repository on this machine, else null. T3
     * runs on the same machine as the relay (loopback), so a local check sees the same folder.
     */
    static String gitBranch(Path root) {
        if (!Files.isDirectory(root)) return null;
        try {
            Process git = new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "--abbrev-ref", "HEAD")
                    .redirectErrorStream(true).start();
            String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!git.waitFor(10, TimeUnit.SECONDS) || git.exitValue() != 0 || out.isEmpty()) return null;
            // A repo with no commits yet, or a detached HEAD, has no branch to base worktrees on.
            return out.equals("HEAD") || out.toLowerCase(Locale.ROOT).contains("fatal") ? null : out;
        } catch (IOException e) {
            return null; // git not installed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
