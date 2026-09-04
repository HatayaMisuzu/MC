package com.mccompanion.core.body.daily;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

final class DailyActionEngineTest {
    private static final DailyActionRequest.Position ORIGIN =
            new DailyActionRequest.Position("minecraft:overworld", 0, 64, 0);

    @ParameterizedTest
    @EnumSource(DailyActionKind.class)
    void everyKindHasARealPostconditionDrivenHappyPath(DailyActionKind kind) {
        DailyActionRequest request = request(kind);
        DailyActionEngine engine = new DailyActionEngine();
        engine.start("happy-" + kind, request, 0);
        DailyActionEngine.Adapter adapter = command -> DailyActionCommand.CommandResult.success();
        for (int tick = 0; tick < 80; tick++) {
            DailyActionEngine.Session session = engine.inspect("happy-" + kind);
            DailyActionSnapshot snapshot = snapshot(request, session.phase(), tick > 0);
            DailyActionEngine.TickResult result = engine.tick("happy-" + kind, snapshot, adapter);
            if (result.session().status() == DailyActionEngine.Status.COMPLETE) return;
        }
        var finalSession = engine.inspect("happy-" + kind);
        assertEquals(DailyActionEngine.Status.COMPLETE, finalSession.status(), kind.name() + " phase="
                + finalSession.phase() + " baseline=" + finalSession.phaseBaseline()
                + " obs=" + finalSession.observation());
    }

    @ParameterizedTest
    @EnumSource(DailyActionKind.class)
    void acceptedCommandWithoutObservedPostconditionNeverSucceeds(DailyActionKind kind) {
        DailyActionRequest request = request(kind);
        DailyActionEngine engine = new DailyActionEngine();
        engine.start("uncertain-" + kind, request, 0);
        DailyActionEngine.Adapter adapter = command -> DailyActionCommand.CommandResult.success();
        DailyActionEngine.TickResult result = engine.tick("uncertain-" + kind,
                snapshot(request, engine.inspect("uncertain-" + kind).phase(), false), adapter);
        assertEquals(DailyActionEngine.Status.RUNNING, result.session().status(), kind.name());
    }

    @ParameterizedTest
    @EnumSource(DailyActionKind.class)
    void adapterRejectionIsUncertainForEveryKind(DailyActionKind kind) {
        DailyActionRequest request = request(kind);
        DailyActionEngine engine = new DailyActionEngine();
        engine.start("reject-" + kind, request, 0);
        DailyActionEngine.TickResult result = null;
        for (int tick = 0; tick < 16; tick++) {
            DailyActionPhase phase = engine.inspect("reject-" + kind).phase();
            result = engine.tick("reject-" + kind, snapshot(request, phase, false),
                    command -> DailyActionCommand.CommandResult.uncertain("VANILLA_EFFECT_UNCERTAIN"));
            if (result.session().status() != DailyActionEngine.Status.RUNNING) break;
        }
        assertEquals(DailyActionEngine.Status.UNCERTAIN, result.session().status(), kind.name());
    }

