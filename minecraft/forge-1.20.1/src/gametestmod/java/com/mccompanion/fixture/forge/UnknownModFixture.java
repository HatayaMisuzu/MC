package com.mccompanion.fixture.forge;

import com.mccompanion.minecraft.forge.json.ObjectMapper;
import com.mccompanion.minecraft.forge.RegistryObservationService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * GameTest-only unknown Mod. It is a separate local Mod source set and is never
 * included in the production Companion JAR.
 */
@Mod(UnknownModFixture.MOD_ID)
@GameTestHolder(UnknownModFixture.MOD_ID)
@PrefixGameTestTemplate(false)
public final class UnknownModFixture {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String MOD_ID = "mcac_unknown_fixture";
    private static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    static final RegistryObject<Block> BLUE_BLOCK = BLOCKS.register(
            "blue_block",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK).strength(1.5F)));
    static final RegistryObject<Item> BLUE_BLOCK_ITEM = ITEMS.register(
            "blue_block",
            () -> new BlockItem(BLUE_BLOCK.get(), new Item.Properties()));
    static final RegistryObject<Item> BLUE_ITEM =
            ITEMS.register("blue_item", () -> new Item(new Item.Properties()));
    static final RegistryObject<Item> CHARGED_BLUE_ITEM =
            ITEMS.register("charged_blue_item", () -> new Item(new Item.Properties()));

    public UnknownModFixture() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }

    @GameTest(
            batch = "unknownModDiscovery",
            templateNamespace = "minecraft",
            template = "bastion/mobs/empty",
            timeoutTicks = 200)
    public static void genericRegistryAndRecipeDiscovery(GameTestHelper helper) {
        var search = RegistryObservationService.registry(
                helper.getLevel().getServer(),
                JSON.createObjectNode()
                        .put("tool", "registry.search")
                        .put("kind", "ITEM")
                        .put("namespace", MOD_ID)
                        .put("query", "blue")
                        .put("limit", 8));
        helper.assertTrue(search.success(), "unknown Mod Registry search failed: " + search.code());
        helper.assertTrue(
                java.util.stream.StreamSupport.stream(
                                search.observation().path("entries").spliterator(), false)
                        .anyMatch(value -> value.path("id").asText().equals(
                                MOD_ID + ":charged_blue_item")),
                "unknown Mod Registry search omitted the charged item");

        var described = RegistryObservationService.registry(
                helper.getLevel().getServer(),
                JSON.createObjectNode()
                        .put("tool", "registry.describe")
                        .put("kind", "BLOCK")
                        .put("id", MOD_ID + ":blue_block"));
        helper.assertTrue(described.success(), "unknown Mod Registry describe failed: " + described.code());
        helper.assertTrue(
                java.util.stream.StreamSupport.stream(
                                described.observation().path("entry").path("details")
                                        .path("tags").spliterator(), false)
                        .anyMatch(value -> value.asText().equals("minecraft:mineable/pickaxe")),
                "unknown Mod block descriptor omitted its live tool tag");

        var recipe = RegistryObservationService.recipes(
                helper.getLevel().getServer(),
                JSON.createObjectNode()
                        .put("type", "CRAFTING")
                        .put("query", "charged_blue_item")
                        .put("output", MOD_ID + ":charged_blue_item")
                        .put("limit", 8));
        helper.assertTrue(recipe.success(), "unknown Mod recipe query failed: " + recipe.code());
        helper.assertTrue(
                recipe.observation().path("totalMatches").asInt() == 1,
                "unknown Mod recipe was not discovered from the live RecipeManager");
        helper.assertTrue(
                recipe.observation().path("source").asText().equals("LIVE_SERVER_RECIPE_MANAGER"),
                "unknown Mod recipe result was not live server evidence");
        helper.succeed();
    }
}
