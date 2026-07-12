package com.mccompanion.minecraft.neoforge;

import com.mccompanion.minecraft.bootstrap.BootstrapCapabilityReport;
import com.mccompanion.minecraft.bootstrap.BootstrapCommandContract;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MinecraftAiCompanionNeoForge.MOD_ID)
public final class MinecraftAiCompanionNeoForge {
    public static final String MOD_ID = "minecraft_ai_companion";
    public static final String MOD_VERSION = "0.1.0-alpha";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final BootstrapCapabilityReport CAPABILITIES = BootstrapCapabilityReport.minimal(
            MOD_VERSION,
            "1.21.1",
            "neoforge",
            "21.1.235",
            21);

    public MinecraftAiCompanionNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        LOGGER.info("Minecraft AI Companion loaded in offline-safe bootstrap mode; Runtime is optional");
        LOGGER.info("capability_report={}", CAPABILITIES.toJson());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(BootstrapCommandContract.ROOT)
                .then(Commands.literal("status")
                        .executes(context -> sendStatus(context.getSource())))
                .then(Commands.literal("capabilities")
                        .executes(context -> sendCapabilities(context.getSource())))
                .then(Commands.literal("help")
                        .executes(context -> sendHelp(context.getSource())))
                .then(unavailable("create"))
                .then(unavailable("spawn"))
                .then(unavailable("despawn"))
                .then(unavailable("remove"))
                .then(unavailable("follow"))
                .then(Commands.literal("goto")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(context -> sendUnavailable(
                                                        context.getSource(), "goto"))))))
                .then(unavailable("stop"))
                .then(unavailable("pause"))
                .then(unavailable("resume")));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> unavailable(
            String command) {
        return Commands.literal(command)
                .requires(source -> source.hasPermission(2))
                .executes(context -> sendUnavailable(context.getSource(), command));
    }

    private static int sendStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(CAPABILITIES.statusLine()), false);
        return 1;
    }

    private static int sendCapabilities(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(CAPABILITIES.toJson()), false);
        return 1;
    }

    private static int sendHelp(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(BootstrapCommandContract.helpText()), false);
        return 1;
    }

    private static int sendUnavailable(CommandSourceStack source, String command) {
        source.sendFailure(Component.literal(BootstrapCommandContract.unavailableMessage(command)));
        return 0;
    }
}
