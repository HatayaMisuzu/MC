package com.mccompanion.minecraft.navigation;

import com.mccompanion.core.navigation.GridPathPlanner;
import com.mccompanion.core.navigation.NavigationActionController;
import com.mccompanion.core.navigation.SurvivalNavigationPolicy;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Shared Minecraft API execution for the current navigation feature's bounded route actions. */
public final class MinecraftNavigationActionExecutor {
    private static final Set<String> PROTECTED_EXACT = Set.of(
            "ancient_debris", "spawner", "trial_spawner", "vault", "beacon", "conduit",
            "lodestone", "respawn_anchor", "enchanting_table", "end_portal_frame", "end_portal",
            "nether_portal", "obsidian", "crying_obsidian", "tnt", "ice", "packed_ice", "blue_ice",
            "redstone_wire", "repeater", "comparator", "lever", "daylight_detector",
            "tripwire", "tripwire_hook", "target", "command_block", "chain_command_block",
            "repeating_command_block", "structure_block", "jigsaw", "barrier");

    private final SurvivalNavigationPolicy policy;
    private final NavigationActionController.Session session;

    public MinecraftNavigationActionExecutor(SurvivalNavigationPolicy policy) {
        this.policy = policy;
        this.session = new NavigationActionController.Session(policy);
    }

    public NavigationActionController.Result tick(
            ServerPlayer body,
            GridPathPlanner.RouteStep step,
            Runnable markVanillaGameModeAction,
            Runnable markVanillaMenuAction,
            IntConsumer selectHotbarSlot) {
        return session.tick(step, new NavigationActionController.Environment() {
            @Override public NavigationActionController.Observation observe(
                    GridPathPlanner.WorldAction action) {
                if (action.type() == GridPathPlanner.ActionType.PLACE_BLOCK) {
                    return observePlacement(body, action, policy);
                }
                BlockPos position = MinecraftSurvivalNavigation.block(action.position());
                if (!body.serverLevel().hasChunkAt(position)) {
                    return NavigationActionController.Observation.unavailable("ACTION_CHUNK_UNLOADED");
                }
                BlockState state = body.serverLevel().getBlockState(position);
                String currentId = blockId(state);
                if (!currentId.equals(action.expectedBlockId())) {
                    return state.getCollisionShape(body.serverLevel(), position).isEmpty()
                            && state.getFluidState().isEmpty()
                            ? NavigationActionController.Observation.satisfied()
                            : NavigationActionController.Observation.changed();
                }
                String unsafe = unsafeReason(body, position, state, policy, true);
                return unsafe == null ? NavigationActionController.Observation.expected()
                        : NavigationActionController.Observation.unsafe(unsafe);
            }

            @Override public double breakProgress(GridPathPlanner.WorldAction action) {
                BlockPos position = MinecraftSurvivalNavigation.block(action.position());
                BlockState state = body.serverLevel().getBlockState(position);
                ToolChoice choice = bestTool(body, state);
                if (choice == null || !equip(body, choice, markVanillaMenuAction, selectHotbarSlot)) {
                    return 0.0D;
                }
                lookAt(body, position);
                body.swing(InteractionHand.MAIN_HAND);
                return body.getAbilities().instabuild ? 1.0D
                        : state.getDestroyProgress(body, body.serverLevel(), position);
            }

            @Override public boolean breakBlock(GridPathPlanner.WorldAction action) {
                BlockPos position = MinecraftSurvivalNavigation.block(action.position());
                markVanillaGameModeAction.run();
                return body.gameMode.destroyBlock(position);
            }

            @Override public boolean placeBlock(GridPathPlanner.WorldAction action) {
                BlockPos target = MinecraftSurvivalNavigation.block(action.position());
                PlacementChoice choice = placementChoice(body, action.expectedBlockId());
                PlacementHit placement = choice == null ? null : findPlacementHit(body, target);
                if (choice == null || placement == null
                        || !equip(body, choice.asToolChoice(), markVanillaMenuAction, selectHotbarSlot)) {
                    return false;
                }
                if (!body.mayUseItemAt(target, placement.face, body.getMainHandItem())) return false;
                lookAt(body, placement.hit.getBlockPos());
                markVanillaGameModeAction.run();
                var result = body.gameMode.useItemOn(body, body.serverLevel(), body.getMainHandItem(),
                        InteractionHand.MAIN_HAND, placement.hit);
                return result.consumesAction();
            }
        });
    }

