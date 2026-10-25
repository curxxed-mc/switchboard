package curxxed.dev.switchboard.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import curxxed.dev.switchboard.app.RelayApp;
import curxxed.dev.switchboard.app.Supervisor;
import curxxed.dev.switchboard.config.AppConfig;
import curxxed.dev.switchboard.store.Database;
import curxxed.dev.switchboard.store.MessageLedger;
import curxxed.dev.switchboard.store.UpdateLedger;
import curxxed.dev.switchboard.telegram.FakeTelegram;
import curxxed.dev.switchboard.telegram.TelegramApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Three real BotInstance pollers against a fake Bot API, sharing one gateway and database. */
@Timeout(30)
class GatewayIntegrationTest {

    static final long GROUP = -1001234567890L;
    static final long ADMIN = 555;
    static final Map<String, FakeTelegram.Bot> BOTS = new LinkedHashMap<>();

    static {
        BOTS.put("claude", new FakeTelegram.Bot("101:claude-token", 101, "ClaudeRelayBot", true));
        BOTS.put("codex", new FakeTelegram.Bot("102:codex-token", 102, "CodexRelayBot", true));
        BOTS.put("gemini", new FakeTelegram.Bot("103:gemini-token", 103, "GeminiRelayBot", false));
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<MessageEnvelope> handled = new CopyOnWriteArrayList<>();
    private FakeTelegram telegram;
    private Database db;
    private UpdateLedger updates;
    private MessageLedger ledger;
    private ExecutorService httpExecutor;
    private Supervisor supervisor;
    private Map<String, TelegramApi> apis;
    private Gateway gateway;
    private final BotHealth health = new BotHealth(Clock.systemUTC());

    @BeforeEach
    void setUp() throws Exception {
        telegram = new FakeTelegram();
        BOTS.values().forEach(telegram::addBot);
        db = Database.inMemory();
        updates = new UpdateLedger(db, Clock.systemUTC());
        ledger = new MessageLedger(db);
        httpExecutor = Executors.newVirtualThreadPerTaskExecutor();
        var http = RelayApp.newHttpClient(httpExecutor);

        apis = new LinkedHashMap<>();
        List<AgentDirectory.AgentBot> identified = new ArrayList<>();
        for (var e : BOTS.entrySet()) {
            TelegramApi api = new TelegramApi(telegram.apiBase(), e.getValue().token(), http, mapper);
            TelegramApi.BotUser me = api.getMe();
            String displayName = new AppConfig.Agent(e.getValue().token(), e.getKey(), e.getKey())
                    .effectiveDisplayName(e.getKey());
            identified.add(new AgentDirectory.AgentBot(e.getKey(), displayName, me.id(), me.username()));
            apis.put(e.getKey(), api);
        }
        AppConfig.Telegram config = new AppConfig.Telegram(GROUP, ADMIN, telegram.apiBase(), 300);
        gateway = new Gateway(config, updates, ledger, new AgentDirectory(identified),
                (envelope, via) -> handled.add(envelope), Clock.systemUTC());
        supervisor = new Supervisor(m -> {});
    }

    @AfterEach
    void tearDown() throws Exception {
        supervisor.close();
        httpExecutor.close();
        telegram.close();
        db.close();
    }

    /** Seeds each bot's offset so the first-start backlog skip can't race the test's deliveries. */
    private void startPolling() throws Exception {
        for (String key : apis.keySet()) updates.advanceOffset(key, 1);
        startPollingFresh();
    }

    private void startPollingFresh() {
        apis.forEach((key, api) -> supervisor.start("bot-" + key, new BotInstance(key, api, updates, gateway, health, 1)));
    }

    /** Delivers one group message to all three bots under different update ids, as Telegram does. */
    private void deliverToAll(long baseUpdateId, String messageJson) throws Exception {
        JsonNode payload = mapper.readTree(messageJson.replace('\'', '"'));
        int i = 0;
        for (FakeTelegram.Bot bot : BOTS.values()) telegram.deliver(bot.token(), baseUpdateId + 1000L * i++, payload);
    }

    private String groupMessage(long messageId, long fromId, String text, long date) {
        return """
                {'message': {'message_id': %d, 'date': %d, 'chat': {'id': %d, 'type': 'supergroup'},
                 'from': {'id': %d, 'is_bot': false, 'first_name': 'User%d'}, 'text': '%s'}}"""
                .formatted(messageId, date, GROUP, fromId, fromId, text);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("condition not met within 10s");
            Thread.sleep(20);
        }
    }

