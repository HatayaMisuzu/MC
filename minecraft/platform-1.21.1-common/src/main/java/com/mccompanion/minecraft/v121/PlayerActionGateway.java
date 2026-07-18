package com.mccompanion.minecraft.v121;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/**
 * The only normal-behavior entry point that changes companion movement input.
 * It records behavior-level evidence and never calls teleport/setPos or edits world/inventory state.
 */
final class PlayerActionGateway {
    private static final int MAX_COMPLETED_EVIDENCE = 128;

    private final Map<UUID, InFlight> inFlight = new HashMap<>();
    private final Deque<ActionEvidence> completed = new ArrayDeque<>();
    private final java.util.Set<UUID> gameModeActions = new java.util.HashSet<>();
    private final java.util.Set<UUID> lookActions = new java.util.HashSet<>();

    void startBehavior(CompanionPlayer body, CompanionEntry.Mode mode, long tick) {
        completeBehavior(body, false, "SUPERSEDED", tick);
        inFlight.put(body.getUUID(), new InFlight(
                UUID.randomUUID(),
                mode.name(),
                tick,
                body.position(),
                inventoryDigest(body)));
    }

    void applyMoveInput(CompanionPlayer body, float yaw, boolean jumpRequested) {
        body.applyWalkingInput(yaw, jumpRequested);
    }

    void stopInput(CompanionPlayer body) {
        body.stopWalking();
    }

    void lookAt(CompanionPlayer body, net.minecraft.world.phys.Vec3 target) {
        body.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target);
        lookActions.add(body.getUUID());
    }

    void markVanillaGameModeAction(CompanionPlayer body) { gameModeActions.add(body.getUUID()); }

    void completeBehavior(CompanionPlayer body, boolean success, String failureCode, long tick) {
        InFlight started = inFlight.remove(body.getUUID());
        if (started == null) {
            return;
        }
        completed.addLast(new ActionEvidence(
                started.actionId,
                body.getUUID(),
                started.behavior,
                started.startTick,
                tick,
                started.beforePosition,
                body.position(),
                started.beforeInventoryDigest,
                inventoryDigest(body),
                success,
                success ? "NONE" : failureCode,
                gameModeActions.remove(body.getUUID()) ? "VANILLA_SERVER_PLAYER_GAME_MODE"
                        : lookActions.remove(body.getUUID()) ? "VANILLA_ENTITY_LOOK"
                        : "VANILLA_PLAYER_INPUT",
                false));
        while (completed.size() > MAX_COMPLETED_EVIDENCE) {
            completed.removeFirst();
        }
    }

    void discard(UUID companionId) {
        inFlight.remove(companionId);
        gameModeActions.remove(companionId);
        lookActions.remove(companionId);
    }

    String evidenceSummary(UUID companionId) {
        ActionEvidence latest = null;
        for (ActionEvidence evidence : completed) {
            if (evidence.companionId().equals(companionId)) {
                latest = evidence;
            }
        }
        if (latest == null) {
            return "evidence=NONE forbiddenWriteDetected=false";
        }
        return "evidence=" + latest.actionId()
                + " behavior=" + latest.behavior()
                + " ticks=" + latest.startTick() + ".." + latest.endTick()
                + " success=" + latest.success()
                + " failure=" + latest.failureCode()
                + " path=" + latest.playerPathUsed()
                + " forbiddenWriteDetected=" + latest.forbiddenWriteDetected();
    }

    private static int inventoryDigest(CompanionPlayer body) {
        int hash = 1;
        for (int slot = 0; slot < body.getInventory().getContainerSize(); slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            hash = 31 * hash + stack.getItem().hashCode();
            hash = 31 * hash + stack.getCount();
            hash = 31 * hash + stack.getComponents().hashCode();
        }
        return hash;
    }

    private record InFlight(
            UUID actionId,
            String behavior,
            long startTick,
            net.minecraft.world.phys.Vec3 beforePosition,
            int beforeInventoryDigest) {
    }
}
