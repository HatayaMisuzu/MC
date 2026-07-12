package com.mccompanion.minecraft.fabric;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/** World-scoped registry metadata; vanilla player-data files persist body position and inventory. */
final class CompanionSavedData extends SavedData {
    static final String STORAGE_ID = "minecraft_ai_companion_registry";
    static final Factory<CompanionSavedData> FACTORY = new Factory<>(
            CompanionSavedData::new,
            CompanionSavedData::load,
            DataFixTypes.LEVEL);

    private final Map<UUID, CompanionEntry> byOwner = new LinkedHashMap<>();

    static CompanionSavedData load(CompoundTag root, HolderLookup.Provider registries) {
        CompanionSavedData data = new CompanionSavedData();
        ListTag entries = root.getList("companions", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag tag = entries.getCompound(index);
            if (!tag.hasUUID("companionId") || !tag.hasUUID("ownerId")) {
                continue;
            }
            try {
                CompanionEntry entry = CompanionEntry.load(tag);
                data.byOwner.put(entry.ownerId, entry);
            } catch (IllegalArgumentException ignored) {
                // A malformed entry is skipped rather than preventing the world from loading.
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        for (CompanionEntry entry : byOwner.values()) {
            entries.add(entry.save());
        }
        root.put("companions", entries);
        root.putInt("schemaVersion", 1);
        return root;
    }

    CompanionEntry get(UUID ownerId) {
        return byOwner.get(ownerId);
    }

    Collection<CompanionEntry> entries() {
        return byOwner.values();
    }

    void put(CompanionEntry entry) {
        byOwner.put(entry.ownerId, entry);
        setDirty();
    }

    CompanionEntry remove(UUID ownerId) {
        CompanionEntry removed = byOwner.remove(ownerId);
        if (removed != null) {
            setDirty();
        }
        return removed;
    }

    void changed() {
        setDirty();
    }
}