    @Test
    void groupMessageSeenByThreeBotsIsHandledOnceAndRecorded() throws Exception {
        startPolling();
        long now = Instant.now().getEpochSecond();
        deliverToAll(1, groupMessage(10, ADMIN, "@Codex run the tests", now));
        deliverToAll(2, groupMessage(11, ADMIN, "@Gemini and @Claude review", now));

        await(() -> handled.size() >= 2);
        Thread.sleep(1500); // let every poller drain its copies
        for (String key : BOTS.keySet()) assertTrue(health.state(key).orElseThrow().online(), key + " is online");
        assertEquals(2, handled.size(), "each message must reach the handler exactly once: " + handled);
        assertEquals(List.of("codex"), List.copyOf(handled.stream().filter(e -> e.messageId() == 10).findFirst().orElseThrow().targetAgents()));
        assertTrue(handled.stream().allMatch(e -> e.sender().isAdmin()));

        assertEquals(List.of("@Codex run the tests", "@Gemini and @Claude review"),
                ledger.recent(GROUP, 10).stream().map(MessageLedger.Entry::text).toList());
        for (var e : BOTS.entrySet()) {
            // Each bot confirmed both of its own updates: next offset = last update id + 1.
            int index = List.copyOf(BOTS.keySet()).indexOf(e.getKey());
            assertEquals(2 + 1000L * index + 1, updates.nextOffset(e.getKey()).orElseThrow(), e.getKey());
        }
    }

    @Test
    void foreignChatsAreIgnored() throws Exception {
        startPolling();
        long now = Instant.now().getEpochSecond();
        telegram.deliver("101:claude-token", 1, mapper.readTree("""
                {"message": {"message_id": 1, "date": %d, "chat": {"id": 555, "type": "private"},
                 "from": {"id": 555, "first_name": "A"}, "text": "@Codex do something"}}""".formatted(now)));
        telegram.deliver("101:claude-token", 2, mapper.readTree("""
                {"message": {"message_id": 1, "date": %d, "chat": {"id": -100999, "type": "group"},
                 "from": {"id": 555, "first_name": "A"}, "text": "/stopall"}}""".formatted(now)));
        deliverToAll(3, groupMessage(20, ADMIN, "marker", now));

        await(() -> handled.size() >= 1);
        Thread.sleep(500);
        assertEquals(List.of(20L), handled.stream().map(MessageEnvelope::messageId).toList());
        assertTrue(telegram.sent().isEmpty(), "nothing is ever sent to a foreign chat");
        assertEquals(1, ledger.recent(GROUP, 10).size());
        assertTrue(ledger.recent(555, 10).isEmpty());
    }

    @Test
    void basicGroupsAreRefusedBecauseMessageIdsDifferPerBot() throws Exception {
        startPolling();
        long now = Instant.now().getEpochSecond();
        // What Telegram really sent on this machine: one message, a different message_id per bot.
        int i = 0;
        for (FakeTelegram.Bot bot : BOTS.values()) {
            telegram.deliver(bot.token(), 1 + i, mapper.readTree("""
                    {"message": {"message_id": %d, "date": %d, "chat": {"id": %d, "type": "group"},
                     "from": {"id": %d, "first_name": "A"}, "text": "@Claude hi"}}""".formatted(4 + i, now, GROUP, ADMIN)));
            i++;
        }
        deliverToAll(10, groupMessage(70, ADMIN, "marker", now));

        await(() -> handled.size() >= 1);
        Thread.sleep(500);
        assertEquals(List.of(70L), handled.stream().map(MessageEnvelope::messageId).toList());
        assertEquals(List.of("marker"), ledger.recent(GROUP, 10).stream().map(MessageLedger.Entry::text).toList());
    }

    @Test
    void adminCommandsFromOthersAreRefusedOnceAndNotHandled() throws Exception {
        startPolling();
        long now = Instant.now().getEpochSecond();
        deliverToAll(1, groupMessage(30, 777, "/stopall", now));
        deliverToAll(2, groupMessage(31, ADMIN, "/stopall", now));
        deliverToAll(3, groupMessage(32, 777, "/agents", now));

        await(() -> handled.size() >= 2);
        Thread.sleep(1500);
        assertEquals(List.of(31L, 32L), handled.stream().map(MessageEnvelope::messageId).sorted().toList());
        List<FakeTelegram.Sent> refusals = telegram.sent("sendMessage");
        assertEquals(1, refusals.size(), "one refusal, not one per bot");
        assertTrue(refusals.get(0).body().path("text").asText().contains("Only the relay administrator"));
        assertEquals(30, refusals.get(0).body().path("reply_parameters").path("message_id").asLong());
    }

