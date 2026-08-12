package dev.ssa.construction.task;

import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.construction.schedule.WorkZone;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record BuildTask(
        String id,
        GridPos position,
        TaskOperation operation,
        Optional<MaterialRequirement> materialRequirement,
        Set<String> dependencyIds,
        BuildPhase phase,
        WorkZone workZone,
        boolean optional,
        Optional<String> atomicGroupId) {
    public BuildTask {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Build task ID must not be blank");
        }
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(operation, "operation");
        materialRequirement = Objects.requireNonNull(materialRequirement, "materialRequirement");
        dependencyIds = Set.copyOf(Objects.requireNonNull(dependencyIds, "dependencyIds"));
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(workZone, "workZone");
        atomicGroupId = Objects.requireNonNull(atomicGroupId, "atomicGroupId");
        if (dependencyIds.contains(id)) {
            throw new IllegalArgumentException("A build task cannot depend on itself: " + id);
        }
        if (operation.requiresMaterial() != materialRequirement.isPresent()) {
            throw new IllegalArgumentException(
                    "Task material requirement must match operation: " + operation);
        }
        if (!workZone.contains(position)) {
            throw new IllegalArgumentException("Task position must belong to its work zone");
        }
        atomicGroupId.ifPresent(group -> {
            if (group.isBlank()) {
                throw new IllegalArgumentException("Atomic group ID must not be blank");
            }
        });
    }

    public record MaterialRequirement(MaterialRole materialRole, BlockStateSpec state) {
        public MaterialRequirement {
            Objects.requireNonNull(materialRole, "materialRole");
            Objects.requireNonNull(state, "state");
        }
    }
}
