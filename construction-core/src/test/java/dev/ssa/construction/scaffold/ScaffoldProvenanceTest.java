package dev.ssa.construction.scaffold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ScaffoldProvenanceTest {
    private static final BlockStateSpec SCAFFOLD = new BlockStateSpec(
            NamespacedId.parse("minecraft:scaffolding"), Map.of());

    @Test
    void recordsPlacementAndCleanupIdempotently() {
        ScaffoldProvenance planned = ScaffoldProvenance.planned("job-1", "plan-1", "task-1", plan());

        ScaffoldProvenance placed = planned.recordPlaced(0, "place-0");
        assertTrue(placed.cells().getFirst().isPlaced());
        assertEquals(1, placed.revision());
        assertEquals(placed, placed.recordPlaced(0, "place-0"));

        ScaffoldProvenance removed = placed.recordRemoved(0, "remove-0");
        assertFalse(removed.cells().getFirst().isPlaced());
        assertTrue(removed.cells().getFirst().isCleaned());
        assertEquals(2, removed.revision());
        assertEquals(removed, removed.recordRemoved(0, "remove-0"));
    }

    @Test
    void rejectsConflictingOperationEvidence() {
        ScaffoldProvenance placed = ScaffoldProvenance.planned("job-1", "plan-1", "task-1", plan())
                .recordPlaced(0, "place-0");

        assertThrows(IllegalStateException.class, () -> placed.recordPlaced(0, "different-place"));
        assertThrows(IllegalStateException.class, () -> placed.recordRemoved(1, "remove-before-place"));
    }

    @Test
    void completesOnlyAfterEveryPlacedCellIsCleaned() {
        ScaffoldProvenance provenance = ScaffoldProvenance.planned("job-1", "plan-1", "task-1", plan());
        for (int index = 0; index < provenance.cells().size(); index++) {
            provenance = provenance.recordPlaced(index, "place-" + index);
        }
        assertFalse(provenance.isCleaned());
        for (int index = provenance.cells().size() - 1; index >= 0; index--) {
            provenance = provenance.recordRemoved(index, "remove-" + index);
        }
        assertTrue(provenance.isCleaned());
    }

    @Test
    void persistedCellsRetainThePlanBoundsAndUniquePositions() {
        ScaffoldProvenance.Cell first = cell(new GridPos(2, 64, 3));

        assertThrows(IllegalArgumentException.class, () -> new ScaffoldProvenance(
                "job-1", "plan-1", "task-1", List.of(first, first), 0));
        assertThrows(IllegalArgumentException.class, () -> new ScaffoldProvenance(
                "job-1",
                "plan-1",
                "task-1",
                List.of(first, cell(new GridPos(2, 77, 3))),
                0));
    }

    private static ScaffoldProvenance.Cell cell(GridPos position) {
        return new ScaffoldProvenance.Cell(position, SCAFFOLD, Optional.empty(), Optional.empty());
    }

    private static ScaffoldPlan plan() {
        return new ScaffoldPlan(List.of(
                new ScaffoldPlan.Placement(new GridPos(2, 64, 3), SCAFFOLD),
                new ScaffoldPlan.Placement(new GridPos(2, 65, 3), SCAFFOLD)));
    }
}
