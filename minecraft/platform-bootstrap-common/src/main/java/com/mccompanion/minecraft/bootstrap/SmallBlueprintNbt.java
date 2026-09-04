package com.mccompanion.minecraft.bootstrap;

import com.mccompanion.core.body.build.SmallBlueprint;
import com.mccompanion.core.body.build.SmallBlueprintExecutor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** Version-neutral NBT codec for durable bounded blueprint progress. */
public final class SmallBlueprintNbt {
    private SmallBlueprintNbt() { }

    public static CompoundTag save(SmallBlueprintExecutor.Session session) {
        CompoundTag root = new CompoundTag();
        root.putInt("schemaVersion", 1);
        root.putInt("supportsPlaced", session.supportsPlaced());
        root.put("plan", savePlan(session.plan()));
        root.putIntArray("completed", session.completed().stream().mapToInt(Integer::intValue).toArray());
        CompoundTag selections = new CompoundTag();
        session.selections().forEach((index, block) -> selections.putString(Integer.toString(index), block));
        root.put("selections", selections);
        ListTag supports = new ListTag();
        session.temporarySupports().forEach((position, block) -> {
            CompoundTag support = new CompoundTag();
            support.putInt("x", position.x()); support.putInt("y", position.y()); support.putInt("z", position.z());
            support.putString("block", block);
            supports.add(support);
        });
        root.put("temporarySupports", supports);
        return root;
    }

    public static SmallBlueprintExecutor.Session load(CompoundTag root) {
        if (root.getInt("schemaVersion") != 1) throw new IllegalArgumentException("unsupported blueprint schema");
        SmallBlueprint plan = loadPlan(root.getCompound("plan"));
        LinkedHashSet<Integer> completed = new LinkedHashSet<>();
        for (int index : root.getIntArray("completed")) completed.add(index);
        Map<Integer, String> selections = new LinkedHashMap<>();
        CompoundTag selectionTag = root.getCompound("selections");
        for (String key : selectionTag.getAllKeys()) selections.put(Integer.parseInt(key), selectionTag.getString(key));
        Map<SmallBlueprintExecutor.Position, String> supports = new LinkedHashMap<>();
        ListTag supportTags = root.getList("temporarySupports", Tag.TAG_COMPOUND);
        for (int index = 0; index < supportTags.size(); index++) {
            CompoundTag support = supportTags.getCompound(index);
            supports.put(new SmallBlueprintExecutor.Position(
                    support.getInt("x"), support.getInt("y"), support.getInt("z")), support.getString("block"));
        }
        SmallBlueprintExecutor.Session session = new SmallBlueprintExecutor.Session(
                plan, completed, selections, supports, root.getInt("supportsPlaced"));
        session.requireReconciliation();
        return session;
    }

    private static CompoundTag savePlan(SmallBlueprint plan) {
        CompoundTag root = new CompoundTag();
        CompoundTag anchor = new CompoundTag();
        anchor.putString("dimension", plan.anchor().dimension());
        anchor.putInt("x", plan.anchor().x()); anchor.putInt("y", plan.anchor().y()); anchor.putInt("z", plan.anchor().z());
        root.put("anchor", anchor);
        CompoundTag size = new CompoundTag();
        size.putInt("x", plan.maxSize().x()); size.putInt("y", plan.maxSize().y()); size.putInt("z", plan.maxSize().z());
        root.put("maxSize", size);
        ListTag blocks = new ListTag();
        for (SmallBlueprint.Block block : plan.blocks()) {
            CompoundTag value = new CompoundTag();
            value.putInt("x", block.offset().x()); value.putInt("y", block.offset().y()); value.putInt("z", block.offset().z());
            value.putString("block", block.blockId());
            CompoundTag state = new CompoundTag();
            block.state().forEach(state::putString);
            value.put("state", state);
            ListTag alternatives = new ListTag();
            block.alternatives().forEach(id -> alternatives.add(StringTag.valueOf(id)));
            value.put("alternatives", alternatives);
            blocks.add(value);
        }
        root.put("blocks", blocks);
        CompoundTag support = new CompoundTag();
        support.putInt("maxBlocks", plan.temporarySupport().maxBlocks());
        support.putBoolean("cleanup", plan.temporarySupport().cleanup());
        ListTag supportBlocks = new ListTag();
        plan.temporarySupport().blocks().forEach(id -> supportBlocks.add(StringTag.valueOf(id)));
        support.put("blocks", supportBlocks);
        root.put("temporarySupport", support);
        return root;
    }

    private static SmallBlueprint loadPlan(CompoundTag root) {
        CompoundTag anchor = root.getCompound("anchor");
        CompoundTag size = root.getCompound("maxSize");
        List<SmallBlueprint.Block> blocks = new ArrayList<>();
        ListTag blockTags = root.getList("blocks", Tag.TAG_COMPOUND);
        for (int index = 0; index < blockTags.size(); index++) {
            CompoundTag value = blockTags.getCompound(index);
            Map<String, String> state = new LinkedHashMap<>();
            CompoundTag stateTag = value.getCompound("state");
            stateTag.getAllKeys().forEach(key -> state.put(key, stateTag.getString(key)));
            List<String> alternatives = strings(value.getList("alternatives", Tag.TAG_STRING));
            blocks.add(new SmallBlueprint.Block(new SmallBlueprint.Offset(
                    value.getInt("x"), value.getInt("y"), value.getInt("z")),
                    value.getString("block"), state, alternatives));
        }
        CompoundTag support = root.getCompound("temporarySupport");
        return new SmallBlueprint(new SmallBlueprint.Anchor(anchor.getString("dimension"),
                anchor.getInt("x"), anchor.getInt("y"), anchor.getInt("z")),
                new SmallBlueprint.Size(size.getInt("x"), size.getInt("y"), size.getInt("z")), blocks,
                new SmallBlueprint.SupportPolicy(strings(support.getList("blocks", Tag.TAG_STRING)),
                        support.getInt("maxBlocks"), support.getBoolean("cleanup")));
    }

    private static List<String> strings(ListTag values) {
        ArrayList<String> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) result.add(values.getString(index));
        return List.copyOf(result);
    }
}
