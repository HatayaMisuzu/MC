package com.mccompanion.minecraft.neoforge;

import com.mccompanion.minecraft.bootstrap.BootstrapCapabilityReport;
import com.mccompanion.minecraft.v121.CompanionCommands;
import com.mccompanion.minecraft.v121.CompanionRegistry;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MinecraftAiCompanionNeoForge.MOD_ID)
public final class MinecraftAiCompanionNeoForge {
    public static final String MOD_ID = "minecraft_ai_companion";
    public static final String MOD_VERSION = "0.1.0-alpha";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final BootstrapCapabilityReport CAPABILITIES = new BootstrapCapabilityReport(
            MOD_VERSION,
            "1.21.1",
            "neoforge",
            "21.1.235",
            21,
            "OFFLINE_LOCAL_CONTROL",
            false,
            "SERVER_PLAYER_BODY",
            "FOLLOW_GOTO_PAUSE_RESUME_STOP",
            "VANILLA_PLAYER_DATA_PLUS_SAVED_DATA",
            "PLAYER_TRAVEL_ONLY",
            List.of("status", "capabilities", "help", "create", "spawn", "despawn", "remove",
                    "follow", "come", "goto", "stop", "pause", "resume", "runtime"),
            List.of("runtime_control_lease"),
            List.of());
    private static volatile MinecraftServer activeServer;
    private static volatile CompanionRegistry registry;

    public MinecraftAiCompanionNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        LOGGER.info("Minecraft AI Companion loaded with server-player companion control; Runtime is optional");
        LOGGER.info("capability_report={}", CAPABILITIES.toJson());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CompanionCommands.register(event.getDispatcher(), source -> registryFor(source.getServer()), CAPABILITIES.toJson());
    }

    private void onServerStarted(ServerStartedEvent event) {
        activeServer = event.getServer();
        CompanionRegistry next = new CompanionRegistry(activeServer, LOGGER);
        registry = next;
        next.start();
    }

    private void onServerTick(ServerTickEvent.Post event) {
        CompanionRegistry current = registryFor(event.getServer());
        if (current != null) {
            current.tick();
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        CompanionRegistry current = registryFor(event.getServer());
        if (current != null) {
            current.shutdown();
        }
        registry = null;
        activeServer = null;
    }

    private static CompanionRegistry registryFor(MinecraftServer server) {
        return server == activeServer ? registry : null;
    }

    /** Public only for the loader's headless integration-test class. */
    public static CompanionRegistry integrationRegistryFor(MinecraftServer server) {
        return registryFor(server);
    }
}
