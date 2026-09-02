package com.mccompanion.minecraft.v121;

import com.mccompanion.core.body.build.SmallBlueprintExecutor;
import com.mccompanion.core.navigation.GridPathPlanner;
import com.mccompanion.core.navigation.RouteExecutionController;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Minecraft adapter for the loader-neutral bounded blueprint executor. */
final class MinecraftSmallBlueprintBuilder {
    interface Navigator {
        RouteExecutionController.Status navigate(Vec3 target);
    }

    private final SmallBlueprintExecutor executor = new SmallBlueprintExecutor();
    private final PlayerActionGateway actions;
    private final SurvivalNavigationAdapter navigation;
    private String preparationDiagnostic = "not_started";
    private final Map<java.util.UUID, Removal> removals = new java.util.HashMap<>();

    private record Removal(BlockPos position, String blockId,
            com.mccompanion.minecraft.navigation.MinecraftNavigationActionExecutor executor) { }

    MinecraftSmallBlueprintBuilder(PlayerActionGateway actions, SurvivalNavigationAdapter navigation) {
        this.actions = actions;
        this.navigation = navigation;
    }

    SmallBlueprintExecutor.Result tick(CompanionPlayer body, SmallBlueprintExecutor.Session session,
                                       Navigator navigator) {
        preparationDiagnostic = "not_needed";
        return executor.tick(session, new Environment(body, navigator, session.plan()));
    }

    String preparationDiagnostic() { return preparationDiagnostic; }

    void forget(CompanionPlayer body) { removals.remove(body.getUUID()); }

    private final class Environment implements SmallBlueprintExecutor.Environment {
        private final CompanionPlayer body;
        private final Navigator navigator;
        private final com.mccompanion.core.body.build.SmallBlueprint plan;
        private String preparationFailure;

        private Environment(CompanionPlayer body, Navigator navigator,
                            com.mccompanion.core.body.build.SmallBlueprint plan) {
            this.body = body;
            this.navigator = navigator;
            this.plan = plan;
        }

        @Override public SmallBlueprintExecutor.WorldBlock block(SmallBlueprintExecutor.Position position) {
            BlockPos target = blockPos(position);
            if (!body.serverLevel().hasChunkAt(target)) {
                return new SmallBlueprintExecutor.WorldBlock("", Map.of(), true, false);
            }
            BlockState state = body.serverLevel().getBlockState(target);
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            Map<String, String> properties = new LinkedHashMap<>();
            for (Property<?> property : state.getProperties()) properties.put(property.getName(), valueName(state, property));
            return new SmallBlueprintExecutor.WorldBlock(id.toString(), properties, state.canBeReplaced(), true);
        }

        @Override public boolean validMaterial(String blockId, Map<String, String> requestedState) {
            ResourceLocation id = ResourceLocation.tryParse(blockId);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return false;
            var block = BuiltInRegistries.BLOCK.get(id);
            if (!(block.asItem() instanceof BlockItem)) return false;
            BlockState state = block.defaultBlockState();
            for (var requested : requestedState.entrySet()) {
                Property<?> property = state.getBlock().getStateDefinition().getProperty(requested.getKey());
                if (property == null || property.getValue(requested.getValue()).isEmpty()) return false;
            }
            return true;
        }

        @Override public int inventoryCount(String blockId) {
            ResourceLocation id = ResourceLocation.tryParse(blockId);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return 0;
            return body.getInventory().countItem(BuiltInRegistries.BLOCK.get(id).asItem());
        }

        @Override public boolean playerOccupies(SmallBlueprintExecutor.Position position) {
            return !body.serverLevel().getEntitiesOfClass(Player.class, new AABB(blockPos(position)),
                    player -> player.isAlive()).isEmpty();
        }

        @Override public boolean actorOccupies(SmallBlueprintExecutor.Position position) {
            return body.getBoundingBox().intersects(new AABB(blockPos(position)));
        }

