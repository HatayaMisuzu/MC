package com.mccompanion.minecraft.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.tags.BlockTags;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Bounded read-only views over the live server registries and recipe manager. */
public final class RegistryObservationService {
    private static final ObjectMapper JSON = new ObjectMapper();

    private RegistryObservationService() { }

    public static Result registry(MinecraftServer server, JsonNode arguments) {
        try {
            String operation = arguments.path("tool").asText("").equals("registry.describe")
                    ? "DESCRIBE" : "SEARCH";
            String kind = arguments.path("kind").asText("").toUpperCase(Locale.ROOT);
            return operation.equals("DESCRIBE")
                    ? describe(server, kind, arguments.path("id").asText(""))
                    : search(server, kind, arguments.path("query").asText(""),
                    arguments.path("namespace").asText(""), arguments.path("limit").asInt(64));
        } catch (RuntimeException invalid) {
            return failure("REGISTRY_QUERY_INVALID", invalid.getMessage());
        }
    }

    public static Result recipes(MinecraftServer server, JsonNode arguments) {
        try {
            String type = arguments.path("type").asText("ANY").toUpperCase(Locale.ROOT);
            String query = arguments.path("query").asText("").toLowerCase(Locale.ROOT);
            String output = arguments.path("output").asText("");
            int limit = arguments.path("limit").asInt(32);
            if (limit < 1 || limit > 32) throw new IllegalArgumentException("limit must be 1..32");
            if (!type.equals("ANY") && !type.equals("CRAFTING") && !type.equals("SMELTING")) {
                throw new IllegalArgumentException("unsupported recipe type");
            }
            List<ObjectNode> matches = new ArrayList<>();
            if (!type.equals("SMELTING")) {
                for (RecipeHolder<CraftingRecipe> holder
                        : server.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
                    ObjectNode value = recipe(server, holder, "CRAFTING");
                    if (matches(value, query, output)) matches.add(value);
                }
            }
            if (!type.equals("CRAFTING")) {
                for (RecipeHolder<SmeltingRecipe> holder
                        : server.getRecipeManager().getAllRecipesFor(RecipeType.SMELTING)) {
                    ObjectNode value = recipe(server, holder, "SMELTING");
                    if (matches(value, query, output)) matches.add(value);
                }
            }
            matches.sort(Comparator.comparing(value -> value.path("id").asText()));
            ObjectNode observation = envelope("LIVE_SERVER_RECIPE_MANAGER")
                    .put("type", type).put("totalMatches", matches.size())
                    .put("truncated", matches.size() > limit).put("limit", limit);
            if (!query.isBlank()) observation.put("query", query);
            if (!output.isBlank()) observation.put("output", output);
            ArrayNode entries = observation.putArray("recipes");
            matches.stream().limit(limit).forEach(entries::add);
            return new Result(true, "OK", observation);
        } catch (RuntimeException invalid) {
            return failure("RECIPE_QUERY_INVALID", invalid.getMessage());
        }
    }

    private static Result search(MinecraftServer server, String kind, String query,
                                 String namespace, int limit) {
        if (limit < 1 || limit > 64) throw new IllegalArgumentException("limit must be 1..64");
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        List<ResourceLocation> matches = keys(server, kind)
                .filter(value -> namespace.isBlank() || value.getNamespace().equals(namespace))
                .filter(value -> normalizedQuery.isBlank()
                        || value.toString().toLowerCase(Locale.ROOT).contains(normalizedQuery))
                .sorted(Comparator.comparing(ResourceLocation::toString)).toList();
        ObjectNode observation = envelope("LIVE_SERVER_REGISTRY").put("kind", kind)
                .put("totalMatches", matches.size()).put("truncated", matches.size() > limit)
                .put("limit", limit);
        if (!normalizedQuery.isBlank()) observation.put("query", normalizedQuery);
        if (!namespace.isBlank()) observation.put("namespace", namespace);
        ArrayNode entries = observation.putArray("entries");
        matches.stream().limit(limit).forEach(id -> entries.add(identifier(kind, id)));
        return new Result(true, "OK", observation);
    }

