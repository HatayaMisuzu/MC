package com.mccompanion.minecraft.v120;

import com.mccompanion.core.body.build.SmallBlueprint;
import com.mccompanion.minecraft.forge.MinecraftAiCompanionForge;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Real player placement; only the isolated arena and inventory are fixtures. */
@GameTestHolder(MinecraftAiCompanionForge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SmallBlueprintForgeGameTests {
    private SmallBlueprintForgeGameTests() { }

    @GameTest(batch = "blueprint_examples", templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 1400)
    public static void smallBlueprintOrientsAlternativeStairsCleansSupportAndCancelsSafely(GameTestHelper helper) {
        FakeConnection connection = new FakeConnection();
        ServerPlayer owner = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "forge-blueprint-example"));
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, owner);
        CompanionRegistry registry = MinecraftAiCompanionForge.integrationRegistryFor(helper.getLevel().getServer());
        helper.assertTrue(registry.create(owner, "Builder").success(), "example body create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        BlockPos origin = arena(owner, body, 1088);
        String id = registry.runtimeSnapshots(false).stream()
                .filter(value -> value.ownerId().equals(owner.getUUID().toString()))
                .findFirst().orElseThrow().companionId();
        String lease = "blueprint-examples";
        helper.assertTrue(registry.runtimeAcquireLease(id, lease, 1L,
                System.currentTimeMillis() + 120_000L).success(), "example lease failed");
        BlockPos anchor = origin.offset(3, 0, 3);
        var blocks = java.util.List.of(
                exampleBlock(0, 0, "minecraft:oak_stairs", true),
                exampleBlock(1, 0, "minecraft:cobblestone", false),
                exampleBlock(2, 0, "minecraft:cobblestone", false),
                exampleBlock(1, 1, "minecraft:oak_stairs", true),
                exampleBlock(2, 1, "minecraft:cobblestone", false),
                exampleBlock(2, 2, "minecraft:oak_stairs", true),
                exampleBlock(4, 1, "minecraft:oak_planks", false));
        SmallBlueprint plan = new SmallBlueprint(
                new SmallBlueprint.Anchor(body.serverLevel().dimension().location().toString(),
                        anchor.getX(), anchor.getY(), anchor.getZ()),
                new SmallBlueprint.Size(5, 3, 1), blocks,
                new SmallBlueprint.SupportPolicy(java.util.List.of("minecraft:dirt"), 1, true));
        body.addItem(new ItemStack(Items.COBBLESTONE, 3));
        body.addItem(new ItemStack(Items.SPRUCE_STAIRS, 3));
        body.addItem(new ItemStack(Items.OAK_PLANKS, 1));
        body.addItem(new ItemStack(Items.DIRT, 2));
        helper.assertTrue(registry.runtimeStart(id, lease, 1L, "blueprint-stairs", "skill",
                null, null, null, new SkillParameters("BuildSmallBlueprint", plan)).success(), "example start failed");
        awaitExampleCondition(helper, registry, id, 1000, () -> registry.runtimeSnapshots(false).stream()
                .anyMatch(value -> value.companionId().equals(id) && value.behaviorState().equals("IDLE")), () -> {
            for (var block : blocks) {
                var actual = body.serverLevel().getBlockState(anchor.offset(
                        block.offset().x(), block.offset().y(), block.offset().z()));
                String expected = block.alternatives().isEmpty() ? block.blockId() : block.alternatives().get(0);
                helper.assertTrue(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(actual.getBlock())
                        .toString().equals(expected), "example final block mismatch: " + block);
                for (var property : block.state().entrySet()) {
                    var key = actual.getBlock().getStateDefinition().getProperty(property.getKey());
                    helper.assertTrue(key != null && actual.getValue(key).toString().equals(property.getValue()),
                            "example final state mismatch: " + actual);
                }
            }
            helper.assertTrue(body.serverLevel().getBlockState(anchor.offset(4, 0, 0)).isAir(),
                    "temporary dirt was not cleaned through real mining");
            BlockPos cancellationTarget = origin.offset(10, 1, 10);
            SmallBlueprint cancelPlan = new SmallBlueprint(new SmallBlueprint.Anchor(
                    body.serverLevel().dimension().location().toString(),
                    cancellationTarget.getX(), cancellationTarget.getY(), cancellationTarget.getZ()),
                    new SmallBlueprint.Size(1, 1, 1),
                    java.util.List.of(exampleBlock(0, 0, "minecraft:oak_planks", false)),
                    new SmallBlueprint.SupportPolicy(java.util.List.of("minecraft:dirt"), 1, true));
            body.addItem(new ItemStack(Items.OAK_PLANKS));
            helper.assertTrue(registry.runtimeStart(id, lease, 1L, "blueprint-cancel", "skill",
                    null, null, null, new SkillParameters("BuildSmallBlueprint", cancelPlan)).success(),
                    "cancellation start failed");
            awaitExampleCondition(helper, registry, id, 300,
                    () -> body.serverLevel().getBlockState(cancellationTarget.below()).is(Blocks.DIRT), () -> {
                helper.assertTrue(registry.runtimeCancel(id, lease, 1L).success(), "blueprint cancel failed");
                var snapshot = registry.runtimeSnapshots(false).stream()
                        .filter(value -> value.companionId().equals(id)).findFirst().orElseThrow();
                helper.assertTrue(snapshot.behaviorObservation().failureCode()
                        .equals("BLUEPRINT_CANCELLED_SUPPORTS_RETAINED"),
                        "cancel did not report retained supports: " + snapshot.behaviorObservation());
                helper.runAfterDelay(5, () -> {
                    helper.assertTrue(body.serverLevel().getBlockState(cancellationTarget).isAir()
                            && body.serverLevel().getBlockState(cancellationTarget.below()).is(Blocks.DIRT),
                            "cancel mutated world or silently removed supports");
                    helper.assertTrue(registry.runtimeReleaseLease(id, lease, 1L).success(), "example release failed");
                helper.assertTrue(registry.remove(owner).success(), "blueprint example cleanup failed");
                helper.getLevel().getServer().getPlayerList().remove(owner);
                connection.disconnect(Component.literal("Blueprint example complete"));
                    helper.succeed();
                });
            });
        });
    }

    private static SmallBlueprint.Block exampleBlock(int x, int y, String id, boolean stairs) {
        return new SmallBlueprint.Block(new SmallBlueprint.Offset(x, y, 0), id,
                stairs ? java.util.Map.of("facing", "west", "half", "bottom") : java.util.Map.of(),
                stairs ? java.util.List.of("minecraft:spruce_stairs") : java.util.List.of());
    }

    private static void awaitExampleCondition(GameTestHelper helper, CompanionRegistry registry, String id,
            int remaining, java.util.function.BooleanSupplier condition, Runnable done) {
        if (condition.getAsBoolean()) { done.run(); return; }
        var snapshot = registry.runtimeSnapshots(false).stream()
                .filter(value -> value.companionId().equals(id)).findFirst().orElseThrow();
        helper.assertTrue(remaining > 0 && snapshot.behaviorState().equals("RUNNING"),
                "blueprint example blocked: " + snapshot.behaviorState() + " " + snapshot.behaviorObservation());
        helper.runAfterDelay(1, () -> awaitExampleCondition(helper, registry, id, remaining - 1, condition, done));
    }


    @GameTest(batch = "small_blueprint", templateNamespace = "minecraft",
            template = "bastion/mobs/empty", timeoutTicks = 6000)
    public static void buildsShelterAndHouseWithDurableResumeAndMaterialRecovery(GameTestHelper helper) {
        FakeConnection connection = new FakeConnection();
        ServerPlayer owner = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "forge-blueprint-owner"));
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, owner);
        CompanionRegistry registry = MinecraftAiCompanionForge.integrationRegistryFor(helper.getLevel().getServer());
        helper.assertTrue(registry.create(owner, "Blueprint").success(), "blueprint create failed");
        CompanionPlayer body = registry.liveBodyForOwner(owner.getUUID());
        BlockPos origin = arena(owner, body);
        String id = registry.runtimeSnapshots(false).stream()
                .filter(value -> value.ownerId().equals(owner.getUUID().toString()))
                .findFirst().orElseThrow().companionId();
        String lease = "forge-blueprint";
        helper.assertTrue(registry.runtimeAcquireLease(id, lease, 1L,
                System.currentTimeMillis() + 300_000L).success(), "blueprint lease failed");
        SmallBlueprint shelter = shelter(body, origin.offset(3, 0, 2), 3);
        body.addItem(new ItemStack(Items.COBBLESTONE, shelter.blocks().size()));
        start(helper, registry, id, lease, "shelter", shelter);
        await(helper, registry, id, "RUNNING", 300, () -> matching(body, shelter) >= 3, () -> {
            int completed = matching(body, shelter);
            helper.assertTrue(completed < shelter.blocks().size(), "interruption was not partial");
            helper.assertTrue(registry.runtimePause(id, lease, 1L).success(), "blueprint pause failed");
            CompanionSavedData data = helper.getLevel().getServer().overworld().getDataStorage().computeIfAbsent(
                    CompanionSavedData::load, CompanionSavedData::new, CompanionSavedData.STORAGE_ID);
            CompanionEntry entry = data.get(owner.getUUID());
            CompanionEntry restored = CompanionEntry.load(entry.save());
            helper.assertTrue(restored.blueprintSession != null
                    && restored.blueprintSession.completed().size() == completed,
                    "durable record lost completed blueprint positions");
            entry.blueprintSession = restored.blueprintSession;
            helper.runAfterDelay(5, () -> {
                helper.assertTrue(matching(body, shelter) == completed, "paused blueprint mutated the world");
                helper.assertTrue(registry.runtimeResume(id, lease, 1L).success(), "blueprint resume failed");
                awaitIdle(helper, registry, id, 1800, () -> {
                    helper.assertTrue(matching(body, shelter) == shelter.blocks().size(), "3x3 shelter incomplete");
                    SmallBlueprint house = shelter(body, origin.offset(8, 0, 0), 5);
                    body.addItem(new ItemStack(Items.COBBLESTONE, 64));
                    body.addItem(new ItemStack(Items.COBBLESTONE, house.blocks().size() - 64));
                    start(helper, registry, id, lease, "house", house);
                    awaitIdle(helper, registry, id, 3000, () -> {
                        helper.assertTrue(matching(body, house) == house.blocks().size(), "5x5 house incomplete");
                        BlockPos repairTarget = origin.offset(2, 0, 10);
                        SmallBlueprint repair = new SmallBlueprint(anchor(body, repairTarget),
                                new SmallBlueprint.Size(1, 1, 1),
                                List.of(new SmallBlueprint.Block(new SmallBlueprint.Offset(0, 0, 0),
                                        "minecraft:oak_planks", Map.of(), List.of())),
                                SmallBlueprint.SupportPolicy.none());
                        start(helper, registry, id, lease, "missing-material", repair);
                        await(helper, registry, id, "PAUSED", 40, () -> true, () -> {
                            var snapshot = snapshot(registry, id);
                            helper.assertTrue(snapshot.behaviorObservation().failureCode().equals("MATERIALS_INSUFFICIENT"),
                                    "missing material did not pause safely: " + snapshot.behaviorObservation());
                            body.addItem(new ItemStack(Items.OAK_PLANKS));
                            helper.assertTrue(registry.runtimeResume(id, lease, 1L).success(), "material resume failed");
                            awaitIdle(helper, registry, id, 300, () -> {
                                helper.assertTrue(body.serverLevel().getBlockState(repairTarget).is(Blocks.OAK_PLANKS),
                                        "material recovery postcondition failed");
                                helper.assertTrue(registry.runtimeReleaseLease(id, lease, 1L).success(), "release failed");
                                helper.assertTrue(registry.remove(owner).success(), "blueprint cleanup failed");
                                helper.getLevel().getServer().getPlayerList().remove(owner);
                                connection.disconnect(Component.literal("Blueprint GameTest complete"));
                                helper.succeed();
                            });
                        });
                    });
                });
            });
        });
    }

    private static void start(GameTestHelper helper, CompanionRegistry registry, String id,
                              String lease, String behavior, SmallBlueprint plan) {
        helper.assertTrue(registry.runtimeStart(id, lease, 1L, "blueprint-" + behavior,
                "skill", null, null, null, new SkillParameters("BuildSmallBlueprint", plan)).success(),
                "blueprint start failed: " + behavior);
    }

    private static CompanionRegistry.RuntimeSnapshot snapshot(CompanionRegistry registry, String id) {
        return registry.runtimeSnapshots(false).stream().filter(value -> value.companionId().equals(id))
                .findFirst().orElseThrow();
    }

    private static void awaitIdle(GameTestHelper helper, CompanionRegistry registry, String id,
                                  int remaining, Runnable done) {
        await(helper, registry, id, "IDLE", remaining, () -> true, done);
    }

    private static void await(GameTestHelper helper, CompanionRegistry registry, String id, String expected,
                              int remaining, java.util.function.BooleanSupplier condition, Runnable done) {
        var current = snapshot(registry, id);
        if (current.behaviorState().equals(expected) && condition.getAsBoolean()) { done.run(); return; }
        helper.assertTrue(current.behaviorState().equals("RUNNING") && remaining > 0,
                "blueprint failed: state=" + current.behaviorState() + " observation=" + current.behaviorObservation()
                        + " position=" + current.x() + "," + current.y() + "," + current.z());
        helper.runAfterDelay(1, () -> await(helper, registry, id, expected, remaining - 1, condition, done));
    }

    private static SmallBlueprint shelter(CompanionPlayer body, BlockPos anchor, int width) {
        List<SmallBlueprint.Block> blocks = new ArrayList<>();
        for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) blocks.add(block(x, 0, z));
        int wallTop = width == 3 ? 2 : 3;
        for (int y = 1; y <= wallTop; y++) for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
            boolean perimeter = x == 0 || z == 0 || x == width - 1 || z == width - 1;
            if (perimeter && !(z == 0 && x == width / 2 && y <= 2)) blocks.add(block(x, y, z));
        }
        for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) blocks.add(block(x, wallTop + 1, z));
        return new SmallBlueprint(anchor(body, anchor), new SmallBlueprint.Size(width, wallTop + 2, width),
                blocks, new SmallBlueprint.SupportPolicy(List.of("minecraft:dirt"), 4, true));
    }

    private static SmallBlueprint.Anchor anchor(CompanionPlayer body, BlockPos position) {
        return new SmallBlueprint.Anchor(body.serverLevel().dimension().location().toString(),
                position.getX(), position.getY(), position.getZ());
    }

    private static SmallBlueprint.Block block(int x, int y, int z) {
        return new SmallBlueprint.Block(new SmallBlueprint.Offset(x, y, z), "minecraft:cobblestone",
                Map.of(), List.of("minecraft:stone"));
    }

    private static int matching(CompanionPlayer body, SmallBlueprint plan) {
        return (int) plan.blocks().stream().filter(block -> body.serverLevel().getBlockState(new BlockPos(
                plan.anchor().x() + block.offset().x(), plan.anchor().y() + block.offset().y(),
                plan.anchor().z() + block.offset().z())).is(Blocks.COBBLESTONE)).count();
    }

    private static BlockPos arena(ServerPlayer owner, CompanionPlayer body) {
        return arena(owner, body, 960);
    }

    private static BlockPos arena(ServerPlayer owner, CompanionPlayer body, int coordinate) {
        owner.teleportTo(owner.serverLevel(), coordinate + 0.5D, 100.0D, coordinate + 0.5D, 0.0F, 0.0F);
        body.teleportTo(body.serverLevel(), coordinate + 0.5D, 100.0D, coordinate + 0.5D, 0.0F, 0.0F);
        body.setDeltaMovement(Vec3.ZERO);
        BlockPos origin = body.blockPosition();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            body.serverLevel().setChunkForced((origin.getX() >> 4) + x, (origin.getZ() >> 4) + z, true);
        }
        for (int x = -4; x <= 15; x++) for (int z = -4; z <= 15; z++) {
            body.serverLevel().setBlockAndUpdate(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            for (int y = 0; y <= 6; y++) body.serverLevel().setBlockAndUpdate(
                    origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
        }
        return origin;
    }
}
