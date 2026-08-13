package dev.ssa.construction.scaffold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ScaffoldPlanTest {
    private static final BlockStateSpec SCAFFOLD = new BlockStateSpec(
            NamespacedId.parse("minecraft:scaffolding"), Map.of());

    @Test
    void acceptsAPlanWithinTheBoundedLimits() {
        ScaffoldPlan plan = new ScaffoldPlan(List.of(
                placement(0, 64, 0),
                placement(0, 76, 0)));

        assertEquals(2, plan.placements().size());
        assertEquals(12, plan.height());
    }

    @Test
    void rejectsMoreThanTwentyFourTemporaryBlocks() {
        List<ScaffoldPlan.Placement> placements = java.util.stream.IntStream.range(0, 25)
                .mapToObj(x -> placement(x, 64, 0))
                .toList();

        assertThrows(IllegalArgumentException.class, () -> new ScaffoldPlan(placements));
    }

    @Test
    void rejectsAPlanHigherThanTwelveBlocks() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScaffoldPlan(List.of(placement(0, 64, 0), placement(0, 77, 0))));
    }

    @Test
    void rejectsDuplicateTemporaryPositions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScaffoldPlan(List.of(placement(0, 64, 0), placement(0, 64, 0))));
    }

    private static ScaffoldPlan.Placement placement(int x, int y, int z) {
        return new ScaffoldPlan.Placement(new GridPos(x, y, z), SCAFFOLD);
    }
}