        @Override public SmallBlueprintExecutor.Preparation vacate(SmallBlueprintExecutor.Position position) {
            BlockPos target = blockPos(position);
            BlockPos stance = java.util.stream.IntStream.rangeClosed(1, 5).boxed()
                    .flatMap(radius -> candidateRing(target, radius).stream())
                    .filter(this::outsideBlueprintFootprint)
                    .filter(candidate -> !blueprintPosition(candidate))
                    .filter(this::standable)
                    .filter(candidate -> navigation.plan(body, Vec3.atBottomCenterOf(candidate),
                            GridPathPlanner.Budget.safeLocomotion()).status() == GridPathPlanner.Status.READY)
                    .findFirst().orElse(null);
            if (stance == null) {
                preparationFailure = "PLAYER_OCCUPIED";
                return SmallBlueprintExecutor.Preparation.BLOCKED;
            }
            Vec3 delta = Vec3.atBottomCenterOf(stance).subtract(body.position());
            float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
            actions.applyMoveInput(body, yaw, body.horizontalCollision);
            preparationDiagnostic = "vacate target=" + target + " stance=" + stance
                    + " body=" + body.position() + " status=DIRECT_SAFE_ROUTE";
            return SmallBlueprintExecutor.Preparation.WAIT;
        }

        private boolean outsideBlueprintFootprint(BlockPos candidate) {
            return candidate.getX() < plan.anchor().x()
                    || candidate.getX() >= plan.anchor().x() + plan.maxSize().x()
                    || candidate.getZ() < plan.anchor().z()
                    || candidate.getZ() >= plan.anchor().z() + plan.maxSize().z();
        }

        @Override public SmallBlueprintExecutor.Preparation prepare(
                SmallBlueprintExecutor.Position target, SmallBlueprintExecutor.Position interactionBlock) {
            BlockPos interaction = blockPos(interactionBlock);
            Vec3 center = Vec3.atCenterOf(interaction);
            BlockHitResult directHit = faceHit(body.getEyePosition(), interaction, blockPos(target));
            if (body.distanceToSqr(center) <= 20.25D && directHit.getType() == HitResult.Type.BLOCK
                    && ((BlockHitResult) directHit).getBlockPos().equals(interaction)) {
                preparationDiagnostic = "ready target=" + target + " support=" + interaction;
                actions.stopInput(body);
                return SmallBlueprintExecutor.Preparation.READY;
            }
            BlockPos stance = reachableStance(blockPos(target), interaction);
            if (stance == null) {
                preparationDiagnostic = "blocked target=" + target + " support=" + interaction
                        + " body=" + body.position();
                preparationFailure = "PLACEMENT_POSITION_UNREACHABLE";
                return SmallBlueprintExecutor.Preparation.BLOCKED;
            }
            RouteExecutionController.Status result = navigator.navigate(Vec3.atBottomCenterOf(stance));
            preparationDiagnostic = "navigate target=" + target + " support=" + interaction
                    + " stance=" + stance + " body=" + body.position() + " status=" + result
                    + " directHit=" + directHit.getType() + ":" + directHit.getBlockPos()
                    + ":" + directHit.getDirection() + ":" + directHit.getLocation();
            if (result == RouteExecutionController.Status.BLOCKED) {
                preparationFailure = "PATH_UNREACHABLE";
                return SmallBlueprintExecutor.Preparation.BLOCKED;
            }
            return SmallBlueprintExecutor.Preparation.WAIT;
        }

        @Override public String preparationFailure() { return preparationFailure; }

        @Override public String place(SmallBlueprintExecutor.Position targetValue,
                                      SmallBlueprintExecutor.Position supportValue,
                                      SmallBlueprintExecutor.Direction faceValue, String blockId,
                                      Map<String, String> requestedState) {
            BlockPos target = blockPos(targetValue);
            BlockPos support = blockPos(supportValue);
            Direction face = Direction.valueOf(faceValue.name());
            ResourceLocation id = ResourceLocation.tryParse(blockId);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return "BLOCK_UNKNOWN";
            Item item = BuiltInRegistries.BLOCK.get(id).asItem();
            int slot = item instanceof BlockItem ? BehaviorDirector.ensureHotbarItem(body, item) : -1;
            if (slot < 0) {
                return "MATERIALS_INSUFFICIENT";
            }
            body.getInventory().selected = slot;
            if (!body.serverLevel().getBlockState(target).canBeReplaced()) return "PLACEMENT_TARGET_OCCUPIED";
            if (body.serverLevel().getBlockState(support).canBeReplaced()) return "PLACEMENT_SUPPORT_MISSING";
            BlockHitResult hit = placementHit((BlockItem) item, support, face, requestedState);
            if (hit == null) return "PLACEMENT_STATE_UNAVAILABLE";
            if (body.distanceToSqr(hit.getLocation()) > 25.0D) return "BLOCK_OUT_OF_REACH";
            body.gameMode.useItemOn(body, body.serverLevel(), body.getMainHandItem(),
                    InteractionHand.MAIN_HAND, hit);
            actions.markVanillaGameModeAction(body);
            return exactState(target, blockId, requestedState) ? null : "UNCERTAIN_EFFECT";
        }

