package dev.ssa.construction.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.blueprint.BlockRole;
import dev.ssa.architect.blueprint.Blueprint;
import dev.ssa.architect.blueprint.BlueprintBlock;
import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.StyleId;
import dev.ssa.architect.scoring.ScoreBreakdown;
import dev.ssa.architect.terrain.TerrainPlan;
import dev.ssa.architect.validation.BlueprintValidation;
import dev.ssa.construction.material.WorkBatchPlanner;
import dev.ssa.construction.schedule.WorkZone;
import dev.ssa.construction.task.BuildTask;
import dev.ssa.construction.task.TaskOperation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ConstructionPlannerTest {
    private static final BlockStateSpec STONE = state("minecraft:stone");
    private static final BlockStateSpec OAK_LOG = state("minecraft:oak_log");
    private static final BlockStateSpec ROOF = state("minecraft:dark_oak_stairs");
    private final ConstructionPlanner planner = new ConstructionPlanner();

    @Test
    void roofTaskCannotBecomeEligibleBeforeItsSupportChain() {
        GridPos foundation = new GridPos(0, 0, 0);
        GridPos column = new GridPos(0, 1, 0);
        GridPos beam = new GridPos(0, 2, 0);
        GridPos roof = new GridPos(0, 3, 0);
        TaskGraph graph = planner.plan(blueprint(
                List.of(
                        block(foundation, BuildPhase.FOUNDATION, MaterialRole.FOUNDATION_STONE, STONE, Set.of()),
                        block(column, BuildPhase.WALL_FRAME, MaterialRole.STRUCTURAL_WOOD, OAK_LOG,
                                Set.of(foundation)),
                        block(beam, BuildPhase.WALL_FRAME, MaterialRole.STRUCTURAL_WOOD, OAK_LOG,
                                Set.of(column)),
                        block(roof, BuildPhase.ROOF, MaterialRole.ROOF_PRIMARY, ROOF, Set.of(beam))),
                flatTerrain(),
                new Blueprint.LocalBounds(foundation, roof)));

        String foundationId = ConstructionPlanner.blockTaskId(foundation);
        String columnId = ConstructionPlanner.blockTaskId(column);
        String beamId = ConstructionPlanner.blockTaskId(beam);
        String roofId = ConstructionPlanner.blockTaskId(roof);
        assertEquals(Set.of(foundationId), graph.eligibleTaskIds(Set.of()));
        assertFalse(graph.eligibleTaskIds(Set.of(foundationId, columnId)).contains(roofId));
        assertTrue(graph.eligibleTaskIds(Set.of(foundationId, columnId, beamId)).contains(roofId));
    }

    @Test
    void frontierUnlocksOnlyCompletedTasksDirectDependents() {
        BuildTask root = task("root", new GridPos(0, 0, 0), Set.of());
        BuildTask child = task("child", new GridPos(0, 1, 0), Set.of("root"));
        BuildTask grandchild = task("grandchild", new GridPos(0, 2, 0), Set.of("child"));
        BuildTask independent = task("independent", new GridPos(1, 0, 0), Set.of());
        TaskGraph.Frontier frontier = new TaskGraph(List.of(root, child, grandchild, independent))
                .frontier(Set.of());

        assertEquals(Set.of("independent", "root"), frontier.eligibleTaskIds());
        assertEquals(Set.of("child", "independent"), frontier.complete("root"));
        assertFalse(frontier.eligibleTaskIds().contains("grandchild"));
        assertEquals(Set.of("grandchild", "independent"), frontier.complete("child"));
        assertThrows(IllegalArgumentException.class, () -> frontier.complete("root"));
    }

    @Test
    void graphRejectsDuplicateMissingAndCyclicDependencies() {
        BuildTask a = task("a", new GridPos(0, 0, 0), Set.of("b"));
        BuildTask b = task("b", new GridPos(1, 0, 0), Set.of("a"));

        assertThrows(IllegalArgumentException.class, () -> new TaskGraph(List.of(a, a)));
        assertThrows(IllegalArgumentException.class, () -> new TaskGraph(List.of(
                task("missing", new GridPos(2, 0, 0), Set.of("unknown")))));
        assertThrows(IllegalArgumentException.class, () -> new TaskGraph(List.of(a, b)));
        assertThrows(IllegalArgumentException.class, () ->
                new TaskGraph(List.of(task("root", new GridPos(0, 0, 0), Set.of())))
                        .frontier(Set.of("unknown")));
    }

    @Test
    void plannerMakesBlueprintPlacementWaitForTerrainAtSamePosition() {
        GridPos position = new GridPos(0, 0, 0);
        TerrainPlan terrain = new TerrainPlan(
                TerrainPlan.Strategy.FILL,
                0,
                1,
                0,
                1,
                false,
                false,
                TerrainPlan.SalvagePolicy.DISCARD_NO_DROPS,
                List.of(change(position, state("minecraft:air"), state("minecraft:dirt"))));
        Blueprint blueprint = blueprint(
                List.of(block(
                        position,
                        BuildPhase.FOUNDATION,
                        MaterialRole.FOUNDATION_STONE,
                        STONE,
                        Set.of())),
                terrain,
                new Blueprint.LocalBounds(position, position));

        TaskGraph graph = planner.plan(blueprint);
        BuildTask terrainTask = graph.task(ConstructionPlanner.terrainTaskId(position));
        BuildTask blockTask = graph.task(ConstructionPlanner.blockTaskId(position));

        assertEquals(TaskOperation.PLACE, terrainTask.operation());
        assertEquals(MaterialRole.FOUNDATION_FILL,
                terrainTask.materialRequirement().orElseThrow().materialRole());
        assertEquals(Set.of(terrainTask.id()), blockTask.dependencyIds());
    }

    @Test
    void terrainRemovalNeedsNoMaterial() {
        GridPos position = new GridPos(0, 0, 0);
        TerrainPlan terrain = new TerrainPlan(
                TerrainPlan.Strategy.CUT,
                1,
                0,
                1,
                0,
                false,
                false,
                TerrainPlan.SalvagePolicy.DISCARD_NO_DROPS,
                List.of(change(position, STONE, state("minecraft:air"))));
        TaskGraph graph = planner.plan(blueprint(
                List.of(),
                terrain,
                new Blueprint.LocalBounds(position, position)));
        BuildTask removal = graph.task(ConstructionPlanner.terrainTaskId(position));

        assertEquals(TaskOperation.REMOVE, removal.operation());
        assertTrue(removal.materialRequirement().isEmpty());
        assertEquals(BuildPhase.SITE_PREPARATION, removal.phase());
    }

    @Test
    void workZonesAreStableFiveByFiveHorizontalCells() {
        WorkZone origin = WorkZone.containing(new GridPos(4, 99, 4));

        assertEquals(origin, WorkZone.containing(new GridPos(0, -20, 0)));
        assertEquals("zone:0:0", origin.id());
        assertEquals(0, origin.minimumX());
        assertEquals(5, origin.maximumXExclusive());
        assertEquals("zone:1:0", WorkZone.containing(new GridPos(5, 0, 0)).id());
        assertEquals("zone:-1:-1", WorkZone.containing(new GridPos(-1, 0, -1)).id());
    }

    @Test
    void materialBatchChoosesOneZoneAndCountsExactRequirements() {
        BuildTask firstStone = task("stone-1", new GridPos(0, 0, 0), Set.of());
        BuildTask secondStone = task("stone-2", new GridPos(1, 0, 0), Set.of());
        BuildTask otherZone = new BuildTask(
                "wood",
                new GridPos(5, 0, 0),
                TaskOperation.PLACE,
                Optional.of(new BuildTask.MaterialRequirement(MaterialRole.STRUCTURAL_WOOD, OAK_LOG)),
                Set.of(),
                BuildPhase.WALL_FRAME,
                WorkZone.containing(new GridPos(5, 0, 0)),
                false,
                Optional.empty());
        WorkBatchPlanner.WorkBatch batch = new WorkBatchPlanner()
                .plan(new TaskGraph(List.of(otherZone, secondStone, firstStone)).frontier(Set.of()), 2, 2)
                .orElseThrow();

        assertEquals("zone:0:0", batch.workZone().id());
        assertEquals(List.of("stone-1", "stone-2"), batch.taskIds());
        assertEquals(Map.of(
                new BuildTask.MaterialRequirement(MaterialRole.FOUNDATION_STONE, STONE), 2),
                batch.materialCounts());
    }

    @Test
    void materialBatchNeverSplitsAnAtomicGroup() {
        BuildTask first = atomicTask("door-lower", new GridPos(0, 0, 0), "door-1");
        BuildTask second = atomicTask("door-upper", new GridPos(0, 1, 0), "door-1");
        WorkBatchPlanner batchPlanner = new WorkBatchPlanner();
        TaskGraph.Frontier frontier = new TaskGraph(List.of(first, second)).frontier(Set.of());

        assertTrue(batchPlanner.plan(frontier, 2, 1).isEmpty());
        assertEquals(
                List.of("door-lower", "door-upper"),
                batchPlanner.plan(frontier, 2, 2).orElseThrow().taskIds());
    }

    @Test
    void frontierCompletesAnAtomicGroupAsOneUnit() {
        BuildTask first = atomicTask("door-lower", new GridPos(0, 0, 0), "door-1");
        BuildTask second = atomicTask("door-upper", new GridPos(0, 1, 0), "door-1");
        BuildTask dependent = task(
                "after-door",
                new GridPos(1, 0, 0),
                Set.of("door-lower", "door-upper"));
        TaskGraph.Frontier frontier = new TaskGraph(List.of(first, second, dependent))
                .frontier(Set.of());

        assertEquals(Set.of("door-lower", "door-upper"), frontier.eligibleTaskIds());
        assertEquals(Set.of("after-door"), frontier.complete("door-lower"));
        assertEquals(Set.of("door-lower", "door-upper"), frontier.completedTaskIds());
        assertThrows(IllegalArgumentException.class, () -> frontier.complete("door-upper"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskGraph(List.of(first, second)).frontier(Set.of("door-lower")));
    }

    @Test
    void plannerMakesBothHalvesOfADoorEligibleAsOneAtomicGroup() {
        GridPos lower = new GridPos(0, 0, 0);
        GridPos upper = new GridPos(0, 1, 0);
        BlockStateSpec lowerDoor = new BlockStateSpec(
                NamespacedId.parse("minecraft:oak_door"), Map.of("half", "lower"));
        BlockStateSpec upperDoor = new BlockStateSpec(
                NamespacedId.parse("minecraft:oak_door"), Map.of("half", "upper"));
        TaskGraph graph = planner.plan(blueprint(
                List.of(
                        block(lower, BuildPhase.WINDOWS_DOORS, MaterialRole.DOOR, lowerDoor, Set.of()),
                        block(upper, BuildPhase.WINDOWS_DOORS, MaterialRole.DOOR, upperDoor, Set.of(lower))),
                flatTerrain(),
                new Blueprint.LocalBounds(lower, upper)));
        Set<String> eligible = graph.eligibleTaskIds(Set.of());
        TaskGraph.Frontier frontier = graph.frontier(Set.of());

        assertEquals(Set.of(
                ConstructionPlanner.blockTaskId(lower),
                ConstructionPlanner.blockTaskId(upper)), eligible);
        assertTrue(new WorkBatchPlanner().plan(frontier, 2, 1).isEmpty());
        assertEquals(2, new WorkBatchPlanner()
                .plan(frontier, 2, 2)
                .orElseThrow()
                .taskIds()
                .size());
    }

    @Test
    void taskOperationsMatchCanonicalConstructionContract() {
        assertEquals(
                List.of("REMOVE", "PLACE", "REPLACE", "TEMP_PLACE", "TEMP_REMOVE"),
                List.of(TaskOperation.values()).stream().map(Enum::name).toList());
    }

    private static BuildTask task(String id, GridPos position, Set<String> dependencies) {
        return new BuildTask(
                id,
                position,
                TaskOperation.PLACE,
                Optional.of(new BuildTask.MaterialRequirement(MaterialRole.FOUNDATION_STONE, STONE)),
                dependencies,
                BuildPhase.FOUNDATION,
                WorkZone.containing(position),
                false,
                Optional.empty());
    }

    private static BuildTask atomicTask(String id, GridPos position, String atomicGroupId) {
        return new BuildTask(
                id,
                position,
                TaskOperation.PLACE,
                Optional.of(new BuildTask.MaterialRequirement(MaterialRole.DOOR, state("minecraft:oak_door"))),
                Set.of(),
                BuildPhase.WINDOWS_DOORS,
                WorkZone.containing(position),
                false,
                Optional.of(atomicGroupId));
    }

    private static Blueprint blueprint(
            List<BlueprintBlock> blocks,
            TerrainPlan terrainPlan,
            Blueprint.LocalBounds bounds) {
        return new Blueprint(
                UUID.fromString("70c6314a-eb2f-4a84-b96f-2de13cb13116"),
                91L,
                StyleId.parse("smart_survival_architect:medieval"),
                bounds,
                Set.of(new GridPos(bounds.minimum().x(), bounds.minimum().y(), bounds.minimum().z())),
                1,
                List.of(),
                blocks,
                BuildPhase.canonicalOrder(),
                terrainPlan,
                ScoreBreakdown.unscored(),
                BlueprintValidation.valid(),
                Blueprint.CURRENT_FORMAT_VERSION);
    }

    private static BlueprintBlock block(
            GridPos position,
            BuildPhase phase,
            MaterialRole materialRole,
            BlockStateSpec state,
            Set<GridPos> dependencies) {
        return new BlueprintBlock(
                position,
                phase == BuildPhase.FOUNDATION ? BlockRole.FOUNDATION : BlockRole.STRUCTURAL,
                materialRole,
                state,
                phase,
                dependencies);
    }

    private static TerrainPlan flatTerrain() {
        return new TerrainPlan(
                TerrainPlan.Strategy.FLAT,
                0,
                0,
                0,
                0,
                false,
                false,
                TerrainPlan.SalvagePolicy.DISCARD_NO_DROPS,
                List.of());
    }

    private static TerrainPlan.TerrainCellChange change(
            GridPos position,
            BlockStateSpec before,
            BlockStateSpec after) {
        return new TerrainPlan.TerrainCellChange(
                position,
                before,
                after,
                TerrainPlan.DropPolicy.SUPPRESS,
                TerrainPlan.XpPolicy.SUPPRESS);
    }

    private static BlockStateSpec state(String id) {
        return new BlockStateSpec(NamespacedId.parse(id), Map.of());
    }
}