    public int brokenBlocks() { return session.brokenBlocks(); }

    public java.util.List<GridPathPlanner.WorldAction> destroyed() { return session.destroyed(); }

    public int placedBlocks() { return session.placedBlocks(); }

    public java.util.List<GridPathPlanner.WorldAction> placed() { return session.placed(); }

    static double plannedBreakCost(ServerPlayer body, BlockPos position, BlockState state,
                                   SurvivalNavigationPolicy policy) {
        String unsafe = unsafeReason(body, position, state, policy, false);
        if (unsafe != null) return Double.POSITIVE_INFINITY;
        ToolChoice choice = bestTool(body, state);
        if (choice == null) return Double.POSITIVE_INFINITY;
        if (body.getAbilities().instabuild) return 1.0D;
        float hardness = state.getDestroySpeed(body.serverLevel(), position);
        if (!(hardness > 0.0F) || !Float.isFinite(hardness)) return Double.POSITIVE_INFINITY;
        double ticks = hardness * (choice.correctForDrops ? 30.0D : 100.0D)
                / Math.max(1.0D, choice.speed);
        return ticks <= 100.0D ? Math.max(2.0D, ticks) : Double.POSITIVE_INFINITY;
    }

    static PlacementPlan plannedPlacement(ServerPlayer body, BlockPos position,
                                          SurvivalNavigationPolicy policy) {
        return policy.allowedPlaceBlocks().stream().sorted()
                .map(blockId -> {
                    String unsafe = unsafePlaceReason(body, position, blockId, policy, false);
                    return unsafe == null ? new PlacementPlan(blockId, 4.0D) : null;
                })
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    public static int availablePlacementBlocks(ServerPlayer body, SurvivalNavigationPolicy policy) {
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            if (stack.getItem() instanceof BlockItem blockItem
                    && policy.allowsPlace(blockId(blockItem.getBlock().defaultBlockState()))
                    && safeScaffoldBlock(blockItem.getBlock())) total += stack.getCount();
        }
        return total;
    }

    private static NavigationActionController.Observation observePlacement(
            ServerPlayer body, GridPathPlanner.WorldAction action, SurvivalNavigationPolicy policy) {
        BlockPos position = MinecraftSurvivalNavigation.block(action.position());
        if (!body.serverLevel().hasChunkAt(position)) {
            return NavigationActionController.Observation.unavailable("ACTION_CHUNK_UNLOADED");
        }
        BlockState state = body.serverLevel().getBlockState(position);
        if (blockId(state).equals(action.expectedBlockId())) {
            return NavigationActionController.Observation.satisfied();
        }
        if (!state.canBeReplaced() && !state.getFluidState().is(FluidTags.WATER)) {
            return NavigationActionController.Observation.changed();
        }
        String unsafe = unsafePlaceReason(body, position, action.expectedBlockId(), policy, true);
        return unsafe == null ? NavigationActionController.Observation.expected()
                : NavigationActionController.Observation.unsafe(unsafe);
    }

