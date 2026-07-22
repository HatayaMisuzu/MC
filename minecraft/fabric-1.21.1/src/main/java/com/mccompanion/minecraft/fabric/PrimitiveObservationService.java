package com.mccompanion.minecraft.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.minecraft.v121.CompanionPlayer;
import com.mccompanion.minecraft.v121.CompanionRegistry;
import com.mccompanion.minecraft.v121.MenuSessionTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

/** Read-only bounded observations of the actual connected body inventory and nearby world. */
public final class PrimitiveObservationService {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PrimitiveObservationService() { }

    public static Result inspect(CompanionRegistry registry, String companionId, JsonNode arguments) {
        try {
            CompanionPlayer body = registry.runtimeBody(companionId);
            if (body == null) return failure("COMPANION_NOT_SPAWNED", "Connected body is not spawned");
            return switch (arguments.path("tool").asText("")) {
                case "block.inspect" -> block(body, arguments.path("position"));
                case "item.inspect" -> item(body, arguments.path("item").asText(""));
                case "entity.inspect" -> entities(body, arguments);
                case "menu.inspect" -> menu(body);
                default -> failure("OBSERVATION_TOOL_UNKNOWN", "Observation tool is unsupported");
            };
        } catch (RuntimeException invalid) {
            return failure("OBSERVATION_QUERY_INVALID", invalid.getMessage());
        }
    }

    private static Result block(CompanionPlayer body, JsonNode position) {
        BlockPos target = position(position);
        String dimension = position.path("dimension").asText(body.serverLevel().dimension().location().toString());
        if (!dimension.equals(body.serverLevel().dimension().location().toString())) {
            return failure("WORLD_CHANGED", "Target dimension does not match the connected body");
        }
        if (!body.serverLevel().hasChunkAt(target)) {
            return failure("CHUNK_NOT_LOADED", "Target block is outside loaded world state");
        }
        if (body.distanceToSqr(Vec3.atCenterOf(target)) > 16.0D * 16.0D) {
            return failure("BLOCK_OUT_OF_OBSERVATION_RANGE", "Target block is more than 16 blocks away");
        }
        var hit = body.serverLevel().clip(new ClipContext(body.getEyePosition(), Vec3.atCenterOf(target),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
        if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(target)) {
            return failure("BLOCK_NOT_VISIBLE", "Another block occludes the target");
        }
        var state = body.serverLevel().getBlockState(target);
        ObjectNode observation = envelope(body, "BLOCK").put("visible", true);
        observation.set("position", position(target, dimension));
        observation.put("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())
                .put("air", state.isAir())
                .put("destroySpeed", state.getDestroySpeed(body.serverLevel(), target))
                .put("collisionEmpty", state.getCollisionShape(body.serverLevel(), target).isEmpty())
                .put("replaceable", state.canBeReplaced());
        ObjectNode properties = observation.putObject("properties");
        state.getValues().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().getName()))
                .limit(32)
                .forEach(entry -> properties.put(entry.getKey().getName(), String.valueOf(entry.getValue())));
        observation.put("propertiesTruncated", state.getValues().size() > 32);
        ResourceLocation fluid = BuiltInRegistries.FLUID.getKey(state.getFluidState().getType());
        if (fluid != null) observation.put("fluid", fluid.toString());
        return new Result(true, "OK", observation);
    }