        @Override public String remove(SmallBlueprintExecutor.Position position, String blockId) {
            BlockPos target = blockPos(position);
            if (body.distanceToSqr(Vec3.atCenterOf(target)) > 25.0D || !visible(target)) {
                return "TEMPORARY_SUPPORT_OUT_OF_REACH";
            }
            Removal removal = removals.get(body.getUUID());
            if (removal == null || !removal.position().equals(target) || !removal.blockId().equals(blockId)) {
                removal = new Removal(target, blockId,
                        new com.mccompanion.minecraft.navigation.MinecraftNavigationActionExecutor(
                                com.mccompanion.core.navigation.SurvivalNavigationPolicy.breaking(
                                        1, 0, java.util.List.of(blockId))));
                removals.put(body.getUUID(), removal);
            }
            var point = new GridPathPlanner.Point(target.getX(), target.getY(), target.getZ());
            var action = new GridPathPlanner.WorldAction(GridPathPlanner.ActionType.BREAK_BLOCK, point, blockId);
            var step = new GridPathPlanner.RouteStep(point, GridPathPlanner.Movement.WALK,
                    java.util.List.of(action), 1.0D, 0);
            var result = removal.executor().tick(body, step,
                    () -> actions.markVanillaGameModeAction(body),
                    () -> actions.markVanillaMenuAction(body), slot -> body.getInventory().selected = slot);
            if (result.status() == com.mccompanion.core.navigation.NavigationActionController.Status.RUNNING) {
                return "TEMPORARY_SUPPORT_BREAKING";
            }
            removals.remove(body.getUUID());
            if (result.status() != com.mccompanion.core.navigation.NavigationActionController.Status.READY_TO_MOVE) {
                return result.code();
            }
            return body.serverLevel().getBlockState(target).canBeReplaced()
                    ? null : "TEMPORARY_SUPPORT_CLEANUP_UNCERTAIN";
        }

        private BlockPos reachableStance(BlockPos target, BlockPos interaction) {
            return java.util.stream.IntStream.rangeClosed(1, 3).boxed()
                    .flatMap(radius -> candidateRing(interaction, radius).stream())
                    .filter(candidate -> !candidate.equals(target))
                    .filter(candidate -> !blueprintPosition(candidate))
                    .filter(this::standable)
                    .filter(candidate -> Vec3.atCenterOf(candidate).distanceToSqr(Vec3.atCenterOf(interaction)) <= 20.25D)
                    .filter(candidate -> visibleFaceFrom(candidate, interaction, target))
                    .filter(candidate -> navigation.plan(body, Vec3.atBottomCenterOf(candidate),
                            GridPathPlanner.Budget.safeLocomotion()).status() == GridPathPlanner.Status.READY)
                    .findFirst().orElse(null);
        }

        private boolean blueprintPosition(BlockPos candidate) {
            return plan.blocks().stream().anyMatch(block ->
                    plan.anchor().x() + block.offset().x() == candidate.getX()
                            && plan.anchor().y() + block.offset().y() == candidate.getY()
                            && plan.anchor().z() + block.offset().z() == candidate.getZ());
        }

        private boolean visibleFaceFrom(BlockPos stance, BlockPos support, BlockPos target) {
            double eyeOffset = body.getEyePosition().y - body.getY();
            Vec3 center = Vec3.atBottomCenterOf(stance).add(0.0D, eyeOffset, 0.0D);
            for (double[] offset : new double[][] {{0, 0}, {0.15D, 0}, {-0.15D, 0}, {0, 0.15D}, {0, -0.15D}}) {
                var hit = faceHit(center.add(offset[0], 0.0D, offset[1]), support, target);
                if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(support)) return false;
            }
            return true;
        }

