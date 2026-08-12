package dev.ssa.architect.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.StyleId;
import dev.ssa.architect.scoring.ScoreBreakdown;
import dev.ssa.architect.terrain.TerrainPlan;
import dev.ssa.architect.validation.BlueprintValidation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class BlueprintImmutabilityTest {
    @Test
    void blueprintAndNestedValuesAreDetachedFromMutableSources() {
        Set<GridPos> dependencies = new HashSet<>(Set.of(new GridPos(0, 0, 0)));
        List<BlueprintBlock> blocks = new ArrayList<>(List.of(
                block(new GridPos(0, 0, 0), Set.of()),
                block(new GridPos(0, 1, 0), dependencies)));
        Set<GridPos> roomCells = new HashSet<>(Set.of(new GridPos(0, 1, 0)));
        List<Room> rooms = new ArrayList<>(List.of(new Room(
                "living",
                NamespacedId.parse("smart_survival_architect:living"),
                0,
                roomCells,
                Set.of())));
        Set<GridPos> footprint = new HashSet<>(Set.of(new GridPos(0, 0, 0)));

        Blueprint blueprint = blueprint(footprint, rooms, blocks, BuildPhase.canonicalOrder(), 1);

        dependencies.clear();
        blocks.clear();
        roomCells.clear();
        rooms.clear();
        footprint.clear();

        assertEquals(2, blueprint.blocks().size());
        assertEquals(Set.of(new GridPos(0, 0, 0)), blueprint.blocks().get(1).dependencies());
        assertEquals(Set.of(new GridPos(0, 1, 0)), blueprint.rooms().getFirst().cells());
        assertEquals(Set.of(new GridPos(0, 0, 0)), blueprint.footprint());
        assertThrows(UnsupportedOperationException.class, () -> blueprint.blocks().clear());
        assertThrows(UnsupportedOperationException.class, () -> blueprint.rooms().getFirst().cells().clear());
        assertThrows(UnsupportedOperationException.class, () -> blueprint.buildPhases().clear());
    }

    @Test
    void rejectsMalformedBoundsAndDuplicateOrOutOfBoundsPlacements() {
        assertThrows(IllegalArgumentException.class,
                () -> new Blueprint.LocalBounds(new GridPos(1, 0, 0), new GridPos(0, 1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> blueprint(
                        Set.of(new GridPos(0, 0, 0)),
                        List.of(),
                        List.of(block(new GridPos(2, 0, 0), Set.of())),
                        BuildPhase.canonicalOrder(),
                        1));

        BlueprintBlock placement = block(new GridPos(0, 0, 0), Set.of());
        assertThrows(IllegalArgumentException.class,
                () -> blueprint(
                        Set.of(new GridPos(0, 0, 0)),
                        List.of(),
                        List.of(placement, placement),
                        BuildPhase.canonicalOrder(),
                        1));
        assertThrows(IllegalArgumentException.class,
                () -> blueprint(
                        Set.of(new GridPos(0, 0, 0)),
                        List.of(),
                        List.of(placement),
                        List.of(BuildPhase.FOUNDATION),
                        1));
        assertThrows(IllegalArgumentException.class,
                () -> blueprint(
                        Set.of(new GridPos(0, 0, 0)),
                        List.of(),
                        List.of(placement),
                        BuildPhase.canonicalOrder(),
                        0));
    }

    @Test
    void roomAndPlacementRejectMalformedSemanticData() {
        assertThrows(IllegalArgumentException.class, () -> new Room(
                " ",
                NamespacedId.parse("smart_survival_architect:living"),
                0,
                Set.of(new GridPos(0, 0, 0)),
                Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Room(
                "living",
                NamespacedId.parse("smart_survival_architect:living"),
                -1,
                Set.of(new GridPos(0, 0, 0)),
                Set.of()));
        assertThrows(IllegalArgumentException.class, () -> block(
                new GridPos(0, 0, 0), Set.of(new GridPos(0, 0, 0))));
    }

    private static Blueprint blueprint(
            Set<GridPos> footprint,
            List<Room> rooms,
            List<BlueprintBlock> blocks,
            List<BuildPhase> phases,
            int formatVersion) {
        return new Blueprint(
                UUID.fromString("d26e7d80-38e5-447b-9acd-3f49ffca48ee"),
                77L,
                StyleId.parse("smart_survival_architect:medieval"),
                new Blueprint.LocalBounds(new GridPos(0, 0, 0), new GridPos(1, 2, 1)),
                footprint,
                1,
                rooms,
                blocks,
                phases,
                new TerrainPlan(
                        TerrainPlan.Strategy.FLAT,
                        0,
                        0,
                        0,
                        0,
                        false,
                        false,
                        TerrainPlan.SalvagePolicy.DISCARD_NO_DROPS,
                        List.of()),
                ScoreBreakdown.unscored(),
                BlueprintValidation.valid(),
                formatVersion);
    }

    private static BlueprintBlock block(GridPos position, Set<GridPos> dependencies) {
        return new BlueprintBlock(
                position,
                BlockRole.STRUCTURAL,
                MaterialRole.STRUCTURAL_WOOD,
                new BlockStateSpec(NamespacedId.parse("minecraft:oak_log"), java.util.Map.of("axis", "y")),
                BuildPhase.WALL_FRAME,
                dependencies);
    }
}
