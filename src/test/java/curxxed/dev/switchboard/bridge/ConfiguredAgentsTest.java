package curxxed.dev.switchboard.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import curxxed.dev.switchboard.config.AppConfig;
import curxxed.dev.switchboard.dispatch.CircuitBreaker;
import curxxed.dev.switchboard.dispatch.Dispatcher;
import curxxed.dev.switchboard.dispatch.Turn;
import curxxed.dev.switchboard.gateway.AgentDirectory;
import curxxed.dev.switchboard.gateway.BotHealth;
import curxxed.dev.switchboard.gateway.MentionParser;
import curxxed.dev.switchboard.store.AgentStore;
import curxxed.dev.switchboard.store.Database;
import curxxed.dev.switchboard.store.InvocationLog;
import curxxed.dev.switchboard.store.Settings;
import curxxed.dev.switchboard.store.UsageStore;
import curxxed.dev.switchboard.t3.T3Auth;
import curxxed.dev.switchboard.t3.T3Http;
import curxxed.dev.switchboard.t3.T3Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConfiguredAgentsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.systemUTC();

    @TempDir
    Path temporary;

    private AppConfig example() throws Exception {
        return AppConfig.load(Path.of("config.example.json"));
    }

    private AgentDirectory directory(AppConfig config) {
        List<AgentDirectory.AgentBot> bots = new ArrayList<>();
        for (String key : config.agentKeys()) {
            bots.add(new AgentDirectory.AgentBot(key, config.displayNameFor(key), bots.size() + 1L, key + "RelayBot"));
        }
        return new AgentDirectory(bots);
    }

    private ObjectNode exampleJson() throws Exception {
        return (ObjectNode) mapper.readTree(Files.readString(Path.of("config.example.json")));
    }

    private AppConfig load(ObjectNode json) throws Exception {
        Path file = temporary.resolve("agents.json");
        Files.writeString(file, mapper.writeValueAsString(json));
        return AppConfig.load(file);
    }

    @Test
    void fourAgentsLoadWithLegacyNamesAndSecondCodexInstance() throws Exception {
        AppConfig config = example();
        assertEquals(Set.of("claude", "codex", "gemini", "codex2"), Set.copyOf(config.agentKeys()));
        assertTrue(config.validate().isEmpty());
        assertEquals("Claude", config.displayNameFor("claude"));
        assertEquals("Codex", config.displayNameFor("codex"));
        assertEquals("Gemini", config.displayNameFor("gemini"));
        assertEquals("Codex2", config.displayNameFor("codex2"));
        assertEquals("codex_<second-instance-id>", config.agents().get("codex2").instanceId());
        assertEquals("gpt-6.1-sol", config.agents().get("codex2").model());
        assertFalse(config.toString().contains("JKL-from-BotFather"));
    }

    @Test
    void configuredKeysReplaceRatherThanExtendTheOriginalThree() throws Exception {
        ObjectNode json = exampleJson();
        ObjectNode agents = json.putObject("agents");
        agents.putObject("reviewer").put("botToken", "42:reviewer")
                .put("instanceId", "custom-instance").put("model", "custom-model");
        AppConfig config = load(json);
        assertEquals(List.of("reviewer"), config.agentKeys());
        assertEquals("Reviewer", config.displayNameFor("reviewer"));
        assertTrue(config.validate().isEmpty());
    }

    @Test
    void legacyThreeAgentConfigsKeepTheirNames() throws Exception {
        ObjectNode json = exampleJson();
        ((ObjectNode) json.path("agents")).remove("codex2");
        ((ObjectNode) json.path("agents").path("claude")).put("displayName", " ");
        AppConfig config = load(json);
        assertEquals(List.of("claude", "codex", "gemini"), config.agentKeys());
        assertEquals("Claude", config.displayNameFor("claude"));
        assertEquals("Codex", config.displayNameFor("codex"));
        assertEquals("Gemini", config.displayNameFor("gemini"));
        String prompt = new PromptBuilder(config).build(
                new Turn("chain", "claude", 1, -100L, 12, "go", "Admin", null), List.of(),
                new PromptBuilder.Context("Project", "workspace", false, 0, true));
        assertTrue(prompt.contains("one of 3 AI coding agents (Claude, Codex, Gemini)"));
        assertFalse(prompt.contains("Codex2"));
    }

    @Test
    void fourthAgentMustHaveUniqueTokenInstanceAndModel() throws Exception {
        ObjectNode json = exampleJson();
        ObjectNode fourth = (ObjectNode) json.path("agents").path("codex2");
        fourth.put("botToken", json.path("agents").path("codex").path("botToken").asText());
        fourth.put("instanceId", " ");
        fourth.remove("model");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> load(json));
        assertTrue(failure.getMessage().contains("each agent needs its own bot token"));
        assertTrue(failure.getMessage().contains("agents.codex2.instanceId is required"));
        assertTrue(failure.getMessage().contains("agents.codex2.model is required"));
        fourth.put("botToken", "invalid-token");
        failure = assertThrows(IllegalArgumentException.class, () -> load(json));
        assertTrue(failure.getMessage().contains("agents.codex2.botToken"));
    }

    @Test
    void emptyAndNullAgentEntriesProduceValidationErrors() throws Exception {
        ObjectNode json = exampleJson();
        json.putObject("agents");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> load(json)).getMessage()
                .contains("at least one agent"));
        ((ObjectNode) json.path("agents")).putNull("codex2");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> load(json)).getMessage()
                .contains("agents.codex2 is missing"));
    }

    @Test
    void keysDisplayNamesAndUsernamesAreCaseInsensitiveMentions() throws Exception {
        ObjectNode json = exampleJson();
        ((ObjectNode) json.path("agents").path("codex2")).put("displayName", "Second Codex");
        AppConfig config = load(json);
        AgentDirectory directory = directory(config);
        MentionParser mentions = new MentionParser(directory);
        assertEquals("Second Codex", directory.displayNameFor("codex2"));
        for (String alias : List.of("@codex2", "@Codex2", "@Second Codex", "@SECOND CODEX", "@codex2RelayBot")) {
            assertEquals(Set.of("codex2"), mentions.mentionedAgents(alias), alias);
        }
        assertEquals(Set.of("codex2"), mentions.mentionedAgents("@codex2 @Second Codex"));
        assertEquals(List.of("codex2", "claude", "codex"),
                List.copyOf(mentions.mentionedAgents("@Codex2 @Claude @Codex @Second Codex")));
        assertTrue(mentions.mentionedAgents("`@codex2` ```@Second Codex``` a@codex2 @codex2extra").isEmpty());
        assertEquals(Set.copyOf(config.agentKeys()), mentions.mentionedAgents("@everyone", true));
        assertEquals(Set.copyOf(config.agentKeys()), mentions.mentionedAgents("@all", true));
        assertTrue(mentions.mentionedAgents("@everyone @all").isEmpty());
    }

    @Test
    void missingFourthDisplayNameFallsBackToItsKeyName() throws Exception {
        ObjectNode json = exampleJson();
        ((ObjectNode) json.path("agents").path("codex2")).remove("displayName");
        AppConfig config = load(json);
        assertEquals("Codex2", config.displayNameFor("codex2"));
        MentionParser mentions = new MentionParser(directory(config));
        assertEquals(Set.of("codex2"), mentions.mentionedAgents("@codex2 @Codex2"));
    }

    @Test
    void firstPromptListsConfiguredDisplayNamesAndHandoffs() throws Exception {
        ObjectNode json = exampleJson();
        ((ObjectNode) json.path("agents").path("codex2")).put("displayName", "Reviewer");
        AppConfig config = load(json);
        PromptBuilder builder = new PromptBuilder(config);
        Turn turn = new Turn("chain", "codex2", 1, -100L, 12, "review the patch", "Admin", null);
        String prompt = builder.build(turn, List.of(), new PromptBuilder.Context("Project", "workspace", true, 2, true));
        assertTrue(prompt.startsWith("You are Reviewer, one of 4 AI coding agents"), prompt);
        for (String name : List.of("Claude", "Codex", "Gemini", "Reviewer")) {
            assertTrue(prompt.contains(name));
            assertTrue(prompt.contains("@" + name));
        }
        assertFalse(prompt.contains("three AI"));
        assertTrue(prompt.contains("2 more hand-off(s)"));
        assertTrue(prompt.endsWith("Request from Admin (the admin):\nreview the patch\n"));
        String later = builder.build(turn, List.of(), new PromptBuilder.Context("Project", "workspace", false, 0, false));
        assertFalse(later.contains("You are"));
        assertTrue(later.startsWith("Hand-offs are off"));
    }

    @Test
    void commandsAndUsageIncludeFourthAgent() throws Exception {
        AppConfig config = example();
        AgentDirectory directory = directory(config);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/projects", exchange -> {
            byte[] response = ("{\"projects\":[{\"id\":\"project\",\"title\":\"No project\",\"workspaceRoot\":\""
                    + temporary + "\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try (Database db = Database.inMemory(); HttpClient http = HttpClient.newHttpClient()) {
            Settings settings = new Settings(db);
            AgentStore store = new AgentStore(db, clock);
            T3Session session = new T3Session(null, new T3Auth.Bearer("test", false, "", 0));
            T3Http t3 = new T3Http(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), http, mapper);
            Path manifest = temporary.resolve("models.json");
            Files.writeString(manifest, "{\"manifest\":{\"providers\":{\"codex\":{\"models\":[]}}}}");
            AdminCommands commands = new AdminCommands(config, store, settings,
                    new ProjectResolver(t3, session::bearer), new ModelCatalog(manifest, mapper), directory,
                    new BotHealth(clock), session, temporary.resolve("unused.json"), clock);
            try (Dispatcher dispatcher = new Dispatcher(settings, new CircuitBreaker(config.effectiveLimits(), clock),
                    new InvocationLog(db, clock), turn -> "done", null, directory, clock)) {
                String report = commands.agentsReport(dispatcher);
                for (String key : config.agentKeys()) {
                    assertTrue(report.contains(config.displayNameFor(key)), report);
                    assertTrue(report.contains(config.agents().get(key).instanceId()), report);
                }
                assertTrue(commands.model("", dispatcher).contains("Codex2: gpt-6.1-sol"));
                assertTrue(commands.model("@Codex2", dispatcher).startsWith("Codex2 uses gpt-6.1-sol"));
                store.setModelOverride("codex2", "override");
                assertTrue(commands.model("codex2 default", dispatcher).startsWith("Codex2 is back on its default"));
                assertTrue(store.modelOverride("codex2").isEmpty());
                assertTrue(commands.model("missing", dispatcher).contains("codex2"));
                assertTrue(commands.help().contains("@Codex2"));
                UsageMonitor usage = new UsageMonitor(config, store, new UsageStore(db), clock, (agent, text) -> {});
                assertTrue(usage.report("").contains("Codex2"));
                assertTrue(usage.report("codex2").contains("Codex2"));
                assertTrue(usage.report("missing").contains("codex2"));
            }
        } finally {
            server.stop(0);
        }
    }
}
