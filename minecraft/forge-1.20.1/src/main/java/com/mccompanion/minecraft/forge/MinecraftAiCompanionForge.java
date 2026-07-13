package com.mccompanion.minecraft.forge;

import com.mccompanion.minecraft.bootstrap.BootstrapCapabilityReport;
import com.mccompanion.minecraft.v120.CompanionCommands;
import com.mccompanion.minecraft.v120.CompanionRegistry;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MinecraftAiCompanionForge.MOD_ID)
public final class MinecraftAiCompanionForge {
    public static final String MOD_ID = "minecraft_ai_companion";
    public static final String MOD_VERSION = "0.3.0";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final BootstrapCapabilityReport CAPABILITIES = new BootstrapCapabilityReport(
            MOD_VERSION,
            "1.20.1",
            "forge",
            "47.4.10",
            17,
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

    public MinecraftAiCompanionForge() {
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
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

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
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
