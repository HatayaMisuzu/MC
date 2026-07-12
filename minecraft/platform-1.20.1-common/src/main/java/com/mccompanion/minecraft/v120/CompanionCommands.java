package com.mccompanion.minecraft.v120;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class CompanionCommands {
    private CompanionCommands() {
    }

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            Function<CommandSourceStack, CompanionRegistry> registryLookup,
            String capabilityJson) {
        dispatcher.register(Commands.literal("companion")
                .then(Commands.literal("status")
                        .executes(context -> status(context, registryLookup)))
                .then(Commands.literal("capabilities")
                        .executes(context -> success(context.getSource(), capabilityJson)))
                .then(Commands.literal("runtime")
                        .executes(context -> runtimeStatus(context, registryLookup)))
                .then(Commands.literal("help")
                        .executes(context -> success(context.getSource(), helpText())))
                .then(Commands.literal("create")
                        .executes(context -> ownerCommand(context, registryLookup, (registry, owner) ->
                                registry.create(owner, null)))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> ownerCommand(context, registryLookup, (registry, owner) ->
                                        registry.create(owner, StringArgumentType.getString(context, "name"))))))
                .then(Commands.literal("spawn")
                        .executes(context -> ownerCommand(context, registryLookup, CompanionRegistry::spawn)))
                .then(Commands.literal("despawn")
                        .executes(context -> ownerCommand(context, registryLookup, CompanionRegistry::despawn)))
                .then(Commands.literal("remove")
                        .executes(context -> ownerCommand(context, registryLookup, CompanionRegistry::remove)))
                .then(Commands.literal("follow")
                        .executes(context -> ownerCommand(context, registryLookup, CompanionRegistry::follow)))
                .then(Commands.literal("come")
                        .executes(context -> ownerCommand(context, registryLookup, CompanionRegistry::come)))
                .then(Commands.literal("goto")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(context -> ownerCommand(context, registryLookup,
                                                        (registry, owner) -> registry.goTo(
                                                                owner,
                                                                DoubleArgumentType.getDouble(context, "x"),
                                                                DoubleArgumentType.getDouble(context, "y"),
                                                                DoubleArgumentType.getDouble(context, "z"))))))))
                .then(Commands.literal("stop")
                        .executes(context -> ownerCommand(context, registryLookup, CompanionRegistry::stop)))
                .then(Commands.literal("pause")
                        .executes(context -> ownerCommand(context, registryLookup, CompanionRegistry::pause)))
                .then(Commands.literal("resume")
                        .executes(context -> ownerCommand(context, registryLookup, CompanionRegistry::resume))));
    }

    private static int status(
            CommandContext<CommandSourceStack> context,
            Function<CommandSourceStack, CompanionRegistry> registryLookup) {
        CompanionRegistry registry = registryLookup.apply(context.getSource());
        if (registry == null) {
            context.getSource().sendFailure(Component.literal("SERVER_NOT_READY: companion registry is not initialized."));
            return 0;
        }
        ServerPlayer player = context.getSource().getPlayer();
        return success(context.getSource(), player == null ? registry.globalStatus() : registry.status(player));
    }

    private static int runtimeStatus(
            CommandContext<CommandSourceStack> context,
            Function<CommandSourceStack, CompanionRegistry> registryLookup) {
        CompanionRegistry registry = registryLookup.apply(context.getSource());
        return registry == null
                ? success(context.getSource(), "runtime=OFFLINE server=NOT_READY")
                : success(context.getSource(), registry.globalStatus());
    }

    private static int ownerCommand(
            CommandContext<CommandSourceStack> context,
            Function<CommandSourceStack, CompanionRegistry> registryLookup,
            OwnerOperation operation) throws CommandSyntaxException {
        ServerPlayer owner = context.getSource().getPlayerOrException();
        CompanionRegistry registry = registryLookup.apply(context.getSource());
        if (registry == null) {
            context.getSource().sendFailure(Component.literal("SERVER_NOT_READY: companion registry is not initialized."));
            return 0;
        }
        CompanionRegistry.Result result = operation.apply(registry, owner);
        if (result.success()) {
            context.getSource().sendSuccess(result::component, false);
            return 1;
        }
        context.getSource().sendFailure(result.component());
        return 0;
    }

    private static int success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static String helpText() {
        return "/companion create [name] | spawn | despawn | remove | follow | come | goto <x> <y> <z> | "
                + "stop | pause | resume | status | runtime | capabilities. Mutating commands control only your own companion.";
    }

    @FunctionalInterface
    private interface OwnerOperation {
        CompanionRegistry.Result apply(CompanionRegistry registry, ServerPlayer owner);
    }
}
