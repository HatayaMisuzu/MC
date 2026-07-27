package com.mccompanion.minecraft.forge;

import com.mccompanion.minecraft.bootstrap.BootstrapCapabilityReport;
import com.mccompanion.minecraft.v120.CompanionCommands;
import com.mccompanion.minecraft.v120.CompanionRegistry;
import com.mccompanion.minecraft.v120.ForgePersistenceRestartProbe;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
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
            "RUNTIME_OPTIONAL_LOCAL_CONTROL",
            false,
            "SERVER_PLAYER_BODY",
            "FOLLOW_GOTO_BOUNDED_PRIMITIVES_PAUSE_RESUME_STOP",
            "VANILLA_PLAYER_DATA_PLUS_SAVED_DATA",
            "VANILLA_PLAYER_TRAVEL_INTERACTION_MENU",
            List.of("status", "capabilities", "help", "create", "spawn", "despawn", "remove",
                    "follow", "come", "goto", "stop", "pause", "resume", "runtime", "mcac",
                    "registry_query", "recipe_query", "primitive_observation_query",
                    "owner_activity_handoff", "player_text_gateway", "look_at", "interact_block",
                    "interact_entity", "menu_action", "use_item", "drop_item", "attack_entity",
                    "place_block", "resource_collect", "resource_mine", "storage_transfer",
                    "inventory_deliver", "eat_and_recover", "defend_owner", "retreat",
                    "composite_crafting", "composite_smelting", "movement_step",
                    "world_scan"),
            List.of(),
            List.of());
    private static volatile MinecraftServer activeServer;
    private static volatile CompanionRegistry registry;
    private static volatile RuntimeBridge runtimeBridge;

    public MinecraftAiCompanionForge() {
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onRightClickBlock);
        MinecraftForge.EVENT_BUS.addListener(this::onBlockBreak);
        LOGGER.info("Minecraft AI Companion loaded with server-player companion control; Runtime is optional");
        LOGGER.info("capability_report={}", CAPABILITIES.toJson());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CompanionCommands.register(
                event.getDispatcher(),
                source -> registryFor(source.getServer()),
                CAPABILITIES.toJson(),
                MinecraftAiCompanionForge::submitPlayerText);
    }

    private void onServerStarted(ServerStartedEvent event) {
        activeServer = event.getServer();
        CompanionRegistry next = new CompanionRegistry(activeServer, LOGGER);
        registry = next;
        next.start();
        ForgePersistenceRestartProbe.begin(activeServer, next, LOGGER);
        runtimeBridge = RuntimeBridge.start(activeServer, next, LOGGER);
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        CompanionRegistry current = registryFor(event.getServer());
        if (current != null) {
            current.tick();
            ForgePersistenceRestartProbe.tick(event.getServer(), current, LOGGER);
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        RuntimeBridge bridge = runtimeBridge;
        runtimeBridge = null;
        if (bridge != null) {
            bridge.close();
        }
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

    private void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer owner) {
            notifyOwnerBlockActivity(owner, event.getPos(), "BLOCK_USE");
        }
    }

    private void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer owner) {
            notifyOwnerBlockActivity(owner, event.getPos(), "BLOCK_BREAK");
        }
    }

    private static CompanionCommands.TextRequestResult submitPlayerText(ServerPlayer owner, String text) {
        RuntimeBridge bridge = runtimeBridge;
        return bridge == null
                ? new CompanionCommands.TextRequestResult(
                        false,
                        "Runtime 未连接；状态、暂停、继续和取消仍可使用本地命令。")
                : bridge.submitPlayerText(owner, text);
    }

    private static void notifyOwnerBlockActivity(ServerPlayer owner, BlockPos position, String activityType) {
        RuntimeBridge bridge = runtimeBridge;
        if (bridge != null) {
            bridge.submitOwnerBlockActivity(owner, position, activityType);
        }
    }

    /** Public only for the loader's headless integration-test class. */
    public static CompanionRegistry integrationRegistryFor(MinecraftServer server) {
        return registryFor(server);
    }

    /** Public only for the loader's authenticated Runtime E2E GameTest. */
    public static CompanionCommands.TextRequestResult integrationSubmitPlayerText(
            ServerPlayer owner,
            String text) {
        return submitPlayerText(owner, text);
    }

    /** Public only for the loader's authenticated Runtime E2E GameTest. */
    public static void integrationSubmitOwnerBlockActivity(
            ServerPlayer owner,
            BlockPos position,
            String activityType) {
        notifyOwnerBlockActivity(owner, position, activityType);
    }
}
