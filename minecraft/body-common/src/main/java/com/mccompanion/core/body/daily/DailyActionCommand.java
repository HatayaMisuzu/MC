package com.mccompanion.core.body.daily;

import java.util.Map;

/** Typed command boundary. A loader adapter executes exactly one command through vanilla APIs. */
public sealed interface DailyActionCommand permits DailyActionCommand.Navigate, DailyActionCommand.NavigateEntity,
        DailyActionCommand.StopMovement,
        DailyActionCommand.Equip, DailyActionCommand.Bed,
        DailyActionCommand.Bucket, DailyActionCommand.Vehicle, DailyActionCommand.Fishing,
        DailyActionCommand.Crop, DailyActionCommand.Breed, DailyActionCommand.Trade,
        DailyActionCommand.Enchant, DailyActionCommand.Brew, DailyActionCommand.Glide {
    DailyActionKind kind();

    record Navigate(DailyActionKind kind, DailyActionRequest.Position target,
                    String reason) implements DailyActionCommand {
        public Navigate(DailyActionRequest.Position target, String reason) {
            this(null, target, reason);
        }
    }

    /** Dynamic entity goal; the adapter supplies its latest observed position to the shared navigator. */
    record NavigateEntity(DailyActionKind kind, String targetId,
                          String reason) implements DailyActionCommand { }

    record StopMovement(DailyActionKind kind, String reason) implements DailyActionCommand {
        public StopMovement(String reason) { this(null, reason); }
    }

    record Equip(String action, String itemId, int sourceSlot, String destination,
                 String stackDigest) implements DailyActionCommand {
        public Equip(String action, String itemId, int sourceSlot, String destination) {
            this(action, itemId, sourceSlot, destination, "");
        }
        public Equip(String itemId, String destination, int slot) {
            this("EQUIP", itemId, slot, destination, "");
        }
        public DailyActionKind kind() { return DailyActionKind.EQUIP_ITEM; }
    }
    record Bed(String action, DailyActionRequest.Position target) implements DailyActionCommand {
        public DailyActionKind kind() { return DailyActionKind.SLEEP_AT_BED; }
    }
    record Bucket(String action, DailyActionRequest.Position target, String direction) implements DailyActionCommand {
        public DailyActionKind kind() { return DailyActionKind.USE_WATER_BUCKET; }
    }
    record Vehicle(String action, String targetId, DailyActionRequest.Position target, String direction) implements DailyActionCommand {
        public Vehicle(String action, String targetId, String direction) {
            this(action, targetId, null, direction);
        }
        public DailyActionKind kind() { return DailyActionKind.USE_VEHICLE; }
    }
    record Fishing(String action, String rodItemId,
                   DailyActionRequest.Position waterTarget) implements DailyActionCommand {
        public Fishing(String action) { this(action, "", null); }
        public Fishing(String action, String rodItemId) { this(action, rodItemId, null); }
        public DailyActionKind kind() { return DailyActionKind.FISH; }
    }
    record Crop(String action, DailyActionRequest.Position target, String cropId,
                String seedItem) implements DailyActionCommand {
        public Crop(String action, DailyActionRequest.Position target, String seedItem) {
            this(action, target, "", seedItem);
        }
        public DailyActionKind kind() { return DailyActionKind.FARM_CROP; }
    }
    record Breed(String action, String firstId, String secondId, String foodItem) implements DailyActionCommand {
        public DailyActionKind kind() { return DailyActionKind.BREED_ANIMALS; }
    }
    record Trade(String action, String villagerId, int offerIndex) implements DailyActionCommand {
        public Trade(String action, int offerIndex) { this(action, "", offerIndex); }
        public DailyActionKind kind() { return DailyActionKind.TRADE_WITH_VILLAGER; }
    }
    record Enchant(String action, DailyActionRequest.Position station, int option) implements DailyActionCommand {
        public Enchant(String action, int option) { this(action, null, option); }
        public DailyActionKind kind() { return DailyActionKind.ENCHANT_ITEM; }
    }
    record Brew(String action, DailyActionRequest.Position station, String itemId,
                int slot, int bottleCount) implements DailyActionCommand {
        public Brew(String action, DailyActionRequest.Position station, String itemId, int slot) {
            this(action, station, itemId, slot, 1);
        }
        public Brew(String action, String itemId, int slot) { this(action, null, itemId, slot, 1); }
        public DailyActionKind kind() { return DailyActionKind.BREW_POTION; }
    }
    record Glide(String action, DailyActionRequest.Position target, String direction,
                 String elytraItemId, int sourceSlot, String stackDigest) implements DailyActionCommand {
        public Glide(String action, DailyActionRequest.Position target, String direction) {
            this(action, target, direction, "minecraft:elytra", -1, "");
        }
        public Glide(String action, String direction) { this(action, null, direction); }
        public DailyActionKind kind() { return DailyActionKind.GLIDE_WITH_ELYTRA; }
    }

    record CommandResult(boolean accepted, boolean uncertain, String code, Map<String, String> details) {
        public CommandResult {
            code = code == null ? (accepted ? "ACCEPTED" : "REJECTED") : code;
            details = details == null ? Map.of() : Map.copyOf(details);
        }
        public static CommandResult success() { return new CommandResult(true, false, "ACCEPTED", Map.of()); }
        public static CommandResult rejected(String code) { return new CommandResult(false, false, code, Map.of()); }
        public static CommandResult uncertain(String code) { return new CommandResult(false, true, code, Map.of()); }
    }
}
