package com.mccompanion.runtime;

import com.mccompanion.runtime.command.CommandService;
import com.mccompanion.runtime.command.IdempotencyStore;
import com.mccompanion.runtime.command.ProtocolCommandSender;
import com.mccompanion.runtime.config.RuntimeConfig;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.intent.RuleIntentParser;
import com.mccompanion.runtime.health.RuntimeHealthServer;
import com.mccompanion.runtime.lease.LeaseService;
import com.mccompanion.runtime.logging.Redactor;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.provider.IntentProvider;
import com.mccompanion.runtime.provider.OpenAiCompatibleProvider;
import com.mccompanion.runtime.provider.ProviderRouter;
import com.mccompanion.runtime.security.PairingTokenStore;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.session.SessionRegistry;
import com.mccompanion.runtime.task.TaskEventStore;
import com.mccompanion.runtime.task.TaskRepository;
import com.mccompanion.runtime.websocket.RuntimeWebSocketServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the complete Runtime lifecycle and closes every external resource deterministically. */
public final class RuntimeApplication implements AutoCloseable {
    private final RuntimeConfig config;
    private final RuntimeLog log;
    private final RuntimeDatabase database;
    private final CompanionRepository companions;
    private final SessionRegistry sessions;
    private final CommandService commands;
    private final IntentProvider provider;
    private final ProviderRouter providerRouter;
    private final RuntimeWebSocketServer webSocket;
    private final RuntimeHealthServer healthServer;
    private final ScheduledExecutorService maintenance;
    private final RuntimeCli cli;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    private RuntimeApplication(
            RuntimeConfig config,
            RuntimeLog log,
            RuntimeDatabase database,
            CompanionRepository companions,
            SessionRegistry sessions,
            CommandService commands,
            IntentProvider provider,
            ProviderRouter providerRouter,
            RuntimeWebSocketServer webSocket,
            RuntimeHealthServer healthServer,
            ScheduledExecutorService maintenance,
            RuntimeCli cli) {
        this.config = config;
        this.log = log;
        this.database = database;
        this.companions = companions;
        this.sessions = sessions;
        this.commands = commands;
        this.provider = provider;
        this.providerRouter = providerRouter;
        this.webSocket = webSocket;
        this.healthServer = healthServer;
        this.maintenance = maintenance;
        this.cli = cli;
    }

    public static RuntimeApplication start(RuntimeConfig config, boolean enableCli)
            throws IOException, SQLException, InterruptedException {
        Objects.requireNonNull(config, "config").normalizeAndValidate();
        Redactor redactor = new Redactor();
        String pairingToken = new PairingTokenStore(config.tokenPath()).loadOrCreate();
        redactor.registerSecret(pairingToken);
        RuntimeLog log = new RuntimeLog(config.logPath(), config.logging.console, redactor);
        RuntimeDatabase database = new RuntimeDatabase(config.databasePath());
        IntentProvider provider = null;
        SessionRegistry sessions = null;
        RuntimeWebSocketServer webSocket = null;
        RuntimeHealthServer healthServer = null;
        ScheduledExecutorService maintenance = null;
        RuntimeCli cli = null;
        try {
            database.initialize();
            CompanionRepository companions = new CompanionRepository(database);
            TaskEventStore events = new TaskEventStore(database);
            TaskRepository tasks = new TaskRepository(database, events);
            LeaseService leases = new LeaseService(database);
            int invalidatedLeases = leases.invalidateRecoveredLeases();
            sessions = new SessionRegistry(database, companions, log);
            CommandService commands = new CommandService(
                    sessions,
                    companions,
                    tasks,
                    leases,
                    new IdempotencyStore(database),
                    new ProtocolCommandSender(),
                    log);
            sessions.setListener(commands);

            int staleSessions = sessions.recoverStaleSessions();
            int reconciliationTasks = tasks.markUnfinishedForReconciliation().size();
            if (staleSessions > 0 || reconciliationTasks > 0 || invalidatedLeases > 0) {
                log.warn("Startup reconciliation queued: staleSessions=" + staleSessions
                        + ", unfinishedTasks=" + reconciliationTasks
                        + ", invalidatedLeases=" + invalidatedLeases);
            }

            provider = createProvider(config, redactor, log);
            ProviderRouter providerRouter = new ProviderRouter(new RuleIntentParser(), provider, log);
            webSocket = new RuntimeWebSocketServer(
                    new InetSocketAddress(config.server.bind, config.server.port),
                    pairingToken,
                    sessions,
                    commands,
                    log);
            webSocket.startAndAwait(Duration.ofSeconds(15));
            healthServer = new RuntimeHealthServer(config, pairingToken, sessions, commands, log);
            healthServer.start();

            RuntimeWebSocketServer activeWebSocket = webSocket;
            SessionRegistry activeSessions = sessions;
            maintenance = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mc-companion-runtime-maintenance");
                thread.setDaemon(false);
                return thread;
            });
            maintenance.scheduleWithFixedDelay(() -> {
                try {
                    activeWebSocket.sweepPending(Duration.ofSeconds(20));
                    activeSessions.expireHeartbeat(config.server.heartbeatInterval().multipliedBy(3));
                    commands.expireLeases();
                    commands.renewActiveLeases();
                } catch (RuntimeException failure) {
                    log.error("Runtime maintenance iteration failed", failure);
                }
            }, 5, 5, TimeUnit.SECONDS);