    @Test
    void commandsForOtherBotsAreDropped() throws Exception {
        startPolling();
        long now = Instant.now().getEpochSecond();
        deliverToAll(1, groupMessage(40, ADMIN, "/start@SomeOtherBot", now));
        deliverToAll(2, groupMessage(41, ADMIN, "/agents@CodexRelayBot", now));

        await(() -> handled.size() >= 1);
        Thread.sleep(1000);
        assertEquals(List.of(41L), handled.stream().map(MessageEnvelope::messageId).toList());
        assertEquals("CodexRelayBot", handled.get(0).command().addressedTo());
    }

    @Test
    void staleMessagesAreRecordedButNotHandled() throws Exception {
        startPolling();
        long now = Instant.now().getEpochSecond();
        deliverToAll(1, groupMessage(50, ADMIN, "@Codex old request", now - 3600));
        deliverToAll(2, groupMessage(51, ADMIN, "@Codex fresh request", now));

        await(() -> handled.size() >= 1);
        Thread.sleep(1000);
        assertEquals(List.of(51L), handled.stream().map(MessageEnvelope::messageId).toList());
        assertEquals(2, ledger.recent(GROUP, 10).size());
    }

    @Test
    void firstStartSkipsBacklogButRestartResumes() throws Exception {
        long now = Instant.now().getEpochSecond();
        deliverToAll(1, groupMessage(60, ADMIN, "@Codex queued before the relay ever ran", now));
        startPollingFresh();
        await(() -> {
            try {
                return updates.nextOffset("gemini").isPresent();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        deliverToAll(10, groupMessage(61, ADMIN, "@Codex after start", now));

        await(() -> handled.size() >= 1);
        Thread.sleep(1000);
        assertEquals(List.of(61L), handled.stream().map(MessageEnvelope::messageId).toList());
    }

    @Test
    void buttonTapsInTheGroupReachTheHandlerOnce() throws Exception {
        List<ButtonPress> presses = new CopyOnWriteArrayList<>();
        Gateway withButtons = new Gateway(new AppConfig.Telegram(GROUP, ADMIN, telegram.apiBase(), 300), updates, ledger,
                new AgentDirectory(List.of(new AgentDirectory.AgentBot("codex", "Codex", 102, "CodexRelayBot"))),
                new EnvelopeHandler() {
                    @Override
                    public void handle(MessageEnvelope envelope, TelegramApi via) {}

                    @Override
                    public void handleButton(ButtonPress press, TelegramApi via) {
                        presses.add(press);
                    }
                }, Clock.systemUTC());
        updates.advanceOffset("codex", 1);
        supervisor.start("bot-codex", new BotInstance("codex", apis.get("codex"), updates, withButtons, health, 1));
        String tap = """
                {"callback_query": {"id": "cb%d", "from": {"id": %d, "first_name": "Alex"}, "data": "ap:abcd1234:0",
                 "message": {"message_id": 77, "chat": {"id": %d}}}}""";
        telegram.deliver("102:codex-token", 1, mapper.readTree(tap.formatted(1, ADMIN, GROUP)));
        telegram.deliver("102:codex-token", 2, mapper.readTree(tap.formatted(2, 777, GROUP)));
        telegram.deliver("102:codex-token", 3, mapper.readTree(tap.formatted(3, ADMIN, -100999L)));

        await(() -> presses.size() >= 2);
        Thread.sleep(500);
        assertEquals(2, presses.size(), "the foreign-chat tap is not handled");
        assertTrue(presses.get(0).fromAdmin());
        assertFalse(presses.get(1).fromAdmin());
        assertEquals("ap:abcd1234:0", presses.get(0).data());
        assertEquals(77, presses.get(0).messageId());
        assertEquals(1, telegram.sent("answerCallbackQuery").size(), "the foreign tap's spinner is still stopped");
    }

    @Test
    void privacyModeIsReportedByGetMe() throws Exception {
        assertFalse(apis.get("gemini").getMe().canReadAllGroupMessages());
        assertTrue(apis.get("claude").getMe().canReadAllGroupMessages());
    }

    @Test
    void rejectedTokenStopsOnlyThatBot() throws Exception {
        TelegramApi bad = new TelegramApi(telegram.apiBase(), "999:revoked", RelayApp.newHttpClient(httpExecutor), mapper);
        List<String> log = new CopyOnWriteArrayList<>();
        try (Supervisor s = new Supervisor(log::add)) {
            s.start("bot-bad", new BotInstance("bad", bad, updates, gateway, health, 1));
            s.awaitAll();
        }
        assertEquals(List.of("bot-bad finished"), log);
        assertFalse(health.state("bad").orElseThrow().online(), "/agents shows it offline");
        assertEquals("token rejected by Telegram", health.state("bad").orElseThrow().problem());

        TelegramApi.TelegramException e = assertThrows(TelegramApi.TelegramException.class, bad::getMe);
        assertEquals(401, e.errorCode);
        assertFalse(e.getMessage().contains("revoked"), "the token must never appear in errors");
    }
}
