package curxxed.dev.switchboard.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import curxxed.dev.switchboard.config.AppConfig;
import curxxed.dev.switchboard.bridge.UsageMonitor;
import curxxed.dev.switchboard.store.UsageStore;
import curxxed.dev.switchboard.gateway.AgentDirectory;
import curxxed.dev.switchboard.gateway.BotHealth;
import curxxed.dev.switchboard.gateway.BotInstance;
import curxxed.dev.switchboard.gateway.EnvelopeHandler;
import curxxed.dev.switchboard.gateway.Gateway;
import curxxed.dev.switchboard.store.AgentStore;
import curxxed.dev.switchboard.store.Database;
import curxxed.dev.switchboard.store.InvocationLog;
import curxxed.dev.switchboard.store.MessageLedger;
import curxxed.dev.switchboard.store.Settings;
import curxxed.dev.switchboard.store.UpdateLedger;
import curxxed.dev.switchboard.t3.CredentialStore;
import curxxed.dev.switchboard.telegram.TelegramApi;
import curxxed.dev.switchboard.t3.T3Auth;
import curxxed.dev.switchboard.t3.T3Http;
import curxxed.dev.switchboard.t3.T3RpcClient;
import curxxed.dev.switchboard.t3.T3Session;
import curxxed.dev.switchboard.t3.T3RuntimeState;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * The single relay process: one SQLite database, one shared HTTP client on virtual threads, a T3
 * session, the three Telegram bots, and supervised workers (one poller per bot, plus maintenance).
 */
public final class RelayApp implements AutoCloseable {

    /** Everything a worker needs, built once at startup. */
    public record Services(AppConfig config, Clock clock, ObjectMapper mapper, HttpClient http, Database db,
                           UpdateLedger updates, MessageLedger ledger, AgentStore agents, Settings settings,
                           T3Http t3, T3Session t3Session, T3RpcClient t3Rpc, AgentDirectory directory,
                           Map<String, TelegramApi> bots, BotHealth health, UsageMonitor usage) {}

    private final ExecutorService httpExecutor;
    private final Database db;
    private final Supervisor supervisor;
    private final Services services;
    private final EnvelopeHandler handler;

    private RelayApp(ExecutorService httpExecutor, Database db, Supervisor supervisor, Services services,
                     EnvelopeHandler handler) {
        this.httpExecutor = httpExecutor;
        this.db = db;
        this.supervisor = supervisor;
        this.services = services;
        this.handler = handler;
    }

    public static RelayApp start(AppConfig config, Clock clock, Function<Services, EnvelopeHandler> handlerFactory)
            throws IOException, InterruptedException, SQLException {
        ObjectMapper mapper = new ObjectMapper();
        ExecutorService httpExecutor = Executors.newVirtualThreadPerTaskExecutor();
        Database db = null;
        try {
            HttpClient http = newHttpClient(httpExecutor);
            db = Database.open(config.t3().stateDirPath().resolve("relay.db"));
            Log.info("database ready: schema v" + db.schemaVersion());

            URI origin = T3RuntimeState.resolveOrigin(config.t3().baseUrl());
            T3Http t3 = new T3Http(origin, http, mapper);
            CredentialStore store = new CredentialStore(config.t3().stateDirPath(), mapper);
            T3Auth auth = new T3Auth(t3, store, clock);
            T3Session session = new T3Session(auth, connectT3(t3, auth, store, config));

            AgentStore agentStore = new AgentStore(db, clock);
            Map<String, TelegramApi> bots = new LinkedHashMap<>();
            for (String key : config.agentKeys()) {
                bots.put(key, new TelegramApi(config.telegram().apiBase(), config.agents().get(key).botToken(), http, mapper));
            }
            AgentDirectory directory = identifyBots(config, bots, agentStore);

            T3RpcClient rpc = new T3RpcClient(t3, session::bearer, http, mapper);
            Services services = new Services(config, clock, mapper, http, db,
                    new UpdateLedger(db, clock), new MessageLedger(db), agentStore, new Settings(db),
                    t3, session, rpc, directory, bots, new BotHealth(clock),
                    new UsageMonitor(config, agentStore, new UsageStore(db), clock,
                            (agent, text) -> bots.get(agent).sendMessage(config.telegram().groupId(), text, null),
                            () -> rpc.call("server.getConfig", mapper.createObjectNode(), Duration.ofSeconds(15))
                                    .path("providers")));
            int abandoned = new InvocationLog(db, clock).abandonRunning();
            if (abandoned > 0) Log.warn(abandoned + " agent turn(s) were still running when the relay last stopped");
            EnvelopeHandler handler = handlerFactory.apply(services);
            Gateway gateway = new Gateway(config.telegram(), services.updates(), services.ledger(), directory,
                    handler, clock);

            Supervisor supervisor = new Supervisor(Log::warn);
            RelayApp app = new RelayApp(httpExecutor, db, supervisor, services, handler);
            supervisor.start("maintenance", new MaintenanceWorker(services.updates(), clock));
            supervisor.start("usage-watch", services.usage());
            supervisor.start("credential-watch", new CredentialWatch(session, bots.values().iterator().next(),
                    config.telegram().groupId(), clock));
            bots.forEach((key, api) -> supervisor.start("bot-" + key, new BotInstance(key, api, services.updates(), gateway, services.health())));
            Log.info("relay started; mode=" + services.settings().mode());
            return app;
        } catch (IOException | InterruptedException | SQLException | RuntimeException e) {
            if (db != null) db.close();
            httpExecutor.close();
            throw e;
        }
    }

