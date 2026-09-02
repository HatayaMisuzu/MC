package com.mccompanion.core.body.build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** A deliberately small, bounded construction description authored by the external Brain. */
public record SmallBlueprint(
        Anchor anchor,
        Size maxSize,
        List<Block> blocks,
        SupportPolicy temporarySupport) {
    public static final int MAX_BLOCKS = 128;
    public static final int MAX_AXIS = 7;
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern STATE_NAME = Pattern.compile("[a-z0-9_]+");
    private static final Pattern STATE_VALUE = Pattern.compile("[a-z0-9_.-]+");

    public SmallBlueprint {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(maxSize, "maxSize");
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        temporarySupport = temporarySupport == null ? SupportPolicy.none() : temporarySupport;
        if (blocks.isEmpty() || blocks.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("blueprint blocks must contain 1.." + MAX_BLOCKS + " entries");
        }
        Set<Offset> occupied = new HashSet<>();
        for (Block block : blocks) {
            Objects.requireNonNull(block, "block");
            if (!occupied.add(block.offset())) throw new IllegalArgumentException("duplicate blueprint position");
            if (block.offset().x() < 0 || block.offset().x() >= maxSize.x()
                    || block.offset().y() < 0 || block.offset().y() >= maxSize.y()
                    || block.offset().z() < 0 || block.offset().z() >= maxSize.z()) {
                throw new IllegalArgumentException("blueprint position exceeds maxSize");
            }
        }
    }

    /** Stable bottom-up order: lower supports are built before dependent or oriented blocks. */
    public List<IndexedBlock> buildOrder() {
        List<IndexedBlock> ordered = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) ordered.add(new IndexedBlock(index, blocks.get(index)));
        ordered.sort(Comparator.comparingInt((IndexedBlock value) -> value.block().offset().y())
                .thenComparingInt(value -> value.block().offset().z())
                .thenComparingInt(value -> value.block().offset().x())
                .thenComparingInt(IndexedBlock::index));
        return List.copyOf(ordered);
    }

    public record Anchor(String dimension, int x, int y, int z) {
        public Anchor {
            dimension = requireIdentifier(dimension, "anchor.dimension");
            if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000
                    || y < -2048 || y > 2048) throw new IllegalArgumentException("anchor is outside safe world bounds");
        }
    }

    public record Size(int x, int y, int z) {
        public Size {
            if (x < 1 || x > MAX_AXIS || y < 1 || y > MAX_AXIS || z < 1 || z > MAX_AXIS) {
                throw new IllegalArgumentException("maxSize axes must be 1.." + MAX_AXIS);
            }
        }
    }

    public record Offset(int x, int y, int z) { }

    public record Block(Offset offset, String blockId, Map<String, String> state, List<String> alternatives) {
        public Block {
            Objects.requireNonNull(offset, "offset");
            blockId = requireIdentifier(blockId, "block");
            state = Map.copyOf(state == null ? Map.of() : state);
            if (state.size() > 8) throw new IllegalArgumentException("block state has too many properties");
            state.forEach((name, value) -> {
                if (name == null || !STATE_NAME.matcher(name).matches()
                        || value == null || !STATE_VALUE.matcher(value).matches()) {
                    throw new IllegalArgumentException("invalid block state property");
                }
            });
            alternatives = List.copyOf(alternatives == null ? List.of() : alternatives);
            if (alternatives.size() > 8) throw new IllegalArgumentException("too many material alternatives");
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            unique.add(blockId);
            for (String alternative : alternatives) {
                alternative = requireIdentifier(alternative, "material alternative");
                if (!unique.add(alternative)) throw new IllegalArgumentException("duplicate material alternative");
            }
        }

        public List<String> materials() {
            ArrayList<String> result = new ArrayList<>();
            result.add(blockId);
            result.addAll(alternatives);
            return List.copyOf(result);
        }
    }

    public record SupportPolicy(List<String> blocks, int maxBlocks, boolean cleanup) {
        public SupportPolicy {
            blocks = List.copyOf(blocks == null ? List.of() : blocks);
            if (maxBlocks < 0 || maxBlocks > 16) throw new IllegalArgumentException("max temporary supports must be 0..16");
            if ((maxBlocks == 0) != blocks.isEmpty()) {
                throw new IllegalArgumentException("temporary support blocks are required exactly when maxBlocks is positive");
            }
            if (blocks.size() > 8) throw new IllegalArgumentException("too many temporary support alternatives");
            Set<String> unique = new HashSet<>();
            for (String block : blocks) {
                block = requireIdentifier(block, "temporary support block");
                if (!unique.add(block)) throw new IllegalArgumentException("duplicate temporary support block");
            }
        }

        public static SupportPolicy none() { return new SupportPolicy(List.of(), 0, true); }
    }

    public record IndexedBlock(int index, Block block) { }

    private static String requireIdentifier(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be a namespaced identifier");
        }
        return value;
    }
}
