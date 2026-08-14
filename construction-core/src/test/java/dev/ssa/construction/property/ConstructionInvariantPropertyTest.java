package dev.ssa.construction.property;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.ArchitectEngine;
import dev.ssa.architect.ArchitectEngine.GenerationResult;
import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.material.BlockCapability;
import dev.ssa.architect.material.BlockCapabilityRegistry;
import dev.ssa.architect.model.EntrancePreference;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.TerrainSnapshot;
import dev.ssa.architect.style.JapaneseStyle;
import dev.ssa.architect.style.MedievalStyle;
import dev.ssa.architect.style.ModernStyle;
import dev.ssa.architect.style.StylePack;
import dev.ssa.architect.terrain.TerrainPlan;
import dev.ssa.construction.plan.ConstructionPlanner;
import dev.ssa.construction.plan.TaskGraph;
import dev.ssa.construction.task.BuildTask;
import dev.ssa.construction.task.TaskOperation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ConstructionInvariantPropertyTest {
    private static final List<StylePack> STYLES = List.of(
            new MedievalStyle(), new JapaneseStyle(), new ModernStyle());

    @Test
    void generatedPlansRemainAcyclicPhaseOrderedAndNoDropAcrossSeededFixtures() {
        ArchitectEngine engine = new ArchitectEngine();
        ConstructionPlanner planner = new ConstructionPlanner();
        TerrainSnapshot terrain = gentleSlope(17, 17);
        for (StylePack style : STYLES) {
            BlockCapabilityRegistry registry = registry(style);
            for (long seed = 0; seed < 6; seed++) {
                String label = style.id() + " seed=" + seed;
                HouseRequirements requirements = new HouseRequirements(
                        style.id(),
                        13,
                        13,
                        2,
                        2,
                        true,
                        true,
                        false,
                        false,
                        EntrancePreference.AUTO,
                        seed);
                GenerationResult.Success generated = assertInstanceOf(
                        GenerationResult.Success.class,
                        engine.generate(requirements, terrain, style, registry),
                        label);
                assertEquals(
                        TerrainPlan.SalvagePolicy.DISCARD_NO_DROPS,
                        generated.blueprint().terrainPlan().salvagePolicy(),
                        label);
                assertFalse(generated.blueprint().terrainPlan().modifyWater(), label);
                assertFalse(generated.blueprint().terrainPlan().modifyLava(), label);

                TaskGraph graph = planner.plan(generated.blueprint());
                assertDependenciesRespectPhaseAndDecorationBoundary(graph, label);
                assertEveryTaskCompletesThroughTheFrontier(graph, label);
                graph.tasks().values().stream()
                        .filter(task -> task.phase() == BuildPhase.SITE_PREPARATION)
                        .filter(task -> task.operation() == TaskOperation.REMOVE)
                        .forEach(task -> assertTrue(task.materialRequirement().isEmpty(), label));
            }
        }
    }

    private static void assertDependenciesRespectPhaseAndDecorationBoundary(TaskGraph graph, String label) {
        List<BuildPhase> phases = BuildPhase.canonicalOrder();
        for (BuildTask task : graph.tasks().values()) {
            for (String dependencyId : task.dependencyIds()) {
                BuildTask dependency = graph.task(dependencyId);
                assertTrue(
                        phases.indexOf(dependency.phase()) <= phases.indexOf(task.phase()),
                        label + " phase inversion " + dependencyId + " -> " + task.id());
                if (task.phase() != BuildPhase.DECORATION) {
                    assertTrue(
                            dependency.phase() != BuildPhase.DECORATION,
                            label + " structural task depends on decoration: " + task.id());
                }
            }
        }
    }

    private static void assertEveryTaskCompletesThroughTheFrontier(TaskGraph graph, String label) {
        TaskGraph.Frontier frontier = graph.frontier(Set.of());
        int iterations = 0;
        while (frontier.completedTaskIds().size() < graph.tasks().size()) {
            Set<String> eligible = frontier.eligibleTaskIds();
            assertFalse(eligible.isEmpty(), label + " deadlocked task frontier");
            frontier.complete(eligible.iterator().next());
            assertTrue(++iterations <= graph.tasks().size(), label + " frontier exceeded task bound");
        }
        assertEquals(graph.tasks().keySet(), frontier.completedTaskIds(), label);
    }

    private static TerrainSnapshot gentleSlope(int width, int depth) {
        List<Integer> heights = new ArrayList<>(width * depth);
        List<NamespacedId> materials = new ArrayList<>(width * depth);
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                heights.add(x < width / 2 ? 10 : 11);
                materials.add(NamespacedId.parse("minecraft:grass_block"));
            }
        }
        return new TerrainSnapshot(
                new GridPos(0, 10, 0),
                width,
                depth,
                10,
                11,
                heights,
                materials,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                new TerrainSnapshot.SlopeMetrics(0.1, 1.0),
                Map.of(),
                "construction-property-" + width + "x" + depth);
    }

    private static BlockCapabilityRegistry registry(StylePack style) {
        Map<NamespacedId, Set<BlockCapability>> entries = new HashMap<>();
        style.fallbackPalette().values().forEach(candidates -> candidates.forEach(candidate -> entries
                .computeIfAbsent(candidate.state().blockId(), ignored -> new HashSet<>())
                .addAll(candidate.requiredCapabilities())));
        return BlockCapabilityRegistry.of(entries);
    }
}