    private static Result item(CompanionPlayer body, String rawItem) {
        ResourceLocation id = ResourceLocation.tryParse(rawItem);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return failure("ITEM_ID_NOT_FOUND", "Item identifier is not present in the live Registry");
        }
        var item = BuiltInRegistries.ITEM.get(id);
        ObjectNode observation = envelope(body, "ITEM").put("item", id.toString())
                .put("maxStackSize", item.getDefaultInstance().getMaxStackSize());
        ArrayNode tags = observation.putArray("tags");
        var tagValues = item.builtInRegistryHolder().tags().map(tag -> tag.location().toString())
                .sorted().limit(65).toList();
        tagValues.stream().limit(64).forEach(tags::add);
        observation.put("tagsTruncated", tagValues.size() > 64);
        ArrayNode componentTypes = observation.putArray("observableComponentTypes");
        var componentValues = item.getDefaultInstance().getComponents().stream()
                .map(component -> BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type()))
                .filter(java.util.Objects::nonNull).map(ResourceLocation::toString).sorted().limit(33).toList();
        componentValues.stream().limit(32).forEach(componentTypes::add);
        observation.put("componentsTruncated", componentValues.size() > 32);
        ArrayNode slots = observation.putArray("slots");
        int total = 0;
        for (int slot = 0; slot < body.getInventory().getContainerSize(); slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            if (!stack.is(item)) continue;
            total += stack.getCount();
            slots.add(JSON.createObjectNode().put("slot", slot).put("count", stack.getCount())
                    .put("selected", slot == body.getInventory().selected)
                    .put("damage", stack.getDamageValue()).put("maxDamage", stack.getMaxDamage()));
        }
        observation.put("totalCount", total).put("slotCount", slots.size()).put("present", total > 0);
        return new Result(true, "OK", observation);
    }

    private static Result entities(CompanionPlayer body, JsonNode arguments) {
        double radius = arguments.path("radius").asDouble();
        int limit = arguments.path("limit").asInt(32);
        String type = arguments.path("type").asText("");
        String entityId = arguments.path("entityId").asText("");
        if (!Double.isFinite(radius) || radius < 1.0D || radius > 16.0D) {
            return failure("OBSERVATION_RADIUS_INVALID", "Entity radius must be between 1 and 16 blocks");
        }
        if (limit < 1 || limit > 32) {
            return failure("OBSERVATION_LIMIT_INVALID", "Entity result limit must be between 1 and 32");
        }
        if (!type.isBlank()) {
            ResourceLocation typeId = ResourceLocation.tryParse(type);
            if (typeId == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(typeId)) {
                return failure("ENTITY_TYPE_NOT_FOUND", "Entity type is not present in the live Registry");
            }
        }
        UUID exact = entityId.isBlank() ? null : UUID.fromString(entityId);
        var matches = body.serverLevel().getEntities(body, body.getBoundingBox().inflate(radius), entity -> {
                    if (!entity.isAlive() || !body.hasLineOfSight(entity)) return false;
                    ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
                    if (!type.isBlank() && (id == null || !id.toString().equals(type))) return false;
                    return exact == null || entity.getUUID().equals(exact);
                }).stream()
                .sorted(Comparator.comparingDouble(entity -> entity.distanceToSqr(body)))
                .toList();
        ObjectNode observation = envelope(body, "ENTITY").put("radius", radius)
                .put("totalMatches", matches.size()).put("limit", limit)
                .put("truncated", matches.size() > limit);
        if (!type.isBlank()) observation.put("type", type);
        if (exact != null) observation.put("entityId", exact.toString());
        ArrayNode entries = observation.putArray("entities");
        matches.stream().limit(limit).forEach(entity -> entries.add(entity(body, entity)));
        return new Result(true, "OK", observation);
    }

    private static ObjectNode entity(CompanionPlayer body, Entity entity) {
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        ObjectNode value = JSON.createObjectNode().put("entityId", entity.getUUID().toString())
                .put("type", type == null ? "minecraft:unknown" : type.toString())
                .put("x", entity.getX()).put("y", entity.getY()).put("z", entity.getZ())
                .put("distance", Math.sqrt(entity.distanceToSqr(body)))
                .put("alive", entity.isAlive()).put("visible", true);
        if (entity instanceof LivingEntity living) {
            value.put("health", living.getHealth()).put("maxHealth", living.getMaxHealth());
        }
        if (entity instanceof ItemEntity dropped) {
            ItemStack stack = dropped.getItem();
            value.put("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                    .put("count", stack.getCount());
        }
        return value;
    }

    private static Result menu(CompanionPlayer body) {
        MenuSessionTracker.Snapshot session = MenuSessionTracker.inspect(
                body, body.getServer().getTickCount());
        if (session == null) {
            return failure("MENU_NOT_OPEN", "The connected body has no open container menu");
        }
        var menu = session.menu();
        ResourceLocation menuType = BuiltInRegistries.MENU.getKey(menu.getType());
        ObjectNode observation = envelope(body, "MENU")
                .put("sessionToken", session.token())
                .put("containerId", session.containerId())
                .put("expiresAtTick", session.expiresAtTick())
                .put("menuType", menuType == null ? "minecraft:unknown" : menuType.toString())
                .put("slotCount", menu.slots.size())
                .put("slotsTruncated", menu.slots.size() > 128);
        ArrayNode slots = observation.putArray("slots");
        for (int index = 0; index < Math.min(128, menu.slots.size()); index++) {
            var slot = menu.getSlot(index);
            ItemStack stack = slot.getItem();
            ObjectNode value = slots.addObject().put("slot", index)
                    .put("role", slot.container == body.getInventory()
                            ? slot.getContainerSlot() < 9 ? "PLAYER_HOTBAR" : "PLAYER_INVENTORY"
                            : "CONTAINER")
                    .put("containerSlot", slot.getContainerSlot())
                    .put("mayPlace", slot.mayPlace(ItemStack.EMPTY))
                    .put("mayPickup", slot.mayPickup(body))
                    .put("empty", stack.isEmpty());
            if (!stack.isEmpty()) {
                value.put("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                        .put("count", stack.getCount()).put("maxStackSize", stack.getMaxStackSize())
                        .put("damage", stack.getDamageValue()).put("maxDamage", stack.getMaxDamage());
            }
        }
        ItemStack carried = menu.getCarried();
        ObjectNode carriedValue = observation.putObject("carried").put("empty", carried.isEmpty());
        if (!carried.isEmpty()) {
            carriedValue.put("item", BuiltInRegistries.ITEM.getKey(carried.getItem()).toString())
                    .put("count", carried.getCount());
        }
        return new Result(true, "OK", observation);
    }

    private static BlockPos position(JsonNode value) {
        for (String field : java.util.List.of("x", "y", "z")) {
            if (!value.path(field).isIntegralNumber() || !value.path(field).canConvertToInt()) {
                throw new IllegalArgumentException("position coordinates must be integers");
            }
        }
        return new BlockPos(value.path("x").asInt(), value.path("y").asInt(), value.path("z").asInt());
    }

    private static ObjectNode position(BlockPos value, String dimension) {
        return JSON.createObjectNode().put("dimension", dimension)
                .put("x", value.getX()).put("y", value.getY()).put("z", value.getZ());
    }

    private static ObjectNode envelope(CompanionPlayer body, String kind) {
        return JSON.createObjectNode().put("source", "LIVE_SERVER_OBSERVATION")
                .put("kind", kind).put("verified", true)
                .put("companionId", body.getUUID().toString())
                .put("dimension", body.serverLevel().dimension().location().toString())
                .put("observedAt", Instant.now().toString());
    }

    private static Result failure(String code, String message) {
        return new Result(false, code, JSON.createObjectNode()
                .put("source", "LIVE_SERVER_OBSERVATION").put("verified", false)
                .put("observedAt", Instant.now().toString())
                .put("message", message == null || message.isBlank() ? code : message));
    }

    public record Result(boolean success, String code, ObjectNode observation) { }
}
