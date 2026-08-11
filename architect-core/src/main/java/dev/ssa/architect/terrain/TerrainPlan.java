package dev.ssa.architect.terrain;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record TerrainPlan(
        Strategy strategy,
        int removedBlockCount,
        int filledBlockCount,
        int maxVerticalCut,
        int maxVerticalFill,
        boolean modifyWater,
        boolean modifyLava,
        SalvagePolicy salvagePolicy,
        List<TerrainCellChange> changes) {
    private static final NamespacedId AIR = NamespacedId.parse("minecraft:air");
    private static final Set<NamespacedId> REPLACEABLE_NATURAL_BLOCKS = Set.of(
            NamespacedId.parse("minecraft:andesite"),
            NamespacedId.parse("minecraft:clay"),
            NamespacedId.parse("minecraft:coarse_dirt"),
            NamespacedId.parse("minecraft:deepslate"),
            NamespacedId.parse("minecraft:diorite"),
            NamespacedId.parse("minecraft:dirt"),
            NamespacedId.parse("minecraft:grass_block"),
            NamespacedId.parse("minecraft:granite"),
            NamespacedId.parse("minecraft:gravel"),
            NamespacedId.parse("minecraft:moss_block"),
            NamespacedId.parse("minecraft:mud"),
            NamespacedId.parse("minecraft:mycelium"),
            NamespacedId.parse("minecraft:podzol"),
            NamespacedId.parse("minecraft:red_sand"),
            NamespacedId.parse("minecraft:sand"),
            NamespacedId.parse("minecraft:snow_block"),
            NamespacedId.parse("minecraft:stone"),
            NamespacedId.parse("minecraft:terracotta"));

    public TerrainPlan {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(salvagePolicy, "salvagePolicy");
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        if (removedBlockCount < 0 || filledBlockCount < 0
                || maxVerticalCut < 0 || maxVerticalFill < 0) {
            throw new IllegalArgumentException("Terrain counts and vertical extrema must not be negative");
        }

        Set<GridPos> positions = new HashSet<>();
        Map<Column, List<Integer>> removalsByColumn = new HashMap<>();
        Map<Column, List<Integer>> fillsByColumn = new HashMap<>();
        int actualRemoved = 0;
        int actualFilled = 0;
        for (TerrainCellChange change : changes) {
            if (!positions.add(change.pos())) {
                throw new IllegalArgumentException(
                        "Terrain plan contains duplicate position: " + change.pos());
            }
            Column column = new Column(change.pos().x(), change.pos().z());
            ChangeType type = classify(change);
            if (type == ChangeType.REMOVE) {
                actualRemoved++;
                removalsByColumn.computeIfAbsent(column, ignored -> new ArrayList<>()).add(change.pos().y());
            } else {
                actualFilled++;
                fillsByColumn.computeIfAbsent(column, ignored -> new ArrayList<>()).add(change.pos().y());
            }
        }

        int actualMaxCut = maximumContiguousDepth(removalsByColumn, "cut");
        int actualMaxFill = maximumContiguousDepth(fillsByColumn, "fill");
        if (removedBlockCount != actualRemoved || filledBlockCount != actualFilled
                || maxVerticalCut != actualMaxCut || maxVerticalFill != actualMaxFill) {
            throw new IllegalArgumentException("Terrain summary must exactly match its cell changes");
        }
        boolean validPillar = strategy == Strategy.PILLAR && actualRemoved == 0 && actualFilled == 0;
        if (strategy != expectedStrategy(actualRemoved, actualFilled) && !validPillar) {
            throw new IllegalArgumentException("Terrain strategy does not match its cell changes");
        }
    }

    public int removedCount() {
        return removedBlockCount;
    }

    public int filledCount() {
        return filledBlockCount;
    }

    public boolean within(TerrainBudget budget) {
        Objects.requireNonNull(budget, "budget");
        return removedBlockCount <= budget.maxRemovedBlocks()
                && filledBlockCount <= budget.maxFilledBlocks()
                && maxVerticalCut <= budget.maxVerticalCut()
                && maxVerticalFill <= budget.maxVerticalFill()
                && (!modifyWater || budget.waterModification())
                && (!modifyLava || budget.lavaModification());
    }

    public boolean hasUnsafeRemoval() {
        return changes.stream()
                .filter(change -> classify(change) == ChangeType.REMOVE)
                .map(change -> change.beforeState().blockId())
                .anyMatch(blockId -> !isReplaceableNatural(blockId));
    }

    public static boolean isReplaceableNatural(NamespacedId blockId) {
        return REPLACEABLE_NATURAL_BLOCKS.contains(Objects.requireNonNull(blockId, "blockId"));
    }

    private static ChangeType classify(TerrainCellChange change) {
        boolean beforeAir = change.beforeState().blockId().equals(AIR);
        boolean afterAir = change.afterState().blockId().equals(AIR);
        if (!beforeAir && afterAir) {
            return ChangeType.REMOVE;
        }
        if (beforeAir && !afterAir) {
            return ChangeType.FILL;
        }
        throw new IllegalArgumentException("Terrain changes must be either removal or fill operations");
    }

    private static int maximumContiguousDepth(
            Map<Column, List<Integer>> changesByColumn,
            String label) {
        int maximum = 0;
        for (List<Integer> heights : changesByColumn.values()) {
            heights.sort(Integer::compareTo);
            for (int index = 1; index < heights.size(); index++) {
                if (heights.get(index) != heights.get(index - 1) + 1) {
                    throw new IllegalArgumentException(
                            "Terrain " + label + " changes must be vertically contiguous");
                }
            }
            maximum = Math.max(maximum, heights.size());
        }
        return maximum;
    }

    private static Strategy expectedStrategy(int removed, int filled) {
        if (removed == 0 && filled == 0) {
            return Strategy.FLAT;
        }
        if (filled == 0) {
            return Strategy.CUT;
        }
        if (removed == 0) {
            return Strategy.FILL;
        }
        return Strategy.MIXED;
    }

    public enum Strategy {
        FLAT,
        CUT,
        FILL,
        PILLAR,
        MIXED
    }

    public enum SalvagePolicy {
        DISCARD_NO_DROPS
    }

    public enum DropPolicy {
        SUPPRESS
    }

    public enum XpPolicy {
        SUPPRESS
    }

    public record TerrainCellChange(
            GridPos pos,
            BlockStateSpec beforeState,
            BlockStateSpec afterState,
            DropPolicy dropPolicy,
            XpPolicy xpPolicy) {
        public TerrainCellChange {
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(beforeState, "beforeState");
            Objects.requireNonNull(afterState, "afterState");
            Objects.requireNonNull(dropPolicy, "dropPolicy");
            Objects.requireNonNull(xpPolicy, "xpPolicy");
        }
    }

    private enum ChangeType {
        REMOVE,
        FILL
    }

    private record Column(int x, int z) {
    }
}
