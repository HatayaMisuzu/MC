package com.mccompanion.minecraft.v121;

import com.mccompanion.minecraft.bridge.EntityEventTracker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;

/** Adapts bounded real Minecraft 1.21.1 entity facts to the shared edge detector. */
public final class EntityEventObservationService {
    public static final double ATTENTION_RADIUS = 16.0D;
    public static final double THREAT_RADIUS = 8.0D;

    private final MinecraftServer server;

    public EntityEventObservationService(MinecraftServer server) {
        this.server = java.util.Objects.requireNonNull(server, "server");
    }

    public EntityEventTracker.Snapshot snapshot(CompanionPlayer body, String behaviorId,
                                                EntityEventTracker.TargetBinding target,
                                                long tick, Instant observedAt) {
        java.util.Objects.requireNonNull(body, "body");
        List<EntityEventTracker.EntityFact> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == body || player instanceof CompanionPlayer || player.serverLevel() != body.serverLevel()) {
                continue;
            }
            double distance = player.distanceToSqr(body);
            if (distance <= ATTENTION_RADIUS * ATTENTION_RADIUS) players.add(fact(player, distance));
        }
        List<EntityEventTracker.EntityFact> hostiles = body.serverLevel().getEntities(
                        body, body.getBoundingBox().inflate(THREAT_RADIUS),
                        entity -> entity.isAlive() && entity instanceof Enemy)
                .stream().map(entity -> fact(entity, entity.distanceToSqr(body))).toList();
        return new EntityEventTracker.Snapshot(body.getUUID().toString(), behaviorId, tick, observedAt,
                players, hostiles, target(body, target));
    }

    private EntityEventTracker.TargetFact target(CompanionPlayer body,
                                                  EntityEventTracker.TargetBinding binding) {
        if (binding == null) return null;
        Entity entity = server.getPlayerList().getPlayer(java.util.UUID.fromString(binding.identity()));
        if (entity == null) entity = body.serverLevel().getEntity(java.util.UUID.fromString(binding.identity()));
        if (entity == null) return missing(binding);
        double distance = entity.level() == body.level() ? entity.distanceToSqr(body) : 0.0D;
        EntityEventTracker.TargetState state;
        if (!entity.isAlive()) state = EntityEventTracker.TargetState.DEAD;
        else if (entity.level() != body.level()) {
            state = binding.kind() == EntityEventTracker.TargetKind.FOLLOW
                    ? EntityEventTracker.TargetState.OUT_OF_RANGE : EntityEventTracker.TargetState.MISSING;
        } else if (distance <= ATTENTION_RADIUS * ATTENTION_RADIUS) {
            state = EntityEventTracker.TargetState.IN_RANGE;
        } else state = EntityEventTracker.TargetState.OUT_OF_RANGE;
        return new EntityEventTracker.TargetFact(binding.identity(), type(entity), entity.getDisplayName().getString(),
                binding.kind(), state, entity instanceof ServerPlayer, entity instanceof Enemy,
                entity.isAlive(), distance);
    }

    private static EntityEventTracker.TargetFact missing(EntityEventTracker.TargetBinding binding) {
        return new EntityEventTracker.TargetFact(binding.identity(), "minecraft:unknown", "",
                binding.kind(), EntityEventTracker.TargetState.MISSING,
                binding.kind() == EntityEventTracker.TargetKind.FOLLOW, false, false, 0.0D);
    }

    private static EntityEventTracker.EntityFact fact(Entity entity, double distance) {
        return new EntityEventTracker.EntityFact(entity.getUUID().toString(), type(entity),
                entity.getDisplayName().getString(), entity instanceof ServerPlayer,
                entity instanceof Enemy, entity.isAlive(), distance);
    }

    private static String type(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }
}
