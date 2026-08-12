package dev.ssa.architect.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.StyleId;
import dev.ssa.architect.scoring.ScoreBreakdown;
import dev.ssa.architect.terrain.TerrainPlan;
import dev.ssa.architect.validation.BlueprintValidation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class BlueprintHashTest {
    @Test
    void structuralHashIsCanonicalAcrossCollectionOrder() {
        GridPos firstPosition = new GridPos(0, 0, 0);
        GridPos secondPosition = new GridPos(1, 0, 0);
        BlueprintBlock firstBlock = block(firstPosition);
        BlueprintBlock secondBlock = block(secondPosition);
        Set<GridPos> forwardFootprint = new LinkedHashSet<>(List.of(firstPosition, secondPosition));
        Set<GridPos> reverseFootprint = new LinkedHashSet<>(List.of(secondPosition, firstPosition));

        Blueprint forward = blueprint(77L, forwardFootprint, List.of(firstBlock, secondBlock));
        Blueprint reverse = blueprint(77L, reverseFootprint, List.of(secondBlock, firstBlock));

        assertEquals(64, forward.hash().length());
        assertEquals(forward.hash(), reverse.hash());
        assertNotEquals(forward.hash(), blueprint(78L, forwardFootprint, List.of(firstBlock, secondBlock)).hash());
    }

    private static Blueprint blueprint(
            long seed,
            Set<GridPos> footprint,
            List<BlueprintBlock> blocks) {
        return new Blueprint(
                UUID.fromString("d26e7d80-38e5-447b-9acd-3f49ffca48ee"),
                seed,
                StyleId.parse("smart_survival_architect:medieval"),
                new Blueprint.LocalBounds(new GridPos(0, 0, 0), new GridPos(1, 0, 0)),
                footprint,
                1,
                List.of(),
                blocks,
                BuildPhase.canonicalOrder(),
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
                Blueprint.CURRENT_FORMAT_VERSION);
    }

    private static BlueprintBlock block(GridPos position) {
        return new BlueprintBlock(
                position,
                BlockRole.FOUNDATION,
                MaterialRole.FOUNDATION_STONE,
                new BlockStateSpec(NamespacedId.parse("minecraft:cobblestone"), Map.of()),
                BuildPhase.FOUNDATION,
                Set.of());
    }
}