    private static Result describe(MinecraftServer server, String kind, String rawId) {
        ResourceLocation id = ResourceLocation.tryParse(rawId);
        if (id == null) throw new IllegalArgumentException("invalid Registry identifier");
        Predicate<ResourceLocation> present = value -> keys(server, kind).anyMatch(value::equals);
        if (!present.test(id)) {
            return new Result(false, "REGISTRY_ID_NOT_FOUND",
                    envelope("LIVE_SERVER_REGISTRY").put("kind", kind).put("id", rawId).put("present", false));
        }
        ObjectNode observation = envelope("LIVE_SERVER_REGISTRY");
        observation.set("entry", identifier(kind, id).put("present", true));
        ObjectNode details = observation.withObject("/entry/details");
        switch (kind) {
            case "ITEM" -> {
                var item = BuiltInRegistries.ITEM.get(id);
                ItemStack stack = item.getDefaultInstance();
                details.put("maxStackSize", stack.getMaxStackSize())
                        .put("damageable", stack.isDamageableItem())
                        .put("maxDamage", stack.getMaxDamage());
                boundedTags(details, item.builtInRegistryHolder().tags());
                boundedComponents(details, stack);
            }
            case "BLOCK" -> {
                var block = BuiltInRegistries.BLOCK.get(id);
                ResourceLocation itemForm = BuiltInRegistries.ITEM.getKey(block.asItem());
                details.put("hasItemForm", itemForm != null && !itemForm.toString().equals("minecraft:air"));
                if (itemForm != null) details.put("itemForm", itemForm.toString());
                boundedTags(details, block.builtInRegistryHolder().tags());
                var state = block.defaultBlockState();
                ObjectNode tools = details.putObject("toolRequirement")
                        .put("correctToolRequired", state.requiresCorrectToolForDrops());
                ArrayNode effective = tools.putArray("effectiveToolTags");
                if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) effective.add("minecraft:mineable/pickaxe");
                if (state.is(BlockTags.MINEABLE_WITH_AXE)) effective.add("minecraft:mineable/axe");
                if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) effective.add("minecraft:mineable/shovel");
                if (state.is(BlockTags.MINEABLE_WITH_HOE)) effective.add("minecraft:mineable/hoe");
            }
            case "ENTITY" -> {
                var entity = BuiltInRegistries.ENTITY_TYPE.get(id);
                details.put("category", entity.getCategory().getName());
                boundedTags(details, entity.builtInRegistryHolder().tags());
            }
            case "DIMENSION" -> details.put("loaded", server.levelKeys().stream()
                    .anyMatch(key -> key.location().equals(id)));
            case "MENU" -> details.put("serverRegistered", true);
            default -> throw new IllegalArgumentException("unsupported Registry kind");
        }
        return new Result(true, "OK", observation);
    }

    private static Stream<ResourceLocation> keys(MinecraftServer server, String kind) {
        return switch (kind) {
            case "ITEM" -> BuiltInRegistries.ITEM.keySet().stream();
            case "BLOCK" -> BuiltInRegistries.BLOCK.keySet().stream();
            case "ENTITY" -> BuiltInRegistries.ENTITY_TYPE.keySet().stream();
            case "MENU" -> BuiltInRegistries.MENU.keySet().stream();
            case "DIMENSION" -> server.levelKeys().stream().map(value -> value.location());
            default -> throw new IllegalArgumentException("unsupported Registry kind");
        };
    }

    private static ObjectNode recipe(MinecraftServer server, RecipeHolder<?> holder, String type) {
        ItemStack result = holder.value().getResultItem(server.registryAccess());
        ObjectNode value = JSON.createObjectNode().put("id", holder.id().toString()).put("type", type)
                .put("output", BuiltInRegistries.ITEM.getKey(result.getItem()).toString())
                .put("outputCount", result.getCount());
        ArrayNode ingredients = value.putArray("ingredients");
        holder.value().getIngredients().stream().limit(16).forEach(ingredient ->
                ingredients.add(ingredient(ingredient)));
        value.put("ingredientCount", holder.value().getIngredients().size())
                .put("ingredientsTruncated", holder.value().getIngredients().size() > 16);
        if (holder.value() instanceof CraftingRecipe crafting) {
            value.put("craftsIn2x2", crafting.canCraftInDimensions(2, 2))
                    .put("craftsIn3x3", crafting.canCraftInDimensions(3, 3))
                    .put("stationRequirement", crafting.canCraftInDimensions(2, 2)
                            ? "PLAYER_CRAFTING_OR_CRAFTING_TABLE" : "CRAFTING_TABLE");
        } else if (holder.value() instanceof SmeltingRecipe smelting) {
            value.put("cookingTime", smelting.getCookingTime()).put("experience", smelting.getExperience())
                    .put("stationRequirement", "FURNACE");
        }
        return value;
    }

    private static ObjectNode ingredient(Ingredient ingredient) {
        ObjectNode value = JSON.createObjectNode();
        ArrayNode choices = value.putArray("choices");
        ItemStack[] items = ingredient.getItems();
        Stream.of(items).limit(8).map(ItemStack::getItem).map(BuiltInRegistries.ITEM::getKey)
                .map(ResourceLocation::toString).distinct().forEach(choices::add);
        value.put("choiceCount", items.length).put("truncated", items.length > 8);
        return value;
    }

    private static boolean matches(ObjectNode recipe, String query, String output) {
        if (!output.isBlank() && !recipe.path("output").asText().equals(output)) return false;
        return query.isBlank() || recipe.path("id").asText().toLowerCase(Locale.ROOT).contains(query)
                || recipe.path("output").asText().toLowerCase(Locale.ROOT).contains(query);
    }

    private static ObjectNode identifier(String kind, ResourceLocation id) {
        return JSON.createObjectNode().put("kind", kind).put("id", id.toString())
                .put("namespace", id.getNamespace()).put("path", id.getPath());
    }

    private static ObjectNode envelope(String source) {
        return JSON.createObjectNode().put("source", source).put("observedAt", Instant.now().toString())
                .put("trust", "VERIFIED_LIVE_SERVER").put("confidence", "HIGH");
    }

    private static void boundedTags(ObjectNode parent, Stream<? extends net.minecraft.tags.TagKey<?>> tags) {
        ArrayNode target = parent.putArray("tags");
        List<String> values = tags.map(tag -> tag.location().toString()).sorted().limit(65).toList();
        values.stream().limit(64).forEach(target::add);
        parent.put("tagsTruncated", values.size() > 64);
    }

    private static void boundedComponents(ObjectNode parent, ItemStack stack) {
        ArrayNode target = parent.putArray("components");
        List<String> values = stack.getComponents().stream()
                .map(component -> BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type()))
                .filter(java.util.Objects::nonNull).map(ResourceLocation::toString).sorted().limit(33).toList();
        values.stream().limit(32).forEach(target::add);
        parent.put("componentsTruncated", values.size() > 32);
    }

    private static Result failure(String code, String message) {
        return new Result(false, code, envelope("LIVE_SERVER_REGISTRY")
                .put("message", message == null || message.isBlank() ? code : message));
    }

    public record Result(boolean success, String code, ObjectNode observation) { }
}
