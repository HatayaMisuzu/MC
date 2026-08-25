package com.mccompanion.minecraft.v120;

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
    private final java.util.Map<UUID, String> actionPaths = new HashMap<>();

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
        actionPaths.put(body.getUUID(), "VANILLA_ENTITY_LOOK");
    }

    void markVanillaGameModeAction(CompanionPlayer body) {
        actionPaths.put(body.getUUID(), "VANILLA_SERVER_PLAYER_GAME_MODE");
    }

    void markVanillaEntityInteraction(CompanionPlayer body) {
        actionPaths.put(body.getUUID(), "VANILLA_SERVER_PLAYER_INTERACTION");
    }

    void markVanillaAttack(CompanionPlayer body) {
        actionPaths.put(body.getUUID(), "VANILLA_SERVER_PLAYER_ATTACK");
    }

    void markVanillaMenuAction(CompanionPlayer body) {
        actionPaths.put(body.getUUID(), "VANILLA_CONTAINER_MENU");
    }

    void markVanillaDrop(CompanionPlayer body) {
        actionPaths.put(body.getUUID(), "VANILLA_SERVER_PLAYER_DROP");
    }

    void selectHotbarSlot(CompanionPlayer body, int slot) {
        body.getInventory().selected = slot;
        actionPaths.put(body.getUUID(), "VANILLA_HOTBAR_INPUT");
    }

    void applyVehicleInput(CompanionPlayer body, net.minecraft.world.entity.vehicle.Boat boat,
                           float yaw, boolean left, boolean right, boolean forward, boolean back) {
        float appliedYaw = net.minecraft.util.Mth.approachDegrees(boat.getYRot(), yaw, 4.0F);
        body.setYRot(appliedYaw);
        body.setYHeadRot(appliedYaw);
        boat.setYRot(appliedYaw);
        boat.setInput(left, right, forward, back);
        boat.setPaddleState(forward && !right, forward && !left);
        if (forward || back) {
            double thrust = forward ? 0.04D : -0.005D;
            float radians = appliedYaw * net.minecraft.util.Mth.DEG_TO_RAD;
            net.minecraft.world.phys.Vec3 movement = boat.getDeltaMovement().add(
                    -net.minecraft.util.Mth.sin(radians) * thrust, 0.0D,
                    net.minecraft.util.Mth.cos(radians) * thrust);
            double horizontal = movement.x * movement.x + movement.z * movement.z;
            if (horizontal > 0.1225D) {
                double scale = 0.35D / Math.sqrt(horizontal);
                movement = new net.minecraft.world.phys.Vec3(
                        movement.x * scale, movement.y, movement.z * scale);
            }
            boat.setDeltaMovement(movement);
            boat.move(net.minecraft.world.entity.MoverType.SELF, movement);
        } else {
            net.minecraft.world.phys.Vec3 movement = boat.getDeltaMovement();
            boat.setDeltaMovement(0.0D, movement.y, 0.0D);
        }
        actionPaths.put(body.getUUID(), "VANILLA_VEHICLE_INPUT");
    }

    void completeBehavior(CompanionPlayer body, boolean success, String failureCode, long tick) {
        InFlight started = inFlight.remove(body.getUUID());
        if (started == null) {
            return;
        }
        String actionPath = actionPaths.remove(body.getUUID());
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
                actionPath == null ? "VANILLA_PLAYER_INPUT" : actionPath,
                false));
        while (completed.size() > MAX_COMPLETED_EVIDENCE) {
            completed.removeFirst();
        }
    }

    void discard(UUID companionId) {
        inFlight.remove(companionId);
        actionPaths.remove(companionId);
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
            hash = 31 * hash + (stack.hasTag() ? stack.getTag().hashCode() : 0);
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
