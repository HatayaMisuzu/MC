package com.mccompanion.minecraft.fabric;

import com.mccompanion.minecraft.bootstrap.BootstrapCapabilityReport;
import com.mccompanion.minecraft.v121.CompanionCommands;
import com.mccompanion.minecraft.v121.CompanionRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MinecraftAiCompanionFabric implements ModInitializer {
    public static final String MOD_ID = "minecraft_ai_companion";
    public static final String MOD_VERSION = "0.1.0-alpha";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final BootstrapCapabilityReport CAPABILITIES = new BootstrapCapabilityReport(
            MOD_VERSION,
            "1.21.1",
            "fabric",
            "0.19.3",
            21,
            "OFFLINE_LOCAL_CONTROL",
            false,
            "SERVER_PLAYER_BODY",
            "FOLLOW_GOTO_PAUSE_RESUME_STOP",
            "VANILLA_PLAYER_DATA_PLUS_SAVED_DATA",
            "PLAYER_TRAVEL_ONLY",
            java.util.List.of(
                    "status", "capabilities", "help", "create", "spawn", "despawn", "remove",
                    "follow", "come", "goto", "stop", "pause", "resume", "runtime"),
            java.util.List.of(),
            java.util.List.of());
    private static volatile MinecraftServer activeServer;
    private static volatile CompanionRegistry registry;
    private static volatile RuntimeBridge runtimeBridge;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                CompanionCommands.register(dispatcher, source -> registryFor(source.getServer()), CAPABILITIES.toJson()));
        ServerLifecycleEvents.SERVER_STARTED.register(MinecraftAiCompanionFabric::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(MinecraftAiCompanionFabric::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(MinecraftAiCompanionFabric::onServerStopping);
        LOGGER.info("Minecraft AI Companion Fabric initialized; Runtime is optional and local companion control remains available offline");
        LOGGER.info("capability_report={}", CAPABILITIES.toJson());
    }

    private static void onServerStarted(MinecraftServer server) {
        activeServer = server;
        CompanionRegistry nextRegistry = new CompanionRegistry(server, LOGGER);
        registry = nextRegistry;
        nextRegistry.start();
        runtimeBridge = RuntimeBridge.start(server, nextRegistry, LOGGER);
    }

    private static void onServerTick(MinecraftServer server) {
        CompanionRegistry current = registryFor(server);
        if (current != null) {
            current.tick();
        }
    }

    private static void onServerStopping(MinecraftServer server) {
        RuntimeBridge bridge = runtimeBridge;
        runtimeBridge = null;
        if (bridge != null) {
            bridge.close();
        }
        CompanionRegistry current = registryFor(server);
        if (current != null) {
            current.shutdown();
        }
        registry = null;
        activeServer = null;
    }

    private static CompanionRegistry registryFor(MinecraftServer server) {
        return server == activeServer ? registry : null;
    }

    /** Package-scoped access for the isolated headless integration-test mod. */
    public static CompanionRegistry integrationRegistryFor(MinecraftServer server) {
        return registryFor(server);
    }
}