    private static String unsafePlaceReason(ServerPlayer body, BlockPos position, String blockId,
                                            SurvivalNavigationPolicy policy, boolean requireReach) {
        if (!policy.allowsPlace(blockId)) return "NAVIGATION_PLACE_NOT_ALLOWED";
        ResourceLocation key = ResourceLocation.tryParse(blockId);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) return "SCAFFOLD_BLOCK_UNKNOWN";
        Block block = BuiltInRegistries.BLOCK.get(key);
        if (!safeScaffoldBlock(block)) return "SCAFFOLD_BLOCK_PROTECTED";
        BlockState current = body.serverLevel().getBlockState(position);
        if (!current.canBeReplaced() && !current.getFluidState().is(FluidTags.WATER)) {
            return "PLACEMENT_CONTEXT_CHANGED";
        }
        if (!current.getFluidState().isEmpty() && !current.getFluidState().is(FluidTags.WATER)) {
            return "PLACEMENT_FLUID_UNSAFE";
        }
        if (block.defaultBlockState().getCollisionShape(body.serverLevel(), position).isEmpty()) {
            return "SCAFFOLD_BLOCK_UNSUITABLE";
        }
        if (placementChoice(body, blockId) == null) return "SCAFFOLD_ITEM_MISSING";
        if (requireReach && findPlacementHit(body, position) == null) return "PLACEMENT_SUPPORT_UNAVAILABLE";
        return null;
    }

    private static boolean safeScaffoldBlock(Block block) {
        String id = BuiltInRegistries.BLOCK.getKey(block).toString();
        String path = id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        return !(block instanceof FallingBlock) && !(block instanceof EntityBlock)
                && !isProtectedPath(path);
    }

    private static PlacementChoice placementChoice(ServerPlayer body, String desiredBlockId) {
        PlacementChoice best = null;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            if (!(stack.getItem() instanceof BlockItem blockItem)
                    || !blockId(blockItem.getBlock().defaultBlockState()).equals(desiredBlockId)
                    || !safeScaffoldBlock(blockItem.getBlock())) continue;
            PlacementChoice candidate = new PlacementChoice(slot, stack.getItem(), stack.getCount());
            if (best == null || candidate.count > best.count
                    || candidate.count == best.count && candidate.slot < best.slot) best = candidate;
        }
        return best;
    }

    private static PlacementHit findPlacementHit(ServerPlayer body, BlockPos target) {
        for (Direction direction : new Direction[] {
                Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                Direction.WEST, Direction.EAST, Direction.UP}) {
            BlockPos support = target.relative(direction);
            if (!body.serverLevel().hasChunkAt(support)) continue;
            BlockState supportState = body.serverLevel().getBlockState(support);
            if (supportState.getCollisionShape(body.serverLevel(), support).isEmpty()
                    || isPlacementHazard(supportState)) continue;
            Direction face = direction.getOpposite();
            Vec3 hitLocation = Vec3.atCenterOf(support).add(face.getStepX() * 0.5D,
                    face.getStepY() * 0.5D, face.getStepZ() * 0.5D);
            if (body.distanceToSqr(hitLocation) > 25.0D) continue;
            HitResult visible = body.serverLevel().clip(new ClipContext(body.getEyePosition(), hitLocation,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
            if (visible.getType() != HitResult.Type.BLOCK
                    || !((BlockHitResult) visible).getBlockPos().equals(support)) continue;
            return new PlacementHit(face, new BlockHitResult(hitLocation, face, support, false));
        }
        return null;
    }

    private static boolean isPlacementHazard(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA)
                || state.is(net.minecraft.world.level.block.Blocks.FIRE)
                || state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE)
                || state.is(net.minecraft.world.level.block.Blocks.CACTUS)
                || state.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                || state.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW);
    }

    private static String unsafeReason(ServerPlayer body, BlockPos position, BlockState state,
                                       SurvivalNavigationPolicy policy, boolean requireReach) {
        String id = blockId(state);
        if (!policy.allowsBreak(id)) return "NAVIGATION_BREAK_NOT_ALLOWED";
        if (state.isAir() || state.getCollisionShape(body.serverLevel(), position).isEmpty()) {
            return "BLOCK_ALREADY_PASSABLE";
        }
        if (!state.getFluidState().isEmpty()) return "FLUID_BLOCK_PROTECTED";
        if (body.serverLevel().getBlockEntity(position) != null
                || body.serverLevel().getBlockEntity(position.above()) != null) {
            return "BLOCK_ENTITY_PROTECTED";
        }
        if (state.getBlock() instanceof FallingBlock
                || body.serverLevel().getBlockState(position.above()).getBlock() instanceof FallingBlock) {
            return "FALLING_BLOCK_UNSAFE";
        }
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = position.relative(direction);
            if (body.serverLevel().hasChunkAt(adjacent)
                    && !body.serverLevel().getBlockState(adjacent).getFluidState().isEmpty()) {
                return "FLUID_RELEASE_UNSAFE";
            }
        }
        String path = id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        if (isProtectedPath(path)) return "VALUABLE_OR_FUNCTIONAL_BLOCK_PROTECTED";
        float hardness = state.getDestroySpeed(body.serverLevel(), position);
        if (hardness < 0.0F || !Float.isFinite(hardness)) return "BLOCK_UNBREAKABLE";
        if (bestTool(body, state) == null) return "TOOL_INADEQUATE";
        if (requireReach) {
            if (body.distanceToSqr(Vec3.atCenterOf(position)) > 25.0D) return "BLOCK_OUT_OF_REACH";
            HitResult visible = body.serverLevel().clip(new ClipContext(body.getEyePosition(),
                    Vec3.atCenterOf(position), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
            if (visible.getType() != HitResult.Type.BLOCK
                    || !((net.minecraft.world.phys.BlockHitResult) visible).getBlockPos().equals(position)) {
                return "BLOCK_NOT_VISIBLE";
            }
        }
        return null;
    }

    private static boolean isProtectedPath(String path) {
        if (PROTECTED_EXACT.contains(path) || path.endsWith("_ore") || path.endsWith("_shulker_box")
                || path.endsWith("_chest") || path.endsWith("_bed") || path.endsWith("_door")
                || path.endsWith("_trapdoor") || path.endsWith("_button")
                || path.endsWith("_pressure_plate")) return true;
        return path.equals("diamond_block") || path.equals("emerald_block")
                || path.equals("gold_block") || path.equals("raw_gold_block")
                || path.equals("iron_block") || path.equals("raw_iron_block")
                || path.equals("netherite_block") || path.equals("lapis_block")
                || path.equals("coal_block") || path.equals("copper_block")
                || path.equals("raw_copper_block");
    }

    private static ToolChoice bestTool(ServerPlayer body, BlockState state) {
        ToolChoice best = null;
        boolean requiresCorrect = state.requiresCorrectToolForDrops();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            if (stack.isEmpty() || stack.isDamageableItem()
                    && stack.getDamageValue() >= stack.getMaxDamage() - 1) continue;
            boolean correct = stack.isCorrectToolForDrops(state);
            if (requiresCorrect && !correct) continue;
            double speed = Math.max(1.0D, stack.getDestroySpeed(state));
            ToolChoice candidate = new ToolChoice(slot, stack.getItem(), stack.getDamageValue(), speed, correct);
            if (best == null || candidate.score() > best.score()) best = candidate;
        }
        if (!requiresCorrect && best == null) {
            return new ToolChoice(body.getInventory().selected, ItemStack.EMPTY.getItem(), 0, 1.0D, false);
        }
        return best;
    }

    private static boolean equip(ServerPlayer body, ToolChoice choice, Runnable markMenu,
                                 IntConsumer selectHotbar) {
        int hotbar = choice.slot;
        if (hotbar >= 9) {
            hotbar = body.getInventory().getSuitableHotbarSlot();
            if (hotbar < 0 || hotbar > 8) return false;
            body.inventoryMenu.clicked(choice.slot, hotbar, ClickType.SWAP, body);
            markMenu.run();
        }
        selectHotbar.accept(hotbar);
        ItemStack held = body.getMainHandItem();
        return held.is(choice.item) && (!held.isDamageableItem() || held.getDamageValue() == choice.damage);
    }

    private static void lookAt(ServerPlayer body, BlockPos position) {
        Vec3 delta = Vec3.atCenterOf(position).subtract(body.getEyePosition());
        body.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        body.setXRot((float) -Math.toDegrees(Math.atan2(delta.y,
                Math.sqrt(delta.x * delta.x + delta.z * delta.z))));
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private record ToolChoice(int slot, Item item, int damage, double speed, boolean correctForDrops) {
        private double score() { return (correctForDrops ? 10_000.0D : 0.0D) + speed; }
    }

    static record PlacementPlan(String blockId, double cost) { }

    private record PlacementChoice(int slot, Item item, int count) {
        private ToolChoice asToolChoice() { return new ToolChoice(slot, item, 0, 1.0D, false); }
    }

    private record PlacementHit(Direction face, BlockHitResult hit) { }
}