    /**
     * T3 serves plain http on loopback. Java's default HTTP/2 client sends a h2c upgrade there, and
     * T3 answers POSTs that carry one with an empty 400. GETs still work, which hides the problem.
     */
    public static HttpClient newHttpClient(ExecutorService executor) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Asks Telegram who each bot is, failing fast on bad tokens and reporting privacy mode. */
    private static AgentDirectory identifyBots(AppConfig config, Map<String, TelegramApi> bots, AgentStore store)
            throws IOException, InterruptedException, SQLException {
        List<AgentDirectory.AgentBot> identified = new ArrayList<>();
        for (Map.Entry<String, TelegramApi> e : bots.entrySet()) {
            String key = e.getKey();
            TelegramApi.BotUser me;
            try {
                me = e.getValue().getMe();
            } catch (TelegramApi.TelegramException ex) {
                throw new IOException("agents." + key + ".botToken was rejected by Telegram: " + ex.getMessage());
            }
            store.saveIdentity(new AgentStore.BotIdentity(key, me.id(), me.username(), me.firstName()));
            identified.add(new AgentDirectory.AgentBot(key, config.displayNameFor(key), me.id(), me.username()));
            Log.info(key + " is @" + me.username() + " (id " + me.id() + ")");
            if (!me.canReadAllGroupMessages()) {
                Log.warn("@" + me.username() + " has privacy mode on and only sees commands, mentions and replies."
                        + " For full group context: BotFather /setprivacy -> Disable, then re-add the bot to the group");
            }
        }
        try {
            return new AgentDirectory(identified);
        } catch (IllegalArgumentException ex) {
            throw new IOException("bot configuration conflict: " + ex.getMessage());
        }
    }

    private static T3Auth.Bearer connectT3(T3Http t3, T3Auth auth, CredentialStore store, AppConfig config)
            throws IOException, InterruptedException {
        JsonNode descriptor = t3.environmentDescriptor();
        int protocol = descriptor.path("orchestrationProtocolVersion").asInt(-1);
        if (protocol != T3Http.ORCHESTRATION_PROTOCOL_VERSION) {
            throw new IOException("T3 at " + t3.origin() + " speaks orchestration protocol " + protocol
                    + "; this relay needs " + T3Http.ORCHESTRATION_PROTOCOL_VERSION);
        }
        T3Auth.Bearer bearer = auth.obtain(config.t3().pairingToken());
        if (bearer.exchanged()) {
            Log.warn("t3.pairingToken was exchanged and is now spent; you can remove it from config.json");
        }
        JsonNode session = t3.authSession(bearer.token());
        List<String> scopes = new ArrayList<>();
        session.path("scopes").forEach(s -> scopes.add(s.asText()));
        if (!session.path("authenticated").asBoolean(false) || !scopes.contains("orchestration:operate")) {
            throw new IOException("T3 rejected the stored credential (scopes=" + scopes + "). Delete "
                    + store.path() + " and pair again:\n  " + T3Auth.PAIR_COMMAND);
        }
        String expiry = bearer.expiresAtEpochSecond() > 0
                ? "; credential valid until " + Instant.ofEpochSecond(bearer.expiresAtEpochSecond()).toString().substring(0, 10)
                : "";
        Log.info("T3 " + descriptor.path("serverVersion").asText() + " at " + t3.origin() + " ok; scopes=" + scopes + expiry);
        return bearer;
    }

    public Services services() {
        return services;
    }

    public Supervisor supervisor() {
        return supervisor;
    }

    /** Blocks until all workers stop. */
    public void awaitShutdown() throws InterruptedException {
        supervisor.awaitAll();
    }

    @Override
    public void close() throws SQLException {
        supervisor.close(); // stop polling first, so no new turns start
        if (handler instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                Log.warn("handler did not close cleanly: " + e);
            }
        }
        services.t3Rpc().close();
        httpExecutor.close();
        db.close();
        Log.info("relay stopped");
    }
}
