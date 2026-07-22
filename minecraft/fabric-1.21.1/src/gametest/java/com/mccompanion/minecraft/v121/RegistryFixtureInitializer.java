package com.mccompanion.minecraft.v121;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Unknown-namespace fixture proving generic Registry discovery without a production Mod adapter. */
public final class RegistryFixtureInitializer implements ModInitializer {
    public static final ResourceLocation BLUE_BLOCK_ID =
            ResourceLocation.fromNamespaceAndPath("mcac_unknown_fixture", "blue_block");
    public static final ResourceLocation BLUE_ITEM_ID =
            ResourceLocation.fromNamespaceAndPath("mcac_unknown_fixture", "blue_item");
    public static final ResourceLocation CHARGED_ITEM_ID =
            ResourceLocation.fromNamespaceAndPath("mcac_unknown_fixture", "charged_blue_item");
    public static final ResourceLocation WATCHER_ENTITY_ID =
            ResourceLocation.fromNamespaceAndPath("mcac_unknown_fixture", "watcher");
    public static final SimpleContainer CONTAINER = new SimpleContainer(9);
    public static final Block BLUE_BLOCK = new UnknownContainerBlock(BlockBehaviour.Properties.of()
            .strength(2.0F).requiresCorrectToolForDrops());
    public static final Item BLUE_ITEM = new Item(new Item.Properties().food(new FoodProperties.Builder()
            .nutrition(2).saturationModifier(0.2F).build()));
    public static final Item CHARGED_ITEM = new Item(new Item.Properties().stacksTo(16));
    public static final EntityType<ArmorStand> WATCHER_ENTITY = EntityType.Builder.<ArmorStand>of(
            ArmorStand::new, MobCategory.MISC).sized(0.5F, 1.975F).build(WATCHER_ENTITY_ID.toString());

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.BLOCK, BLUE_BLOCK_ID, BLUE_BLOCK);
        Registry.register(BuiltInRegistries.ITEM, BLUE_BLOCK_ID, new BlockItem(BLUE_BLOCK, new Item.Properties()));
        Registry.register(BuiltInRegistries.ITEM, BLUE_ITEM_ID, BLUE_ITEM);
        Registry.register(BuiltInRegistries.ITEM, CHARGED_ITEM_ID, CHARGED_ITEM);
        Registry.register(BuiltInRegistries.ENTITY_TYPE, WATCHER_ENTITY_ID, WATCHER_ENTITY);
        CONTAINER.setItem(0, new net.minecraft.world.item.ItemStack(BLUE_ITEM, 2));
    }

    private static final class UnknownContainerBlock extends Block {
        private UnknownContainerBlock(Properties properties) { super(properties); }

        @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position,
                                                               Player player, BlockHitResult hit) {
            if (!level.isClientSide()) player.openMenu(new SimpleMenuProvider(
                    (containerId, inventory, owner) -> new ChestMenu(MenuType.GENERIC_9x1, containerId,
                            inventory, CONTAINER, 1), Component.literal("Unknown Fixture Container")));
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
    }
}
