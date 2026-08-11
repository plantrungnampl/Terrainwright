package dev.ssa.architect.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.model.EntrancePreference;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.StyleId;
import dev.ssa.architect.room.RoomGraph;
import dev.ssa.architect.room.RoomGraphGenerator;
import org.junit.jupiter.api.Test;

final class FloorLayoutSolverTest {
    private final FloorLayoutSolver solver = new FloorLayoutSolver();
    private final RoomGraph graph = new RoomGraphGenerator().generate(requirements());
    private final Footprint footprint = Footprint.rectangle(15, 19);

    @Test
    void sameSeedProducesTheSameValidReachableLayout() {
        FloorLayout first = solver.solve(graph, footprint, 77L).orElseThrow();
        FloorLayout second = solver.solve(graph, footprint, 77L).orElseThrow();

        assertEquals(first, second);
        assertFalse(first.hasOverlaps());
        assertTrue(first.allRoomsReachableFromEntrance());
        assertTrue(first.realizes(graph));
        assertThrows(UnsupportedOperationException.class, () -> first.rooms().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> first.rooms().get("living").cells().clear());
    }

    @Test
    void fiveHundredSeedsRemainFeasibleAndRespectEveryHardInvariant() {
        for (long seed = 0; seed < 500; seed++) {
            long currentSeed = seed;
            FloorLayout layout = solver.solve(graph, footprint, seed)
                    .orElseThrow(() -> new AssertionError("Moderate fixture failed for seed " + currentSeed));
            assertFalse(layout.hasOverlaps(), "overlap at seed " + seed);
            assertTrue(layout.allRoomsReachableFromEntrance(), "unreachable room at seed " + seed);
            assertTrue(layout.realizes(graph), "unrealized graph edge at seed " + seed);
        }
    }

    @Test
    void returnsEmptyWhenTheFootprintCannotFitTheGraph() {
        assertTrue(solver.solve(graph, Footprint.rectangle(3, 3), 77L).isEmpty());
    }

    @Test
    void solvesTheNonRectangularV1FootprintPrimitives() {
        FloorLayout lShape = solver.solve(graph, Footprint.lShape(15, 19, 5, 6), 91L).orElseThrow();
        FloorLayout tShape = solver.solve(graph, Footprint.tShape(15, 19, 7, 6), 92L).orElseThrow();

        assertTrue(lShape.realizes(graph));
        assertTrue(tShape.realizes(graph));
    }

    private static HouseRequirements requirements() {
        return new HouseRequirements(
                StyleId.parse("smart_survival_architect:medieval"),
                15,
                19,
                2,
                2,
                true,
                true,
                true,
                false,
                EntrancePreference.AUTO,
                77L);
    }
}
