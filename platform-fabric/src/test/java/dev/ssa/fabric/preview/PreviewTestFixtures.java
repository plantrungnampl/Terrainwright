package dev.ssa.fabric.preview;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PreviewTestFixtures {
    private PreviewTestFixtures() {}

    public static Blueprint blueprint(long seed) {
        GridPos position = new GridPos(0, 0, 0);
        BlueprintBlock block = new BlueprintBlock(
                position,
                BlockRole.FOUNDATION,
                MaterialRole.FOUNDATION_STONE,
                new BlockStateSpec(NamespacedId.parse("minecraft:cobblestone"), Map.of()),
                BuildPhase.FOUNDATION,
                Set.of());
        return new Blueprint(
                UUID.nameUUIDFromBytes(("preview:" + seed).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                seed,
                StyleId.parse("smart_survival_architect:medieval"),
                new Blueprint.LocalBounds(position, position),
                Set.of(position),
                1,
                List.of(),
                List.of(block),
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
}
