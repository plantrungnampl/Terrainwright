package dev.ssa.architect.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.TerrainSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class TerrainAdaptationPlannerTest {
    private static final BlockStateSpec DIRT = state("minecraft:dirt");

    @Test
    void plansTheLowestCostHillsideDeterministically() {
        TerrainSnapshot snapshot = snapshot(
                3,
                2,
                List.of(10, 11, 10, 9, 10, 11),
                List.of(
                        "minecraft:grass_block",
                        "minecraft:dirt",
                        "minecraft:grass_block",
                        "minecraft:grass_block",
                        "minecraft:dirt",
                        "minecraft:stone"),
                Set.of(),
                Set.of());
        Set<GridPos> footprint = footprint(3, 2);
        TerrainAdaptationPlanner planner = new TerrainAdaptationPlanner();

        TerrainPlan first = planner.plan(snapshot, footprint, DIRT, TerrainBudget.light()).orElseThrow();
        TerrainPlan second = planner.plan(snapshot, footprint, DIRT, TerrainBudget.light()).orElseThrow();

        assertEquals(first, second);
        assertEquals(TerrainPlan.Strategy.MIXED, first.strategy());
        assertEquals(2, first.removedCount());
        assertEquals(1, first.filledCount());
        assertEquals(1, first.maxVerticalCut());
        assertEquals(1, first.maxVerticalFill());
        assertTrue(first.changes().stream().allMatch(change ->
                change.dropPolicy() == TerrainPlan.DropPolicy.SUPPRESS
                        && change.xpPolicy() == TerrainPlan.XpPolicy.SUPPRESS));
    }

    @Test
    void preservesLiquidIntersectionEvidenceForHardValidation() {
        TerrainSnapshot snapshot = snapshot(
                2,
                2,
                List.of(10, 10, 10, 10),
                List.of("minecraft:grass_block", "minecraft:grass_block",
                        "minecraft:grass_block", "minecraft:grass_block"),
                Set.of(1),
                Set.of(2));

        TerrainPlan plan = new TerrainAdaptationPlanner()
                .plan(snapshot, footprint(2, 2), DIRT, TerrainBudget.light())
                .orElseThrow();

        assertTrue(plan.modifyWater());
        assertTrue(plan.modifyLava());
        assertEquals(TerrainPlan.Strategy.FLAT, plan.strategy());
    }

    @Test
    void rejectsUnboundedSlopesAndUnsafeNaturalBlockRemoval() {
        TerrainSnapshot steep = snapshot(
                2,
                1,
                List.of(0, 8),
                List.of("minecraft:grass_block", "minecraft:stone"),
                Set.of(),
                Set.of());
        assertTrue(new TerrainAdaptationPlanner()
                .plan(steep, footprint(2, 1), DIRT, TerrainBudget.light())
                .isEmpty());

        TerrainSnapshot unsafe = snapshot(
                2,
                1,
                List.of(10, 11),
                List.of("minecraft:grass_block", "minecraft:chest"),
                Set.of(),
                Set.of());
        assertFalse(new TerrainAdaptationPlanner()
                .plan(unsafe, footprint(2, 1), DIRT, TerrainBudget.light())
                .isPresent());
    }

    @Test
    void rejectsObstructedColumnsAndDishonestVerticalExtrema() {
        TerrainSnapshot obstructed = new TerrainSnapshot(
                new GridPos(0, 0, 0),
                2,
                1,
                10,
                10,
                List.of(10, 10),
                List.of(NamespacedId.parse("minecraft:grass_block"),
                        NamespacedId.parse("minecraft:grass_block")),
                Set.of(new GridPos(1, 11, 0)),
                Set.of(),
                Set.of(),
                Set.of(),
                new TerrainSnapshot.SlopeMetrics(0.0, 0.0),
                Map.of(),
                "obstructed");
        assertTrue(new TerrainAdaptationPlanner()
                .plan(obstructed, footprint(2, 1), DIRT, TerrainBudget.light())
                .isEmpty());

        TerrainPlan.TerrainCellChange removal = new TerrainPlan.TerrainCellChange(
                new GridPos(0, 1, 0),
                state("minecraft:dirt"),
                state("minecraft:air"),
                TerrainPlan.DropPolicy.SUPPRESS,
                TerrainPlan.XpPolicy.SUPPRESS);
        assertThrows(IllegalArgumentException.class, () -> new TerrainPlan(
                TerrainPlan.Strategy.CUT,
                1,
                0,
                0,
                0,
                false,
                false,
                TerrainPlan.SalvagePolicy.DISCARD_NO_DROPS,
                List.of(removal)));
    }

    private static TerrainSnapshot snapshot(
            int width,
            int depth,
            List<Integer> heights,
            List<String> materials,
            Set<Integer> water,
            Set<Integer> lava) {
        List<NamespacedId> materialIds = new ArrayList<>();
        materials.forEach(material -> materialIds.add(NamespacedId.parse(material)));
        return new TerrainSnapshot(
                new GridPos(0, 0, 0),
                width,
                depth,
                heights.stream().min(Integer::compareTo).orElseThrow(),
                heights.stream().max(Integer::compareTo).orElseThrow(),
                heights,
                materialIds,
                Set.of(),
                water,
                lava,
                Set.of(),
                new TerrainSnapshot.SlopeMetrics(0.5, 1.0),
                Map.of(),
                "terrain-test");
    }

    private static Set<GridPos> footprint(int width, int depth) {
        java.util.HashSet<GridPos> cells = new java.util.HashSet<>();
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                cells.add(new GridPos(x, 0, z));
            }
        }
        return Set.copyOf(cells);
    }

    private static BlockStateSpec state(String id) {
        return new BlockStateSpec(NamespacedId.parse(id), Map.of());
    }
}