            final RuntimeApplication[] holder = new RuntimeApplication[1];
            if (enableCli) {
                cli = new RuntimeCli(companions, commands, providerRouter, () -> {
                    RuntimeApplication application = holder[0];
                    if (application != null) {
                        application.close();
                    }
                }, System.in, System.out);
            }
            RuntimeApplication application = new RuntimeApplication(config, log, database, companions, sessions,
                    commands, provider, providerRouter, webSocket, healthServer, maintenance, cli);
            holder[0] = application;
            log.info("Minecraft AI Companion Runtime started: protocol=mc-companion/1, provider="
                    + (provider == null ? "rules" : "openai-compatible")
                    + ", database=WAL, bind=" + config.server.bind + ':' + webSocket.getPort());
            if (cli != null) {
                cli.start();
            }
            return application;
        } catch (IOException | SQLException | InterruptedException | RuntimeException failure) {
            closeQuietly(cli);
            shutdownExecutor(maintenance);
            closeQuietly(webSocket);
            closeQuietly(healthServer);
            closeQuietly(sessions);
            closeQuietly(provider);
            closeQuietly(database);
            closeQuietly(log);
            throw failure;
        }
    }

    private static IntentProvider createProvider(RuntimeConfig config, Redactor redactor, RuntimeLog log) {
        if ("rules".equals(config.provider.mode)) {
            return null;
        }
        String key = config.provider.resolveApiKey().orElse(null);
        if (key == null) {
            log.warn("Provider key environment variable is absent; Runtime is using rules fallback");
            return null;
        }
        redactor.registerSecret(key);
        try {
            return new OpenAiCompatibleProvider(
                    config.provider.baseUrl,
                    key,
                    config.provider.model,
                    config.provider.timeout());
        } catch (RuntimeException invalidProvider) {
            log.error("Provider configuration was rejected; Runtime is using rules fallback", invalidProvider);
            return null;
        }
    }

    public RuntimeConfig config() {
        return config;
    }

    public CompanionRepository companions() {
        return companions;
    }

    public SessionRegistry sessions() {
        return sessions;
    }

    public CommandService commands() {
        return commands;
    }

    public ProviderRouter providerRouter() {
        return providerRouter;
    }

    public int port() {
        return webSocket.getPort();
    }

    public void awaitShutdown() throws InterruptedException {
        stopped.await();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        log.info("Minecraft AI Companion Runtime shutting down");
        closeQuietly(cli);
        shutdownExecutor(maintenance);
        try {
            commands.releaseAllLeases();
        } catch (RuntimeException failure) {
            log.error("Unable to release all control leases during shutdown", failure);
        }
        closeQuietly(webSocket);
        closeQuietly(healthServer);
        closeQuietly(sessions);
        closeQuietly(provider);
        closeQuietly(database);
        closeQuietly(log);
        stopped.countDown();
    }

    private static void shutdownExecutor(ScheduledExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Runtime maintenance executor did not terminate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Startup failure cleanup is best effort; the original exception is preserved.
        }
    }
}