    @Test
    void pauseResumeCancelAndRemoveAreDurableControls() {
        DailyActionEngine engine = new DailyActionEngine();
        DailyActionRequest request = request(DailyActionKind.FISH);
        engine.start("controls", request, 0);
        assertEquals(DailyActionEngine.Status.PAUSED, engine.pause("controls", "user pause").status());
        assertEquals(DailyActionEngine.Status.RUNNING, engine.resume("controls", 10).status());
        assertEquals(DailyActionEngine.Status.CANCELLED, engine.cancel("controls", "user cancel").status());
        engine.remove("controls");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> engine.inspect("controls"));
    }

    @Test
    void actionVariantsSelectDifferentDurableFlows() {
        assertEquals(DailyActionPhase.WAKE, startPhase(DailyActionKind.SLEEP_AT_BED, "WAKE"));
        assertEquals(DailyActionPhase.NAVIGATE_BUCKET, startPhase(DailyActionKind.USE_WATER_BUCKET, "EMPTY"));
        assertEquals(DailyActionPhase.APPROACH_VEHICLE, startPhase(DailyActionKind.USE_VEHICLE, "MOUNT"));
        assertEquals(DailyActionPhase.DISEMBARK_VEHICLE, startPhase(DailyActionKind.USE_VEHICLE, "DISMOUNT"));
        assertEquals(DailyActionPhase.APPROACH_VEHICLE, startPhase(DailyActionKind.USE_VEHICLE, "TRAVEL"));
    }

    @Test
    void bestWeaponDoesNotTreatAnOrdinaryInventoryItemAsAWeapon() {
        DailyActionRequest request = new DailyActionRequest(DailyActionKind.EQUIP_ITEM, "", "", "",
                ORIGIN.dimension(), null, "BEST_WEAPON_ANY", "MAIN_HAND", "", -1,
                1, 0, 300);
        DailyActionEngine engine = new DailyActionEngine();
        engine.start("best-weapon-none", request, 0);
        DailyActionSnapshot base = snapshot(request, DailyActionPhase.VALIDATE, false);
        DailyActionSnapshot ordinaryItemOnly = new DailyActionSnapshot(
                base.tick(), base.alive(), base.dimension(), base.position(), base.inventory(),
                base.equipment(), base.bed(), base.bucket(), base.vehicle(), base.fish(), base.crop(),
                base.breed(), base.menu(), base.experienceLevel(), base.sleeping(), base.onGround(),
                base.fallFlying(), java.util.List.of(new DailyActionSnapshot.ItemCandidate(
                9, "minecraft:stick", 1, 0, 0, false, 1.0D, 0.0D,
                Map.of("equipmentSlot", "MAIN_HAND"))), base.bedCandidates(), base.vehicleCandidates(),
                base.cropCandidates(), base.animalCandidates(), base.entityCandidates(), base.trade(),
                base.enchant(), base.brew(), base.glide());

        DailyActionEngine.TickResult result = engine.tick("best-weapon-none", ordinaryItemOnly,
                command -> DailyActionCommand.CommandResult.success());

        assertEquals(DailyActionEngine.Status.BLOCKED, result.session().status());
        assertEquals("USABLE_EQUIPMENT_NOT_FOUND", result.observation().code());
    }

    @Test
    void alreadyWornNearlyBrokenEquipmentIsRejected() {
        DailyActionRequest baseRequest = request(DailyActionKind.EQUIP_ITEM);
        DailyActionRequest request = new DailyActionRequest(baseRequest.kind(), baseRequest.itemId(),
                baseRequest.targetId(), baseRequest.secondaryTargetId(), baseRequest.dimension(),
                baseRequest.target(), baseRequest.action(), baseRequest.destination(), baseRequest.direction(),
                -1, baseRequest.quantity(), baseRequest.durationTicks(), baseRequest.maxTicks());
        DailyActionSnapshot base = snapshot(request, DailyActionPhase.VALIDATE, false);
        DailyActionSnapshot broken = new DailyActionSnapshot(
                base.tick(), base.alive(), base.dimension(), base.position(), Map.of(),
                Map.of("HEAD", new DailyActionSnapshot.ItemFact(request.itemId(), 1, 99, 100, Map.of())),
                base.bed(), base.bucket(), base.vehicle(), base.fish(), base.crop(), base.breed(),
                base.menu(), base.experienceLevel(), base.sleeping(), base.onGround(), base.fallFlying(),
                java.util.List.of(), base.bedCandidates(), base.vehicleCandidates(), base.cropCandidates(),
                base.animalCandidates(), base.entityCandidates(), base.trade(), base.enchant(), base.brew(),
                base.glide());
        DailyActionEngine engine = new DailyActionEngine();
        engine.start("broken-worn", request, 0);

        DailyActionEngine.TickResult result = engine.tick("broken-worn", broken,
                command -> DailyActionCommand.CommandResult.success());

        assertEquals(DailyActionEngine.Status.BLOCKED, result.session().status());
        assertEquals("EQUIPMENT_UNUSABLE", result.observation().code());
    }

    @Test
    void explicitVehicleTargetIsNeverReplacedByTheCurrentWrongVehicle() {
        String requestedId = java.util.UUID.nameUUIDFromBytes("requested-vehicle".getBytes()).toString();
        java.util.UUID wrongId = java.util.UUID.nameUUIDFromBytes("wrong-vehicle".getBytes());
        DailyActionRequest request = new DailyActionRequest(DailyActionKind.USE_VEHICLE, "", requestedId,
                "", ORIGIN.dimension(), null, "MOUNT", "", "", -1, 1, 0, 300);
        DailyActionSnapshot base = snapshot(request, DailyActionPhase.APPROACH_VEHICLE, false);
        DailyActionSnapshot wrongVehicle = new DailyActionSnapshot(
                base.tick(), base.alive(), base.dimension(), base.position(), base.inventory(),
                base.equipment(), base.bed(), base.bucket(),
                new DailyActionSnapshot.VehicleFact(true, wrongId, "minecraft:oak_boat", ORIGIN,
                        true, false, true),
                base.fish(), base.crop(), base.breed(), base.menu(), base.experienceLevel(),
                base.sleeping(), base.onGround(), base.fallFlying(), base.itemCandidates(),
                base.bedCandidates(), java.util.List.of(new DailyActionSnapshot.VehicleCandidate(
                        wrongId, "minecraft:oak_boat", ORIGIN, true)), base.cropCandidates(),
                base.animalCandidates(), base.entityCandidates(), base.trade(), base.enchant(),
                base.brew(), base.glide());
        DailyActionEngine engine = new DailyActionEngine();
        engine.start("wrong-vehicle", request, 0);

        DailyActionEngine.TickResult result = engine.tick("wrong-vehicle", wrongVehicle,
                command -> DailyActionCommand.CommandResult.rejected("TARGET_NOT_FOUND"));

        assertEquals(DailyActionEngine.Status.BLOCKED, result.session().status());
        assertEquals(requestedId, result.session().selectedTargetId());
        assertTrue(!wrongId.toString().equals(result.session().selectedTargetId()));
    }

    @Test
    void vehicleTravelCommandUsesTheRequestedDestination() {
        DailyActionRequest base = request(DailyActionKind.USE_VEHICLE);
        DailyActionRequest.Position destination =
                new DailyActionRequest.Position(ORIGIN.dimension(), 12, 64, 0);
        DailyActionRequest request = new DailyActionRequest(base.kind(), base.itemId(), base.targetId(),
                base.secondaryTargetId(), base.dimension(), destination, "TRAVEL", base.destination(),
                base.direction(), base.slot(), base.quantity(), base.durationTicks(), base.maxTicks());
        DailyActionEngine engine = new DailyActionEngine();
        engine.start("vehicle-destination", request, 0);
        DailyActionCommand[] dispatched = new DailyActionCommand[1];
        DailyActionEngine.Adapter adapter = command -> {
            dispatched[0] = command;
            return DailyActionCommand.CommandResult.success();
        };

        engine.tick("vehicle-destination", snapshot(request, DailyActionPhase.APPROACH_VEHICLE, false), adapter);
        engine.tick("vehicle-destination", snapshot(request, DailyActionPhase.BOARD_VEHICLE, true), adapter);
        engine.tick("vehicle-destination", snapshot(request, DailyActionPhase.TRAVEL_VEHICLE, true), adapter);

        DailyActionCommand.Vehicle vehicle = (DailyActionCommand.Vehicle) dispatched[0];
        assertEquals(destination, vehicle.target());
    }

    @Test
    void fishingFailsClearlyWhenTheSelectedRodDisappearsWhileWaiting() {
        DailyActionRequest request = request(DailyActionKind.FISH);
        DailyActionEngine engine = new DailyActionEngine();
        engine.start("lost-rod", request, 0);
        DailyActionEngine.Adapter adapter = command -> DailyActionCommand.CommandResult.success();
        engine.tick("lost-rod", snapshot(request, DailyActionPhase.EQUIP_ROD, true), adapter);
        engine.tick("lost-rod", snapshot(request, DailyActionPhase.NAVIGATE_FISH, true), adapter);
        engine.tick("lost-rod", snapshot(request, DailyActionPhase.CAST_LINE, true), adapter);

        DailyActionSnapshot waiting = snapshot(request, DailyActionPhase.WAIT_BITE, true);
        DailyActionEngine.TickResult result = engine.tick("lost-rod", withEquipment(waiting, Map.of()), adapter);

        assertEquals(DailyActionEngine.Status.BLOCKED, result.session().status());
        assertEquals("FISHING_ROD_MISSING", result.observation().code());
    }

    @Test
    void tradeRejectsAnOfferWhoseContentsChangeAfterSelection() {
        DailyActionRequest request = request(DailyActionKind.TRADE_WITH_VILLAGER);
        DailyActionEngine engine = new DailyActionEngine();
        DailyActionEngine.Adapter adapter = command -> DailyActionCommand.CommandResult.success();
        engine.start("changed-trade", request, 0);
        engine.tick("changed-trade", snapshot(request, DailyActionPhase.APPROACH_TRADE, true), adapter);
        engine.tick("changed-trade", snapshot(request, DailyActionPhase.OPEN_TRADE, true), adapter);
        DailyActionSnapshot selected = snapshot(request, DailyActionPhase.SELECT_TRADE, true);
        DailyActionSnapshot.TradeFact original = selected.trade();
        DailyActionSnapshot.TradeFact changed = new DailyActionSnapshot.TradeFact(
                original.valid(), original.villagerId(), original.offerIndex(), original.disabled(),
                original.uses(), original.inputA(), original.inputB(), "minecraft:diamond",
                original.resourcesAvailable(), original.inventorySpace(), original.inputsReady(),
                original.inputAConsumed(), original.inputBConsumed(), original.outputReceived());

        DailyActionEngine.TickResult result = engine.tick("changed-trade", withTrade(selected, changed), adapter);

        assertEquals(DailyActionEngine.Status.BLOCKED, result.session().status());
        assertEquals("TRADE_OFFER_CHANGED", result.observation().code());
    }

    @Test
    void tradeCanVerifyTheLastUseThatExhaustsAnOffer() {
        DailyActionRequest request = request(DailyActionKind.TRADE_WITH_VILLAGER);
        DailyActionEngine engine = new DailyActionEngine();
        DailyActionEngine.Adapter adapter = command -> DailyActionCommand.CommandResult.success();
        engine.start("last-trade", request, 0);
        engine.tick("last-trade", snapshot(request, DailyActionPhase.APPROACH_TRADE, true), adapter);
        engine.tick("last-trade", snapshot(request, DailyActionPhase.OPEN_TRADE, true), adapter);
        engine.tick("last-trade", snapshot(request, DailyActionPhase.SELECT_TRADE, true), adapter);
        DailyActionSnapshot executed = withDisabledTrade(
                snapshot(request, DailyActionPhase.EXECUTE_TRADE, true));
        engine.tick("last-trade", executed, adapter);
        DailyActionEngine.TickResult result = engine.tick("last-trade",
                withDisabledTrade(snapshot(request, DailyActionPhase.VERIFY_TRADE, true)), adapter);

        assertEquals(DailyActionEngine.Status.COMPLETE, result.session().status());
    }

    @Test
    void enchantingRejectsAChangedMenuItemBeforeApplyingTheOption() {
        DailyActionRequest request = request(DailyActionKind.ENCHANT_ITEM);
        DailyActionEngine engine = new DailyActionEngine();
        DailyActionEngine.Adapter adapter = command -> DailyActionCommand.CommandResult.success();
        engine.start("changed-enchant", request, 0);
        engine.tick("changed-enchant", snapshot(request, DailyActionPhase.NAVIGATE_ENCHANT, true), adapter);
        engine.tick("changed-enchant", snapshot(request, DailyActionPhase.OPEN_ENCHANT, true), adapter);
        engine.tick("changed-enchant", snapshot(request, DailyActionPhase.SELECT_ENCHANT, true), adapter);
        DailyActionSnapshot applying = snapshot(request, DailyActionPhase.APPLY_ENCHANT, true);
        DailyActionSnapshot.EnchantFact original = applying.enchant();
        DailyActionSnapshot.EnchantFact changed = new DailyActionSnapshot.EnchantFact(
                original.valid(), original.station(), "minecraft:diamond_sword", original.option(), original.cost(),
                original.lapisAvailable(), original.inputsReady(), original.itemDigest(),
                original.levelsSpent(), original.lapisSpent());

        DailyActionEngine.TickResult result = engine.tick(
                "changed-enchant", withEnchant(applying, changed), adapter);

        assertEquals(DailyActionEngine.Status.BLOCKED, result.session().status());
        assertEquals("ENCHANTMENT_ITEM_CHANGED", result.observation().code());
    }

    @Test
    void brewingRejectsBottleChangesWhileBrewingIsStillInProgress() {
        DailyActionRequest request = request(DailyActionKind.BREW_POTION);
        DailyActionEngine engine = new DailyActionEngine();
        DailyActionEngine.Adapter adapter = command -> DailyActionCommand.CommandResult.success();
        engine.start("changed-brew", request, 0);
        engine.tick("changed-brew", snapshot(request, DailyActionPhase.NAVIGATE_BREW, true), adapter);
        engine.tick("changed-brew", snapshot(request, DailyActionPhase.OPEN_BREW, true), adapter);
        engine.tick("changed-brew", snapshot(request, DailyActionPhase.LOAD_BREW, true), adapter);
        DailyActionSnapshot waiting = snapshot(request, DailyActionPhase.WAIT_BREW, true);
        DailyActionSnapshot.BrewFact original = waiting.brew();
        DailyActionSnapshot.BrewFact changed = new DailyActionSnapshot.BrewFact(
                original.valid(), original.station(), original.ingredientId(), original.bottleCount(),
                original.fuel(), original.materialsAvailable(), original.fuelAvailable(),
                original.inputsReady(), 10, Map.of(0, "tampered"), 0, 0);

        DailyActionEngine.TickResult result = engine.tick("changed-brew", withBrew(waiting, changed), adapter);

        assertEquals(DailyActionEngine.Status.BLOCKED, result.session().status());
        assertEquals("BREWING_CONTENT_CHANGED", result.observation().code());
    }

    @Test
    void pausedTicksDoNotConsumeTheActionBudgetAndRestoreKeepsIt() {
        DailyActionEngine engine = new DailyActionEngine();
        DailyActionRequest request = new DailyActionRequest(DailyActionKind.FISH, "minecraft:fishing_rod",
                "", "", ORIGIN.dimension(), ORIGIN, "", "MAIN_HAND", "", -1, 1, 0, 10);
        engine.start("budget", request, 0);
        engine.pause("budget", "user");
        engine.resume("budget", 100);
        assertEquals(100, engine.inspect("budget").pausedTicks());
        var saved = engine.inspect("budget");
        engine.remove("budget");
        var restored = engine.restore(saved);
        assertEquals(100, restored.pausedTicks());
        assertEquals(saved.phase(), restored.phase());
    }

    @Test
    void terminalAndCancellationCleanupRunExactlyOnceAndRestoreDoesNotReplay() {
        DailyActionRequest request = request(DailyActionKind.EQUIP_ITEM);
        DailyActionEngine engine = new DailyActionEngine();
        TrackingAdapter adapter = new TrackingAdapter();
        engine.start("cleanup", request, 0);
        engine.tick("cleanup", snapshot(request, DailyActionPhase.VALIDATE, false), adapter);
        DailyActionEngine.Session inFlight = engine.inspect("cleanup");
        assertTrue(inFlight.dispatched());
        assertEquals("minecraft:diamond_helmet", inFlight.selectedItemId());
        engine.remove("cleanup");
        engine.restore(inFlight);
        DailyActionEngine.TickResult completed = engine.tick("cleanup",
                snapshot(request, DailyActionPhase.VALIDATE, true), adapter);
        assertEquals(DailyActionEngine.Status.COMPLETE, completed.session().status());
        assertEquals(1, adapter.executes.get(), "restored effect was replayed");
        assertEquals(1, adapter.cleanups.get());
        engine.tick("cleanup", snapshot(request, DailyActionPhase.VALIDATE, true), adapter);
        assertEquals(1, adapter.cleanups.get(), "terminal cleanup repeated");

        DailyActionEngine cancelled = new DailyActionEngine();
        TrackingAdapter cancellation = new TrackingAdapter();
        cancelled.start("cancel", request(DailyActionKind.FISH), 0);
        cancelled.cancel("cancel", "user", cancellation);
        assertEquals(1, cancellation.cleanups.get());
        cancelled.cancel("cancel", "duplicate", cancellation);
        assertEquals(1, cancellation.cleanups.get());
    }

    private static DailyActionPhase startPhase(DailyActionKind kind, String action) {
        DailyActionRequest base = request(kind);
        DailyActionRequest value = new DailyActionRequest(kind, base.itemId(), base.targetId(), base.secondaryTargetId(),
                base.dimension(), base.target(), action, base.destination(), base.direction(), base.slot(),
                base.quantity(), base.durationTicks(), base.maxTicks());
        return new DailyActionEngine().start("variant-" + kind + action, value, 0).phase();
    }

    private static DailyActionRequest request(DailyActionKind kind) {
        String item = switch (kind) {
            case EQUIP_ITEM -> "minecraft:diamond_helmet";
            case FISH -> "minecraft:fishing_rod";
            case FARM_CROP -> "minecraft:wheat";
            case BREED_ANIMALS -> "minecraft:cow";
            case ENCHANT_ITEM -> "minecraft:book";
            case BREW_POTION -> "minecraft:nether_wart";
            case GLIDE_WITH_ELYTRA -> "minecraft:elytra";
            default -> "";
        };
        String action = switch (kind) {
            case USE_WATER_BUCKET -> "FILL";
            case USE_VEHICLE -> "TRAVEL";
            case SLEEP_AT_BED -> "SLEEP";
            default -> "";
        };
        String targetId = kind == DailyActionKind.USE_VEHICLE || kind == DailyActionKind.TRADE_WITH_VILLAGER
                ? java.util.UUID.nameUUIDFromBytes((kind + "target").getBytes()).toString() : "";
        return new DailyActionRequest(kind, item, targetId, "", ORIGIN.dimension(), ORIGIN,
                action, kind == DailyActionKind.EQUIP_ITEM ? "HEAD"
                : kind == DailyActionKind.GLIDE_WITH_ELYTRA ? "CHEST" : "MAIN_HAND",
                "FORWARD", 0, 1, 0, 300);
    }

    private static DailyActionSnapshot snapshot(DailyActionRequest request, DailyActionPhase phase, boolean changed) {
        Map<String, DailyActionSnapshot.ItemFact> inventory = new java.util.HashMap<>();
        java.util.function.BiConsumer<String, Integer> put = (id, count) -> inventory.put(id,
                new DailyActionSnapshot.ItemFact(id, count, 0, 100, Map.of()));
        if (!request.itemId().isBlank()) put.accept(request.itemId(), 1);
        put.accept("minecraft:bucket", request.kind() == DailyActionKind.USE_WATER_BUCKET && !changed ? 1 : 0);
        put.accept("minecraft:water_bucket", request.kind() == DailyActionKind.USE_WATER_BUCKET && changed ? 1 : 0);
        put.accept("minecraft:wheat_seeds", request.kind() == DailyActionKind.FARM_CROP ? 2 : 0);
        put.accept("minecraft:wheat", request.kind() == DailyActionKind.BREED_ANIMALS ? 2 : 0);
        put.accept("minecraft:lapis_lazuli", request.kind() == DailyActionKind.ENCHANT_ITEM
                ? (phase == DailyActionPhase.APPLY_ENCHANT || phase == DailyActionPhase.VERIFY_ENCHANT ? 2 : 3) : 0);
        put.accept("minecraft:emerald", request.kind() == DailyActionKind.TRADE_WITH_VILLAGER && changed ? 1 : 0);
        Map<String, DailyActionSnapshot.ItemFact> equipment = new java.util.HashMap<>();
        if (changed && request.kind() == DailyActionKind.EQUIP_ITEM) {
            equipment.put("HEAD", new DailyActionSnapshot.ItemFact(request.itemId(), 1, 0, 100, Map.of()));
        }
        if (changed && request.kind() == DailyActionKind.FISH) {
            equipment.put("MAIN_HAND", new DailyActionSnapshot.ItemFact("minecraft:fishing_rod", 1, 0, 64, Map.of()));
        }
        if (changed && request.kind() == DailyActionKind.GLIDE_WITH_ELYTRA) {
            equipment.put("CHEST", new DailyActionSnapshot.ItemFact("minecraft:elytra", 1, 0, 432, Map.of()));
        }
        DailyActionSnapshot.BedFact bed = request.kind() == DailyActionKind.SLEEP_AT_BED
                ? new DailyActionSnapshot.BedFact(true, ORIGIN, true, "") : null;
        DailyActionSnapshot.BucketFact bucket = request.kind() == DailyActionKind.USE_WATER_BUCKET
                ? new DailyActionSnapshot.BucketFact(changed, changed ? "minecraft:air" : "minecraft:water",
                changed ? 0 : 1, changed ? 1 : 0, true, true, true, "",
                ORIGIN, request.action(), request.direction()) : null;
        java.util.UUID vehicleId = java.util.UUID.nameUUIDFromBytes((DailyActionKind.USE_VEHICLE + "target").getBytes());
        DailyActionSnapshot.VehicleFact vehicle = request.kind() == DailyActionKind.USE_VEHICLE
                ? new DailyActionSnapshot.VehicleFact(true, vehicleId, "minecraft:oak_boat", ORIGIN,
                changed && phase != DailyActionPhase.APPROACH_VEHICLE,
                false, true) : null;
        DailyActionSnapshot.FishFact fish = changed && request.kind() == DailyActionKind.FISH
                ? new DailyActionSnapshot.FishFact(true, phase != DailyActionPhase.EQUIP_ROD,
                phase == DailyActionPhase.WAIT_BITE || phase == DailyActionPhase.REEL_LINE || phase == DailyActionPhase.VERIFY_LOOT,
                phase != DailyActionPhase.REEL_LINE && phase != DailyActionPhase.VERIFY_LOOT,
                phase == DailyActionPhase.VERIFY_LOOT ? 1 : 0, phase == DailyActionPhase.VERIFY_LOOT,
                "hook", "rod", phase == DailyActionPhase.VERIFY_LOOT ? 1 : 0) : null;
        DailyActionSnapshot.CropFact crop = changed && request.kind() == DailyActionKind.FARM_CROP
                ? new DailyActionSnapshot.CropFact(true, ORIGIN,
                phase == DailyActionPhase.SCAN_CROP || changed, phase == DailyActionPhase.HARVEST_CROP
                        || phase == DailyActionPhase.PICKUP_CROP || phase == DailyActionPhase.REPLANT_CROP || phase == DailyActionPhase.VERIFY_CROP,
                phase == DailyActionPhase.PICKUP_CROP || phase == DailyActionPhase.REPLANT_CROP || phase == DailyActionPhase.VERIFY_CROP,
                phase == DailyActionPhase.REPLANT_CROP || phase == DailyActionPhase.VERIFY_CROP,
                phase == DailyActionPhase.VERIFY_CROP ? 1 : 0, "minecraft:wheat_seeds", 1) : null;
        java.util.UUID firstAnimal = java.util.UUID.nameUUIDFromBytes("a".getBytes());
        java.util.UUID secondAnimal = java.util.UUID.nameUUIDFromBytes("b".getBytes());
        DailyActionSnapshot.BreedFact breed = changed && request.kind() == DailyActionKind.BREED_ANIMALS
                ? new DailyActionSnapshot.BreedFact(true,
                phase != DailyActionPhase.FEED_FIRST,
                phase == DailyActionPhase.WAIT_BABY || phase == DailyActionPhase.VERIFY_BREED,
                1, firstAnimal, secondAnimal, 2) : null;
        String menuType = phase.name().contains("TRADE") ? "MERCHANT" : phase.name().contains("ENCHANT") ? "ENCHANTMENT"
                : phase.name().contains("BREW") ? "BREWING" : "";
        Map<String, String> menuDetails = new java.util.HashMap<>();
        menuDetails.put("valid", "true");
        menuDetails.put("inputsReady", "true");
        menuDetails.put("resourcesAvailable", "true");
        menuDetails.put("inventorySpace", "true");
        menuDetails.put("materialsAvailable", "true");
        menuDetails.put("fuelAvailable", "true");
        menuDetails.put("itemDigest", phase == DailyActionPhase.APPLY_ENCHANT
                || phase == DailyActionPhase.VERIFY_ENCHANT ? "enchanted" : "plain");
        menuDetails.put("enchantVerified", Boolean.toString(phase == DailyActionPhase.VERIFY_ENCHANT));
        menuDetails.put("tradeVerified", Boolean.toString(phase == DailyActionPhase.VERIFY_TRADE));
        menuDetails.put("brewVerified", Boolean.toString(phase == DailyActionPhase.VERIFY_BREW));
        DailyActionSnapshot.MenuFact menu = !changed || menuType.isBlank() ? null : new DailyActionSnapshot.MenuFact(true, menuType,
                0, false, "minecraft:wheat", "", "minecraft:emerald",
                phase == DailyActionPhase.EXECUTE_TRADE || phase == DailyActionPhase.VERIFY_TRADE ? 1 : 0,
                10, 1, phase == DailyActionPhase.APPLY_ENCHANT || phase == DailyActionPhase.VERIFY_ENCHANT ? 2 : 3,
                phase == DailyActionPhase.APPLY_ENCHANT || phase == DailyActionPhase.VERIFY_ENCHANT,
                phase == DailyActionPhase.LOAD_BREW ? 20 : 0,
                phase == DailyActionPhase.WAIT_BREW || phase == DailyActionPhase.TAKE_BREW,
                phase == DailyActionPhase.TAKE_BREW ? 0 : 1, menuDetails);
        var beds = request.kind() == DailyActionKind.SLEEP_AT_BED
                ? java.util.List.of(new DailyActionSnapshot.BedCandidate(ORIGIN, false, true, "")) : java.util.List.<DailyActionSnapshot.BedCandidate>of();
        var vehicles = request.kind() == DailyActionKind.USE_VEHICLE
                ? java.util.List.of(new DailyActionSnapshot.VehicleCandidate(vehicleId, "minecraft:oak_boat", ORIGIN, true))
                : java.util.List.<DailyActionSnapshot.VehicleCandidate>of();
        var crops = request.kind() == DailyActionKind.FARM_CROP
                ? java.util.List.of(new DailyActionSnapshot.CropCandidate(ORIGIN, true, "minecraft:wheat_seeds"))
                : java.util.List.<DailyActionSnapshot.CropCandidate>of();
        var animals = request.kind() == DailyActionKind.BREED_ANIMALS
                ? java.util.List.of(new DailyActionSnapshot.AnimalCandidate(firstAnimal, "minecraft:cow", ORIGIN,
                "minecraft:wheat", true, true, false, true),
                new DailyActionSnapshot.AnimalCandidate(secondAnimal, "minecraft:cow", ORIGIN,
                        "minecraft:wheat", true, true, false, true))
                : java.util.List.<DailyActionSnapshot.AnimalCandidate>of();
        java.util.List<DailyActionSnapshot.ItemCandidate> items = new java.util.ArrayList<>();
        if (!request.itemId().isBlank() && request.kind() != DailyActionKind.BREED_ANIMALS
                && request.kind() != DailyActionKind.FARM_CROP) {
            items.add(new DailyActionSnapshot.ItemCandidate(9, request.itemId(), 1, 0, 100,
                    true, 8.0, 7.0, Map.of("equipmentSlot",
                    request.kind() == DailyActionKind.EQUIP_ITEM ? "HEAD"
                            : request.kind() == DailyActionKind.GLIDE_WITH_ELYTRA ? "CHEST" : "MAIN_HAND")));
        }
        if (request.kind() == DailyActionKind.FARM_CROP) {
            items.add(new DailyActionSnapshot.ItemCandidate(10, "minecraft:wheat_seeds", 2, 0, 0,
                    false, 1.0, 1.0, Map.of()));
        }
        if (request.kind() == DailyActionKind.BREED_ANIMALS) {
            items.add(new DailyActionSnapshot.ItemCandidate(11, "minecraft:wheat", 2, 0, 0,
                    false, 1.0, 1.0, Map.of()));
        }
        java.util.List<DailyActionSnapshot.EntityCandidate> entities = request.kind() == DailyActionKind.TRADE_WITH_VILLAGER
                ? java.util.List.of(new DailyActionSnapshot.EntityCandidate(
                java.util.UUID.nameUUIDFromBytes((DailyActionKind.TRADE_WITH_VILLAGER + "target").getBytes()),
                "minecraft:villager", ORIGIN, true, true)) : java.util.List.of();
        DailyActionSnapshot.GlideFact glide = request.kind() == DailyActionKind.GLIDE_WITH_ELYTRA
                ? new DailyActionSnapshot.GlideFact(true, phase == DailyActionPhase.GLIDE || phase == DailyActionPhase.LAND,
                true, 0.0, 4.0, "") : null;
        DailyActionSnapshot.TradeFact trade = request.kind() == DailyActionKind.TRADE_WITH_VILLAGER
                ? new DailyActionSnapshot.TradeFact(true, request.targetId(), request.slot(), false,
                phase == DailyActionPhase.EXECUTE_TRADE || phase == DailyActionPhase.VERIFY_TRADE ? 1 : 0,
                "minecraft:wheat", "", "minecraft:emerald", true, true, true,
                phase == DailyActionPhase.EXECUTE_TRADE || phase == DailyActionPhase.VERIFY_TRADE ? 1 : 0,
                0, phase == DailyActionPhase.EXECUTE_TRADE || phase == DailyActionPhase.VERIFY_TRADE ? 1 : 0)
                : null;
        boolean enchantApplied = phase == DailyActionPhase.APPLY_ENCHANT
                || phase == DailyActionPhase.VERIFY_ENCHANT;
        DailyActionSnapshot.EnchantFact enchant = request.kind() == DailyActionKind.ENCHANT_ITEM
                ? new DailyActionSnapshot.EnchantFact(true, ORIGIN, request.itemId(), request.slot(), 1,
                enchantApplied ? 2 : 3, true, enchantApplied ? "enchanted" : "plain",
                enchantApplied ? 1 : 0, enchantApplied ? 1 : 0) : null;
        boolean brewed = phase == DailyActionPhase.WAIT_BREW || phase == DailyActionPhase.TAKE_BREW
                || phase == DailyActionPhase.VERIFY_BREW;
        DailyActionSnapshot.BrewFact brewFact = request.kind() == DailyActionKind.BREW_POTION
                ? new DailyActionSnapshot.BrewFact(true, ORIGIN, request.itemId(), request.quantity(), 20,
                true, true, true, phase == DailyActionPhase.LOAD_BREW ? 20 : 0,
                Map.of(0, brewed ? "awkward" : "water"),
                phase == DailyActionPhase.TAKE_BREW || phase == DailyActionPhase.VERIFY_BREW ? request.quantity() : 0,
                phase == DailyActionPhase.VERIFY_BREW ? request.quantity() : 0) : null;
        return new DailyActionSnapshot(1, true, ORIGIN.dimension(), ORIGIN, inventory, equipment, bed, bucket,
                vehicle, fish, crop, breed, menu,
                phase == DailyActionPhase.APPLY_ENCHANT || phase == DailyActionPhase.VERIFY_ENCHANT ? 9 : 10,
                changed && phase == DailyActionPhase.SLEEP, changed && phase == DailyActionPhase.LAND,
                changed && (phase == DailyActionPhase.START_GLIDE || phase == DailyActionPhase.GLIDE),
                items, beds, vehicles, crops, animals, entities, trade, enchant, brewFact, glide);
    }

    private static DailyActionSnapshot withEquipment(DailyActionSnapshot value,
                                                     Map<String, DailyActionSnapshot.ItemFact> equipment) {
        return new DailyActionSnapshot(value.tick(), value.alive(), value.dimension(), value.position(),
                value.inventory(), equipment, value.bed(), value.bucket(), value.vehicle(), value.fish(),
                value.crop(), value.breed(), value.menu(), value.experienceLevel(), value.sleeping(),
                value.onGround(), value.fallFlying(), value.itemCandidates(), value.bedCandidates(),
                value.vehicleCandidates(), value.cropCandidates(), value.animalCandidates(),
                value.entityCandidates(), value.trade(), value.enchant(), value.brew(), value.glide());
    }

    private static DailyActionSnapshot withTrade(DailyActionSnapshot value,
                                                 DailyActionSnapshot.TradeFact trade) {
        return copyWithMenuFacts(value, trade, value.enchant(), value.brew());
    }

    private static DailyActionSnapshot withDisabledTrade(DailyActionSnapshot value) {
        DailyActionSnapshot.TradeFact original = value.trade();
        return withTrade(value, new DailyActionSnapshot.TradeFact(
                original.valid(), original.villagerId(), original.offerIndex(), true, original.uses(),
                original.inputA(), original.inputB(), original.output(), original.resourcesAvailable(),
                original.inventorySpace(), original.inputsReady(), original.inputAConsumed(),
                original.inputBConsumed(), original.outputReceived()));
    }

    private static DailyActionSnapshot withEnchant(DailyActionSnapshot value,
                                                   DailyActionSnapshot.EnchantFact enchant) {
        return copyWithMenuFacts(value, value.trade(), enchant, value.brew());
    }

    private static DailyActionSnapshot withBrew(DailyActionSnapshot value,
                                                DailyActionSnapshot.BrewFact brew) {
        return copyWithMenuFacts(value, value.trade(), value.enchant(), brew);
    }

    private static DailyActionSnapshot copyWithMenuFacts(DailyActionSnapshot value,
                                                         DailyActionSnapshot.TradeFact trade,
                                                         DailyActionSnapshot.EnchantFact enchant,
                                                         DailyActionSnapshot.BrewFact brew) {
        return new DailyActionSnapshot(value.tick(), value.alive(), value.dimension(), value.position(),
                value.inventory(), value.equipment(), value.bed(), value.bucket(), value.vehicle(), value.fish(),
                value.crop(), value.breed(), value.menu(), value.experienceLevel(), value.sleeping(),
                value.onGround(), value.fallFlying(), value.itemCandidates(), value.bedCandidates(),
                value.vehicleCandidates(), value.cropCandidates(), value.animalCandidates(),
                value.entityCandidates(), trade, enchant, brew, value.glide());
    }

    private static final class TrackingAdapter implements DailyActionEngine.Adapter {
        private final AtomicInteger executes = new AtomicInteger();
        private final AtomicInteger cleanups = new AtomicInteger();

        @Override public DailyActionCommand.CommandResult execute(DailyActionCommand command) {
            executes.incrementAndGet();
            return DailyActionCommand.CommandResult.success();
        }

        @Override public void cleanup(DailyActionEngine.Session session, String reason) {
            cleanups.incrementAndGet();
        }
    }
}