        private java.util.List<BlockPos> candidateRing(BlockPos center, int radius) {
            java.util.ArrayList<BlockPos> result = new java.util.ArrayList<>();
            for (int y = -3; y <= 1; y++) for (int z = -radius; z <= radius; z++) for (int x = -radius; x <= radius; x++) {
                if (Math.max(Math.abs(x), Math.abs(z)) == radius) result.add(center.offset(x, y, z));
            }
            result.sort(java.util.Comparator.comparingDouble((BlockPos pos) -> body.distanceToSqr(Vec3.atBottomCenterOf(pos)))
                    .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX));
            return result;
        }

        private boolean standable(BlockPos position) {
            return body.serverLevel().hasChunkAt(position)
                    && body.serverLevel().getBlockState(position).getCollisionShape(body.serverLevel(), position).isEmpty()
                    && body.serverLevel().getBlockState(position.above()).getCollisionShape(body.serverLevel(), position.above()).isEmpty()
                    && !body.serverLevel().getBlockState(position.below()).getCollisionShape(body.serverLevel(), position.below()).isEmpty();
        }

        private boolean visible(BlockPos target) {
            var hit = body.serverLevel().clip(new ClipContext(body.getEyePosition(), Vec3.atCenterOf(target),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
            return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
        }

        private boolean visibleFace(BlockPos support, BlockPos target) {
            var hit = faceHit(body.getEyePosition(), support, target);
            return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(support);
        }

        private BlockHitResult faceHit(Vec3 eye, BlockPos support, BlockPos target) {
            return body.serverLevel().clip(new ClipContext(eye, facePoint(support, target),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
        }

        private static Vec3 facePoint(BlockPos support, BlockPos target) {
            return Vec3.atCenterOf(support).add(
                    (target.getX() - support.getX()) * 0.499D,
                    (target.getY() - support.getY()) * 0.499D,
                    (target.getZ() - support.getZ()) * 0.499D);
        }

        private BlockHitResult placementHit(BlockItem item, BlockPos support, Direction face,
                                             Map<String, String> requested) {
            Vec3 center = Vec3.atCenterOf(support).add(face.getStepX() * 0.5D,
                    face.getStepY() * 0.5D, face.getStepZ() * 0.5D);
            // Ask the block's vanilla placement logic which ordinary look/hit input produces
            // the requested state. This also works for registry-provided Mod BlockItems.
            for (float yaw : new float[] {body.getYRot(), 0.0F, 90.0F, 180.0F, -90.0F}) {
                body.setYRot(yaw);
                body.setYHeadRot(yaw);
                for (double height : new double[] {0.0D, -0.25D, 0.25D}) {
                    Vec3 point = face.getAxis().isHorizontal() ? center.add(0.0D, height, 0.0D) : center;
                    BlockHitResult hit = new BlockHitResult(point, face, support, false);
                    var context = new net.minecraft.world.item.context.BlockPlaceContext(
                            body, InteractionHand.MAIN_HAND, body.getMainHandItem(), hit);
                    BlockState predicted = item.getBlock().getStateForPlacement(context);
                    if (predicted != null && requested.entrySet().stream().allMatch(entry -> {
                        Property<?> property = predicted.getBlock().getStateDefinition().getProperty(entry.getKey());
                        return property != null && valueName(predicted, property).equals(entry.getValue());
                    })) return hit;
                }
            }
            return null;
        }

        private boolean exactState(BlockPos target, String blockId, Map<String, String> requested) {
            SmallBlueprintExecutor.WorldBlock actual = block(position(target));
            return actual.id().equals(blockId) && requested.entrySet().stream()
                    .allMatch(entry -> entry.getValue().equals(actual.state().get(entry.getKey())));
        }
    }

    private static BlockPos blockPos(SmallBlueprintExecutor.Position value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private static SmallBlueprintExecutor.Position position(BlockPos value) {
        return new SmallBlueprintExecutor.Position(value.getX(), value.getY(), value.getZ());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String valueName(BlockState state, Property property) {
        return property.getName(state.getValue(property));
    }
}
