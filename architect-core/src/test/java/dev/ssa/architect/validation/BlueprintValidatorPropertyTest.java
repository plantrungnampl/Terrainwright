package dev.ssa.architect.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.blueprint.BlockRole;
import dev.ssa.architect.blueprint.Blueprint;
import dev.ssa.architect.blueprint.BlueprintBlock;
import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.blueprint.Room;
import dev.ssa.architect.material.BlockCapability;
import dev.ssa.architect.material.BlockCapabilityRegistry;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.StyleId;
import dev.ssa.architect.model.TerrainSnapshot;
import dev.ssa.architect.scoring.ScoreBreakdown;
import dev.ssa.architect.style.MedievalStyle;
import dev.ssa.architect.terrain.TerrainAdaptationPlanner;
import dev.ssa.architect.terrain.TerrainBudget;
import dev.ssa.architect.terrain.TerrainPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class BlueprintValidatorPropertyTest {
    private static final BlockStateSpec STONE = state("minecraft:stone", Map.of());
    private static final BlockStateSpec OAK_LOG = state("minecraft:oak_log", Map.of("axis", "y"));
    private static final BlockStateSpec ROOF = state(
            "minecraft:dark_oak_stairs",
            Map.of("facing", "north"));
    private static final Set<GridPos> FOOTPRINT = Set.of(
            new GridPos(0, 0, 0), new GridPos(1, 0, 0), new GridPos(2, 0, 0),
            new GridPos(0, 0, 1), new GridPos(1, 0, 1), new GridPos(2, 0, 1));

    private final BlueprintValidator validator = new BlueprintValidator(new MedievalStyle(), registry());

    @ParameterizedTest
    @ValueSource(longs = {1L, 2L, 3L, 10L, 99L, 1001L, 99991L})
    void validBlueprintNeverExceedsLightTerrainBudget(long seed) {
        TerrainPlan terrainPlan = terrainPlan(seed);
        Blueprint blueprint = blueprint(seed, rooms(), blocks(), terrainPlan, 1);

        assertTrue(terrainPlan.removedCount() <= 150);
        assertTrue(terrainPlan.filledCount() <= 180);
        assertTrue(terrainPlan.maxVerticalCut() <= 3);
        assertTrue(terrainPlan.maxVerticalFill() <= 4);
        assertTrue(validator.validate(blueprint).isValid());
    }

    @Test
    void rejectsUnreachableRoomsAndInvalidCrossFloorStairs() {
        List<Room> unreachable = List.of(
                room("entrance", "entrance", 0, Set.of(new GridPos(0, 1, 0)), Set.of()),
                room("living", "living", 0, Set.of(new GridPos(1, 1, 0)), Set.of()));
        assertIssue(blueprint(1L, unreachable, blocks(), flatTerrain(), 1), "UNREACHABLE_ROOM");

        List<Room> invalidStairs = List.of(
                room("entrance", "entrance", 0, Set.of(new GridPos(0, 1, 0)), Set.of("living")),
                room("living", "living", 0, Set.of(new GridPos(1, 1, 0)), Set.of("entrance", "bedroom")),
                room("bedroom", "bedroom", 1, Set.of(new GridPos(2, 5, 0)), Set.of("living")));
        assertIssue(blueprint(1L, invalidStairs, blocks(), flatTerrain(), 2), "INVALID_STAIRS");
    }

    @Test
    void rejectsAdjacentRoomsWithoutAPhysicalBoundary() {
        List<BlueprintBlock> openPlan = without(
                blocks(),
                block -> block.relativePosition().equals(new GridPos(0, 1, 0)));

        assertIssue(blueprint(1L, rooms(), openPlan, flatTerrain(), 1), "ROOM_BOUNDARY");
    }

    @Test
    void rejectsLowRailingAsAFullHeightRoomBoundary() {
        List<BlueprintBlock> openAboveRailing = replace(
                without(
                        blocks(),
                        block -> block.relativePosition().equals(new GridPos(0, 2, 0))),
                new GridPos(0, 1, 0),
                block(
                        new GridPos(0, 1, 0),
                        BlockRole.ENVELOPE,
                        MaterialRole.RAILING,
                        OAK_LOG,
                        BuildPhase.WALLS,
                        Set.of()));

        assertIssue(blueprint(1L, rooms(), openAboveRailing, flatTerrain(), 1), "ROOM_BOUNDARY");
    }

    @Test
    void rejectsRoomOpeningIntoUnassignedFootprintSpace() {
        List<BlueprintBlock> openToVoid = without(
                blocks(),
                block -> block.relativePosition().equals(new GridPos(2, 1, 0))
                        || block.relativePosition().equals(new GridPos(2, 2, 0)));

        assertIssue(blueprint(1L, rooms(), openToVoid, flatTerrain(), 1), "ROOM_BOUNDARY");
    }

    @Test
    void rejectsFoundationRoofAndDependencyFailures() {
        assertIssue(
                blueprint(1L, rooms(), without(blocks(), block ->
                        block.phase() == BuildPhase.FOUNDATION
                                && column(block.relativePosition(), 0, 0)), flatTerrain(), 1),
                "FOUNDATION_COVERAGE");
        assertIssue(
                blueprint(1L, rooms(), without(blocks(), block ->
                        block.phase() == BuildPhase.ROOF
                                && column(block.relativePosition(), 0, 0)), flatTerrain(), 1),
                "ROOF_ENVELOPE");

        List<BlueprintBlock> missingDependency = replace(
                blocks(),
                new GridPos(0, 1, 0),
                block(
                        new GridPos(0, 1, 0),
                        BlockRole.STRUCTURAL,
                        MaterialRole.STRUCTURAL_WOOD,
                        OAK_LOG,
                        BuildPhase.WALL_FRAME,
                        Set.of(new GridPos(3, 1, 0))));
        assertIssue(
                blueprint(1L, rooms(), missingDependency, flatTerrain(), 1),
                "UNRESOLVED_DEPENDENCY");

        List<BlueprintBlock> cycle = replace(
                replace(
                        blocks(),
                        new GridPos(0, 1, 0),
                        block(
                                new GridPos(0, 1, 0),
                                BlockRole.STRUCTURAL,
                                MaterialRole.STRUCTURAL_WOOD,
                                OAK_LOG,
                                BuildPhase.WALL_FRAME,
                                Set.of(new GridPos(1, 1, 0)))),
                new GridPos(1, 1, 0),
                block(
                        new GridPos(1, 1, 0),
                        BlockRole.STRUCTURAL,
                        MaterialRole.STRUCTURAL_WOOD,
                        OAK_LOG,
                        BuildPhase.WALL_FRAME,
                        Set.of(new GridPos(0, 1, 0))));
        assertIssue(blueprint(1L, rooms(), cycle, flatTerrain(), 1), "DEPENDENCY_CYCLE");

        BlueprintBlock fakeFoundation = block(
                new GridPos(0, 4, 0),
                BlockRole.STRUCTURAL,
                MaterialRole.FOUNDATION_STONE,
                STONE,
                BuildPhase.FOUNDATION,
                Set.of());
        List<BlueprintBlock> misplacedFoundation = without(blocks(), block ->
                block.phase() == BuildPhase.FOUNDATION && column(block.relativePosition(), 0, 0));
        misplacedFoundation = new ArrayList<>(misplacedFoundation);
        misplacedFoundation.add(fakeFoundation);
        assertIssue(
                blueprint(1L, rooms(), List.copyOf(misplacedFoundation), flatTerrain(), 1),
                "FOUNDATION_COVERAGE");

        BlueprintBlock floatingRoof = block(
                new GridPos(0, 7, 0),
                BlockRole.ENVELOPE,
                MaterialRole.ROOF_PRIMARY,
                ROOF,
                BuildPhase.ROOF,
                Set.of());
        assertIssue(
                blueprint(1L, rooms(), replace(blocks(), new GridPos(0, 2, 0), floatingRoof),
                        flatTerrain(), 1),
                "ROOF_SUPPORT");

        BlueprintBlock backwardFoundation = block(
                new GridPos(0, 0, 0),
                BlockRole.FOUNDATION,
                MaterialRole.FOUNDATION_STONE,
                STONE,
                BuildPhase.FOUNDATION,
                Set.of(new GridPos(0, 2, 0)));
        assertIssue(
                blueprint(1L, rooms(), replace(blocks(), new GridPos(0, 0, 0), backwardFoundation),
                        flatTerrain(), 1),
                "DEPENDENCY_PHASE_ORDER");
    }

    @Test
    void rejectsSemanticallyConnectedButGeometricallyInvalidStairs() {
        List<Room> invalidGeometry = List.of(
                room("entrance", "entrance", 0, Set.of(new GridPos(0, 1, 0)), Set.of("living")),
                room("living", "living", 0, Set.of(
                        new GridPos(0, 1, 0), new GridPos(0, 1, 1),
                        new GridPos(1, 1, 0), new GridPos(1, 1, 1)),
                        Set.of("entrance", "stairs")),
                room("stairs", "stairs", 1, Set.of(
                        new GridPos(0, 5, 0), new GridPos(0, 5, 1),
                        new GridPos(1, 5, 0), new GridPos(1, 5, 1)),
                        Set.of("living", "upper")),
                room("upper", "upper_hall", 1, Set.of(new GridPos(1, 5, 1)), Set.of("stairs")));
        List<BlueprintBlock> incompleteRun = new ArrayList<>(blocks());
        incompleteRun.add(block(
                new GridPos(0, 4, 0),
                BlockRole.STRUCTURAL,
                MaterialRole.STAIR,
                state("minecraft:oak_stairs", Map.of("facing", "north")),
                BuildPhase.STAIRS,
                Set.of()));
        assertIssue(
                blueprint(1L, invalidGeometry, List.copyOf(incompleteRun), flatTerrain(), 2),
                "INVALID_STAIRS");

        Set<GridPos> runPositions = Set.of(
                new GridPos(0, 1, 0),
                new GridPos(1, 2, 0),
                new GridPos(1, 3, 1),
                new GridPos(0, 4, 1));
        Set<GridPos> clearedPositions = new java.util.HashSet<>(runPositions);
        clearedPositions.add(new GridPos(0, 2, 0));
        List<BlueprintBlock> wrongFacingRun = new ArrayList<>(without(
                blocks(),
                block -> clearedPositions.contains(block.relativePosition())));
        runPositions.stream().sorted(java.util.Comparator.comparingInt(GridPos::y)).forEach(position ->
                wrongFacingRun.add(block(
                        position,
                        BlockRole.STRUCTURAL,
                        MaterialRole.STAIR,
                        state("minecraft:oak_stairs", Map.of("facing", "north")),
                        BuildPhase.STAIRS,
                        Set.of())));
        assertIssue(
                blueprint(1L, invalidGeometry, List.copyOf(wrongFacingRun), flatTerrain(), 2),
                "INVALID_STAIRS");
    }

    @Test
    void rejectsUnsupportedStatesAndMissingStyleCapability() {
        BlueprintBlock invalidFacing = block(
                new GridPos(0, 3, 0),
                BlockRole.ENVELOPE,
                MaterialRole.ROOF_PRIMARY,
                state("minecraft:dark_oak_stairs", Map.of("facing", "up")),
                BuildPhase.ROOF,
                Set.of());
        assertIssue(
                blueprint(1L, rooms(), replace(blocks(), invalidFacing.relativePosition(), invalidFacing),
                        flatTerrain(), 1),
                "UNSUPPORTED_BLOCK_STATE");

        BlueprintBlock wrongCapability = block(
                new GridPos(0, 3, 0),
                BlockRole.ENVELOPE,
                MaterialRole.ROOF_PRIMARY,
                STONE,
                BuildPhase.ROOF,
                Set.of());
        assertIssue(
                blueprint(1L, rooms(), replace(blocks(), wrongCapability.relativePosition(), wrongCapability),
                        flatTerrain(), 1),
                "MISSING_MATERIAL_CAPABILITY");
    }

    @Test
    void rejectsLiquidUnsafeRemovalAndTerrainBudgetViolations() {
        assertIssue(
                blueprint(1L, rooms(), blocks(), new TerrainPlan(
                        TerrainPlan.Strategy.FLAT, 0, 0, 0, 0, true, false,
                        TerrainPlan.SalvagePolicy.DISCARD_NO_DROPS, List.of()), 1),
                "WATER_MODIFICATION");
        assertIssue(
                blueprint(1L, rooms(), blocks(), new TerrainPlan(
                        TerrainPlan.Strategy.FLAT, 0, 0, 0, 0, false, true,
                        TerrainPlan.SalvagePolicy.DISCARD_NO_DROPS, List.of()), 1),
                "LAVA_MODIFICATION");
        assertIssue(
                blueprint(1L, rooms(), blocks(), cutPlan(4, "minecraft:dirt"), 1),
                "TERRAIN_BUDGET");

        TerrainPlan unsafe = new TerrainPlan(
                TerrainPlan.Strategy.CUT,
                1,
                0,
                1,
                0,
                false,
                false,
                TerrainPlan.SalvagePolicy.DISCARD_NO_DROPS,
                List.of(new TerrainPlan.TerrainCellChange(
                        new GridPos(0, 1, 0),
                        state("minecraft:chest", Map.of()),
                        state("minecraft:air", Map.of()),
                        TerrainPlan.DropPolicy.SUPPRESS,
                        TerrainPlan.XpPolicy.SUPPRESS)));
        assertIssue(blueprint(1L, rooms(), blocks(), unsafe, 1), "UNSAFE_TERRAIN_REMOVAL");

        TerrainPlan unsafeFill = new TerrainPlan(
                TerrainPlan.Strategy.FILL,
                0,
                1,
                0,
                1,
                false,
                false,
                TerrainPlan.SalvagePolicy.DISCARD_NO_DROPS,
                List.of(new TerrainPlan.TerrainCellChange(
                        new GridPos(0, 1, 0),
                        state("minecraft:air", Map.of()),
                        state("minecraft:chest", Map.of()),
                        TerrainPlan.DropPolicy.SUPPRESS,
                        TerrainPlan.XpPolicy.SUPPRESS)));
        assertIssue(blueprint(1L, rooms(), blocks(), unsafeFill, 1), "UNSUPPORTED_TERRAIN_FILL");
    }

    private void assertIssue(Blueprint blueprint, String code) {
        BlueprintValidation validation = validator.validate(blueprint);
        assertFalse(validation.isValid());
        assertTrue(validation.issues().stream().anyMatch(issue -> issue.code().equals(code)),
                () -> "Expected issue " + code + " but found " + validation.issues());
    }

    private static Blueprint blueprint(
            long seed,
            List<Room> rooms,
            List<BlueprintBlock> blocks,
            TerrainPlan terrainPlan,
            int floors) {
        return new Blueprint(
                UUID.nameUUIDFromBytes(("blueprint-" + seed).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                seed,
                StyleId.parse("smart_survival_architect:medieval"),
                new Blueprint.LocalBounds(new GridPos(-1, 0, -1), new GridPos(3, 8, 2)),
                FOOTPRINT,
                floors,
                rooms,
                blocks,
                BuildPhase.canonicalOrder(),
                terrainPlan,
                ScoreBreakdown.unscored(),
                BlueprintValidation.valid(),
                Blueprint.CURRENT_FORMAT_VERSION);
    }

    private static List<Room> rooms() {
        return List.of(
                room("entrance", "entrance", 0, Set.of(new GridPos(0, 1, 0)), Set.of("living")),
                room("living", "living", 0, Set.of(new GridPos(1, 1, 0)), Set.of("entrance")));
    }

    private static Room room(
            String id,
            String type,
            int floor,
            Set<GridPos> cells,
            Set<String> connections) {
        return new Room(
                id,
                NamespacedId.parse("smart_survival_architect:" + type),
                floor,
                cells,
                connections);
    }

    private static List<BlueprintBlock> blocks() {
        List<BlueprintBlock> blocks = new ArrayList<>();
        for (GridPos cell : FOOTPRINT) {
            blocks.add(block(
                    cell,
                    BlockRole.FOUNDATION,
                    MaterialRole.FOUNDATION_STONE,
                    STONE,
                    BuildPhase.FOUNDATION,
                    Set.of()));
            blocks.add(block(
                    new GridPos(cell.x(), 1, cell.z()),
                    BlockRole.STRUCTURAL,
                    MaterialRole.STRUCTURAL_WOOD,
                    OAK_LOG,
                    BuildPhase.WALL_FRAME,
                    Set.of()));
            blocks.add(block(
                    new GridPos(cell.x(), 2, cell.z()),
                    BlockRole.ENVELOPE,
                    MaterialRole.ROOF_PRIMARY,
                    ROOF,
                    BuildPhase.ROOF,
                    Set.of(new GridPos(cell.x(), 1, cell.z()))));
        }
        return List.copyOf(blocks);
    }

    private static BlueprintBlock block(
            GridPos position,
            BlockRole blockRole,
            MaterialRole materialRole,
            BlockStateSpec state,
            BuildPhase phase,
            Set<GridPos> dependencies) {
        return new BlueprintBlock(position, blockRole, materialRole, state, phase, dependencies);
    }

    private static List<BlueprintBlock> without(
            List<BlueprintBlock> blocks,
            Predicate<BlueprintBlock> predicate) {
        return blocks.stream().filter(predicate.negate()).toList();
    }

    private static List<BlueprintBlock> replace(
            List<BlueprintBlock> blocks,
            GridPos position,
            BlueprintBlock replacement) {
        List<BlueprintBlock> copy = new ArrayList<>(blocks);
        copy.removeIf(block -> block.relativePosition().equals(position));
        copy.add(replacement);
        return List.copyOf(copy);
    }

    private static boolean column(GridPos position, int x, int z) {
        return position.x() == x && position.z() == z;
    }

    private static TerrainPlan terrainPlan(long seed) {
        Random random = new Random(seed);
        List<Integer> heights = new ArrayList<>();
        for (int ignored = 0; ignored < FOOTPRINT.size(); ignored++) {
            heights.add(10 + random.nextInt(3));
        }
        TerrainSnapshot snapshot = new TerrainSnapshot(
                new GridPos(0, 0, 0),
                3,
                2,
                10,
                12,
                heights,
                List.of(
                        id("minecraft:grass_block"), id("minecraft:dirt"), id("minecraft:stone"),
                        id("minecraft:grass_block"), id("minecraft:dirt"), id("minecraft:stone")),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                new TerrainSnapshot.SlopeMetrics(0.5, 1.0),
                Map.of(),
                "seed-" + seed);
        return new TerrainAdaptationPlanner()
                .plan(snapshot, FOOTPRINT, state("minecraft:dirt", Map.of()), TerrainBudget.light())
                .orElseThrow();
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

    private static TerrainPlan cutPlan(int depth, String material) {
        List<TerrainPlan.TerrainCellChange> changes = new ArrayList<>();
        for (int y = 1; y <= depth; y++) {
            changes.add(new TerrainPlan.TerrainCellChange(
                    new GridPos(0, y, 0),
                    state(material, Map.of()),
                    state("minecraft:air", Map.of()),
                    TerrainPlan.DropPolicy.SUPPRESS,
                    TerrainPlan.XpPolicy.SUPPRESS));
        }
        return new TerrainPlan(
                TerrainPlan.Strategy.CUT,
                depth,
                0,
                depth,
                0,
                false,
                false,
                TerrainPlan.SalvagePolicy.DISCARD_NO_DROPS,
                changes);
    }

    private static BlockCapabilityRegistry registry() {
        return BlockCapabilityRegistry.of(Map.of(
                id("minecraft:stone"), Set.of(BlockCapability.FULL_CUBE),
                id("minecraft:dirt"), Set.of(BlockCapability.FULL_CUBE),
                id("minecraft:oak_log"), Set.of(BlockCapability.ORIENTABLE_AXIS),
                id("minecraft:oak_stairs"),
                Set.of(BlockCapability.STAIR, BlockCapability.HORIZONTAL_FACING),
                id("minecraft:dark_oak_stairs"),
                Set.of(BlockCapability.STAIR, BlockCapability.HORIZONTAL_FACING)));
    }

    private static BlockStateSpec state(String id, Map<String, String> properties) {
        return new BlockStateSpec(NamespacedId.parse(id), properties);
    }

    private static NamespacedId id(String value) {
        return NamespacedId.parse(value);
    }
}
