package dev.ssa.architect.blueprint;

import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import java.util.Objects;
import java.util.Set;

public record BlueprintBlock(
        GridPos relativePosition,
        BlockRole blockRole,
        MaterialRole materialRole,
        BlockStateSpec placementState,
        BuildPhase phase,
        Set<GridPos> dependencies) {
    public BlueprintBlock {
        Objects.requireNonNull(relativePosition, "relativePosition");
        Objects.requireNonNull(blockRole, "blockRole");
        Objects.requireNonNull(materialRole, "materialRole");
        Objects.requireNonNull(placementState, "placementState");
        Objects.requireNonNull(phase, "phase");
        dependencies = Set.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        if (dependencies.contains(relativePosition)) {
            throw new IllegalArgumentException("A Blueprint block cannot depend on itself");
        }
    }
}
