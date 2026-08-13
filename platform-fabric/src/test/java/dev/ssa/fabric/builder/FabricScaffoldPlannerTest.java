package dev.ssa.fabric.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.model.GridPos;
import dev.ssa.construction.scaffold.ScaffoldPlan;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class FabricScaffoldPlannerTest {
    @Test
    void createsABoundedColumnFromTheFirstAirBlockToInteractionHeight() {
        Optional<ScaffoldPlan> plan = FabricScaffoldPlanner.column(
                new GridPos(4, 65, 7),
                new GridPos(4, 69, 7));

        assertTrue(plan.isPresent());
        assertEquals(5, plan.orElseThrow().placements().size());
        assertEquals(new GridPos(4, 65, 7), plan.orElseThrow().placements().getFirst().position());
        assertEquals(new GridPos(4, 69, 7), plan.orElseThrow().placements().getLast().position());
        assertEquals("0", plan.orElseThrow().placements().getFirst().state().properties().get("distance"));
        assertEquals("false", plan.orElseThrow().placements().getFirst().state().properties().get("bottom"));
    }

    @Test
    void refusesAColumnAboveTheV1HeightBudget() {
        assertTrue(FabricScaffoldPlanner.column(
                new GridPos(4, 65, 7),
                new GridPos(4, 78, 7)).isEmpty());
    }

    @Test
    void refusesNonVerticalInput() {
        assertTrue(FabricScaffoldPlanner.column(
                new GridPos(4, 65, 7),
                new GridPos(5, 69, 7)).isEmpty());
    }
}
