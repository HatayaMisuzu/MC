package com.mccompanion.minecraft.v121;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Unknown-namespace fixture proving generic Registry discovery without a production Mod adapter. */
public final class RegistryFixtureInitializer implements ModInitializer {
    public static final ResourceLocation BLUE_BLOCK_ID =
            ResourceLocation.fromNamespaceAndPath("mcac_registry_fixture", "blue_block");
    public static final ResourceLocation BLUE_ITEM_ID =
            ResourceLocation.fromNamespaceAndPath("mcac_registry_fixture", "blue_item");
    public static final Block BLUE_BLOCK = new Block(BlockBehaviour.Properties.of());
    public static final Item BLUE_ITEM = new Item(new Item.Properties());

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.BLOCK, BLUE_BLOCK_ID, BLUE_BLOCK);
        Registry.register(BuiltInRegistries.ITEM, BLUE_BLOCK_ID, new BlockItem(BLUE_BLOCK, new Item.Properties()));
        Registry.register(BuiltInRegistries.ITEM, BLUE_ITEM_ID, BLUE_ITEM);
    }
}
