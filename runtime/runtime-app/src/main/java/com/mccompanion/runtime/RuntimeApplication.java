package com.mccompanion.runtime;

import com.mccompanion.runtime.command.CommandService;
import com.mccompanion.runtime.command.IdempotencyStore;
import com.mccompanion.runtime.command.ProtocolCommandSender;
import com.mccompanion.runtime.agent.AgentPlanRepository;
import com.mccompanion.runtime.agent.AgentKernel;
import com.mccompanion.runtime.brain.ExternalBrainAdapter;
import com.mccompanion.runtime.brain.ExternalBrainCoordinator;
import com.mccompanion.runtime.brain.BrainAuditRepository;
import com.mccompanion.runtime.brain.HermesBrainAdapter;
import com.mccompanion.runtime.brain.OpenAiCompatibleBrainAdapter;
import com.mccompanion.runtime.config.RuntimeConfig;
import com.mccompanion.runtime.capability.CapabilityRegistry;
import com.mccompanion.runtime.capability.CapabilityVisibility;
import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.intent.RuleIntentParser;
import com.mccompanion.runtime.health.RuntimeHealthServer;
import com.mccompanion.runtime.lease.LeaseService;
import com.mccompanion.runtime.logging.Redactor;
import com.mccompanion.runtime.logging.RuntimeLog;
import com.mccompanion.runtime.memory.MemoryRepository;
import com.mccompanion.runtime.memory.MemoryToolGateway;
import com.mccompanion.runtime.conversation.ConversationRepository;
import com.mccompanion.runtime.conversation.ConversationService;
import com.mccompanion.runtime.provider.IntentProvider;
import com.mccompanion.runtime.provider.OpenAiCompatibleProvider;
import com.mccompanion.runtime.provider.ProviderRouter;
import com.mccompanion.runtime.provider.BudgetedProvider;
import com.mccompanion.runtime.security.PairingTokenStore;
import com.mccompanion.runtime.search.DisabledSearchProvider;
import com.mccompanion.runtime.search.HttpSearchProvider;
import com.mccompanion.runtime.search.SearchProvider;
import com.mccompanion.runtime.search.SearchToolGateway;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.session.SessionRegistry;
import com.mccompanion.runtime.task.TaskEventStore;
import com.mccompanion.runtime.task.TaskRepository;
import com.mccompanion.runtime.tool.RuntimeToolGateway;
import com.mccompanion.runtime.tool.CompositeToolGateway;
import com.mccompanion.runtime.tool.ToolGateway;
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
    private final AgentPlanRepository plans;
    private final AgentKernel kernel;
    private final IntentProvider provider;
    private final ProviderRouter providerRouter;
    private final ExternalBrainCoordinator externalBrain;
    private final CompositeToolGateway toolGateway;
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
            AgentPlanRepository plans,
            AgentKernel kernel,
            IntentProvider provider,
            ProviderRouter providerRouter,
            ExternalBrainCoordinator externalBrain,
            CompositeToolGateway toolGateway,
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
        this.plans = plans;
        this.kernel = kernel;
        this.provider = provider;
        this.providerRouter = providerRouter;
        this.externalBrain = externalBrain;
        this.toolGateway = toolGateway;
        this.webSocket = webSocket;
        this.healthServer = healthServer;
        this.maintenance = maintenance;
        this.cli = cli;
    }

    public static RuntimeApplication start(RuntimeConfig config, boolean enableCli)
            throws IOException, SQLException, InterruptedException {
        return start(config, enableCli, null, null);
    }

    /** Package-private dependency injection for deterministic protocol tests; production selects from config. */
    static RuntimeApplication start(RuntimeConfig config, boolean enableCli, ExternalBrainAdapter brainOverride)
            throws IOException, SQLException, InterruptedException {
        return start(config, enableCli, brainOverride, null);
    }

    static RuntimeApplication start(RuntimeConfig config, boolean enableCli, ExternalBrainAdapter brainOverride,
                                    SearchProvider searchOverride)
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
        AgentKernel kernel = null;
        ExternalBrainCoordinator externalBrain = null;
        CompositeToolGateway toolGateway = null;
        try {
            database.initialize();
            BrainAuditRepository brainAudit = new BrainAuditRepository(database);
            int interruptedBrainSessions = brainAudit.interruptActiveSessions();
            CompanionRepository companions = new CompanionRepository(database);
            TaskEventStore events = new TaskEventStore(database);
            TaskRepository tasks = new TaskRepository(database, events);
            AgentPlanRepository plans = new AgentPlanRepository(database);
            MemoryRepository memories = new MemoryRepository(database);
            LeaseService leases = new LeaseService(database);
            int invalidatedLeases = leases.invalidateRecoveredLeases();
            sessions = new SessionRegistry(database, companions, log);
            ConversationRepository conversationRepository = new ConversationRepository(database);
            ConversationService conversations = new ConversationService(conversationRepository, sessions, log);
            CommandService commands = new CommandService(
                    sessions,
                    companions,
                    tasks,
                    leases,
                    new IdempotencyStore(database),
                    new ProtocolCommandSender(),
                    log);
            provider = createProvider(config, redactor, log);
            ProviderRouter providerRouter = new ProviderRouter(new RuleIntentParser(), provider, log);
            CapabilityVisibility capabilityVisibility = new CapabilityVisibility(CapabilityRegistry.standard());
            SessionRegistry activeSessionRegistry = sessions;
            RuntimeToolGateway minecraftTools = new RuntimeToolGateway(commands, companions, tasks, companionId -> {
                try {
                    var companion = companions.get(companionId).orElse(null);
                    if (companion == null) return java.util.List.of();
                    var session = activeSessionRegistry.forCompanion(companionId).orElse(null);
                    return capabilityVisibility.resolve(session == null ? null : session.handshake(),
                            companion.status()).availableNames();
                } catch (java.sql.SQLException failure) {
                    return java.util.List.of();
                }
            });
            SearchProvider searchProvider = searchOverride == null ? createSearchProvider(config, redactor, log) : searchOverride;
            toolGateway = new CompositeToolGateway(java.util.List.of(minecraftTools,
                    new MemoryToolGateway(memories), new SearchToolGateway(searchProvider,
                    config.search.allowedDomains, config.search.deniedDomains)));
            externalBrain = brainOverride == null
                    ? createExternalBrain(config, redactor, log, toolGateway, brainAudit)
                    : new ExternalBrainCoordinator(brainOverride, toolGateway,
                    config.brain.maxToolCallsPerTurn, brainAudit);
            kernel = new AgentKernel(plans, commands, log, providerRouter, companions, sessions,
                    capabilityVisibility, memories, conversations);
            commands.setTaskLifecycleListener(kernel);
            sessions.setListener(commands);

            int staleSessions = sessions.recoverStaleSessions();
            int reconciliationTasks = tasks.markUnfinishedForReconciliation().size();
            int recoveryPlans = plans.pauseRunningForRecovery();
            if (staleSessions > 0 || reconciliationTasks > 0 || invalidatedLeases > 0
                    || recoveryPlans > 0 || interruptedBrainSessions > 0) {
                log.warn("Startup reconciliation queued: staleSessions=" + staleSessions
                        + ", unfinishedTasks=" + reconciliationTasks
                        + ", invalidatedLeases=" + invalidatedLeases + ", pausedPlans=" + recoveryPlans
                        + ", interruptedBrainSessions=" + interruptedBrainSessions);
            }

            webSocket = new RuntimeWebSocketServer(
                    new InetSocketAddress(config.server.bind, config.server.port),
                    pairingToken,
                    sessions,
                    commands,
                    companions,
                    providerRouter,
                    plans,
                    kernel,
                    capabilityVisibility,
                    memories,
                    conversations,
                    externalBrain,
                    log);
            webSocket.startAndAwait(Duration.ofSeconds(15));
            healthServer = new RuntimeHealthServer(config, pairingToken, sessions, commands, companions, plans,
                    kernel, providerRouter, capabilityVisibility, conversations, memories, externalBrain, brainAudit, log);
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
                cli = new RuntimeCli(companions, sessions, commands, providerRouter, capabilityVisibility, () -> {
                    RuntimeApplication application = holder[0];
                    if (application != null) {
                        application.close();
                    }
                }, System.in, System.out);
            }
            RuntimeApplication application = new RuntimeApplication(config, log, database, companions, sessions,
                    commands, plans, kernel, provider, providerRouter, externalBrain, toolGateway,
                    webSocket, healthServer, maintenance, cli);
            holder[0] = application;
            log.info("Minecraft AI Companion Runtime started: protocol=mc-companion/1, provider="
                    + (provider == null ? "rules" : "openai-compatible")
                    + ", externalBrain=" + (externalBrain == null ? "disabled"
                    : brainOverride == null ? config.brain.mode : "injected-replay")
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
            closeQuietly(kernel);
            closeQuietly(externalBrain);
            closeQuietly(toolGateway);
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
            OpenAiCompatibleProvider remote = new OpenAiCompatibleProvider(
                    config.provider.baseUrl,
                    key,
                    config.provider.model,
                    config.provider.timeout(),
                    config.provider.maxOutputTokens);
            return new BudgetedProvider(remote, config.provider.maxConcurrent,
                    config.provider.maxCallsPerMinute, config.provider.maxRetries);
        } catch (RuntimeException invalidProvider) {
            log.error("Provider configuration was rejected; Runtime is using rules fallback", invalidProvider);
            return null;
        }
    }

    private static ExternalBrainCoordinator createExternalBrain(RuntimeConfig config, Redactor redactor,
                                                                 RuntimeLog log, ToolGateway tools,
                                                                 BrainAuditRepository brainAudit) {
        if ("disabled".equals(config.brain.mode)) return null;
        String token = config.brain.resolveToken().orElse(null);
        if (token == null) {
            log.warn("External Brain token environment variable is absent; Brain Bridge is disabled");
            return null;
        }
        redactor.registerSecret(token);
        ExternalBrainAdapter adapter = switch (config.brain.mode) {
            case "hermes" -> new HermesBrainAdapter(config.brain.endpoint, token, config.brain.timeout());
            case "openai-compatible" -> new OpenAiCompatibleBrainAdapter(config.brain.endpoint, token,
                    config.brain.model, config.brain.timeout(), config.brain.maxOutputTokens);
            default -> throw new IllegalArgumentException("Unsupported external Brain mode");
        };
        return new ExternalBrainCoordinator(adapter, tools, config.brain.maxToolCallsPerTurn, brainAudit);
    }

    private static SearchProvider createSearchProvider(RuntimeConfig config, Redactor redactor, RuntimeLog log) {
        if ("disabled".equals(config.search.mode)) return new DisabledSearchProvider();
        String token = config.search.resolveToken().orElse(null);
        if (token == null) {
            log.warn("Search token environment variable is absent; Search Gateway is disabled");
            return new DisabledSearchProvider();
        }
        redactor.registerSecret(token);
        return new HttpSearchProvider(config.search.endpoint, token, config.search.timeout());
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

    public AgentPlanRepository plans() { return plans; }

    public ProviderRouter providerRouter() {
        return providerRouter;
    }

    public java.util.Optional<ExternalBrainCoordinator> externalBrain() {
        return java.util.Optional.ofNullable(externalBrain);
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
        closeQuietly(kernel);
        closeQuietly(externalBrain);
        closeQuietly(toolGateway);
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
