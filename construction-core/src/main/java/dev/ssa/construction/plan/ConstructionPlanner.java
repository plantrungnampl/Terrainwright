package dev.ssa.construction.plan;

import dev.ssa.architect.blueprint.Blueprint;
import dev.ssa.architect.blueprint.BlueprintBlock;
import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.terrain.TerrainPlan;
import dev.ssa.construction.schedule.WorkZone;
import dev.ssa.construction.task.BuildTask;
import dev.ssa.construction.task.TaskOperation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ConstructionPlanner {
    private static final NamespacedId AIR = NamespacedId.parse("minecraft:air");
    private static final Comparator<GridPos> POSITION_ORDER = Comparator
            .comparingInt(GridPos::y)
            .thenComparingInt(GridPos::x)
            .thenComparingInt(GridPos::z);

    public TaskGraph plan(Blueprint blueprint) {
        Objects.requireNonNull(blueprint, "blueprint");
        if (!blueprint.validation().isValid()) {
            throw new IllegalArgumentException("Construction planning requires a validated Blueprint");
        }

        List<BuildTask> tasks = new ArrayList<>();
        Map<GridPos, TerrainPlan.TerrainCellChange> terrainByPosition = new HashMap<>();
        blueprint.terrainPlan().changes().stream()
                .sorted(Comparator.comparing(TerrainPlan.TerrainCellChange::pos, POSITION_ORDER))
                .forEach(change -> {
                    terrainByPosition.put(change.pos(), change);
                    tasks.add(terrainTask(change));
                });
        blueprint.blocks().stream()
                .sorted(Comparator.comparing(BlueprintBlock::relativePosition, POSITION_ORDER))
                .map(block -> blockTask(block, terrainByPosition.get(block.relativePosition())))
                .forEach(tasks::add);
        return new TaskGraph(normalizeAtomicGroups(tasks));
    }

    public static String blockTaskId(GridPos position) {
        return taskId("block", position);
    }

    public static String terrainTaskId(GridPos position) {
        return taskId("terrain", position);
    }

    private static BuildTask terrainTask(TerrainPlan.TerrainCellChange change) {
        TaskOperation operation = operation(change.beforeState(), change.afterState());
        Optional<BuildTask.MaterialRequirement> material = operation.requiresMaterial()
                ? Optional.of(new BuildTask.MaterialRequirement(
                        MaterialRole.FOUNDATION_FILL,
                        change.afterState()))
                : Optional.empty();
        return new BuildTask(
                terrainTaskId(change.pos()),
                change.pos(),
                operation,
                material,
                Set.of(),
                BuildPhase.SITE_PREPARATION,
                WorkZone.containing(change.pos()),
                false,
                Optional.empty());
    }

    private static BuildTask blockTask(
            BlueprintBlock block,
            TerrainPlan.TerrainCellChange terrainChange) {
        Set<String> dependencies = new HashSet<>();
        block.dependencies().stream()
                .map(ConstructionPlanner::blockTaskId)
                .forEach(dependencies::add);
        if (terrainChange != null) {
            dependencies.add(terrainTaskId(block.relativePosition()));
        }
        TaskOperation operation = terrainChange != null
                        && !terrainChange.afterState().blockId().equals(AIR)
                ? TaskOperation.REPLACE
                : TaskOperation.PLACE;
        return new BuildTask(
                blockTaskId(block.relativePosition()),
                block.relativePosition(),
                operation,
                Optional.of(new BuildTask.MaterialRequirement(
                        block.materialRole(),
                        block.placementState())),
                dependencies,
                block.phase(),
                WorkZone.containing(block.relativePosition()),
                false,
                atomicGroup(block));
    }

    private static Optional<String> atomicGroup(BlueprintBlock block) {
        if (block.materialRole() != MaterialRole.DOOR) {
            return Optional.empty();
        }
        String half = block.placementState().properties().get("half");
        int baseY = "upper".equals(half)
                ? block.relativePosition().y() - 1
                : block.relativePosition().y();
        GridPos base = new GridPos(
                block.relativePosition().x(),
                baseY,
                block.relativePosition().z());
        return Optional.of(taskId("door", base));
    }

    private static List<BuildTask> normalizeAtomicGroups(List<BuildTask> tasks) {
        Map<String, List<BuildTask>> groups = new HashMap<>();
        tasks.forEach(task -> task.atomicGroupId().ifPresent(group ->
                groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(task)));
        Map<String, BuildTask> replacements = new HashMap<>();
        groups.forEach((groupId, members) -> {
            Set<String> memberIds = members.stream()
                    .map(BuildTask::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<String> externalDependencies = members.stream()
                    .flatMap(member -> member.dependencyIds().stream())
                    .filter(dependency -> !memberIds.contains(dependency))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            WorkZone groupZone = members.getFirst().workZone();
            BuildPhase groupPhase = members.getFirst().phase();
            if (members.stream().anyMatch(member ->
                    !member.workZone().equals(groupZone) || member.phase() != groupPhase)) {
                throw new IllegalArgumentException(
                        "Atomic task group must remain within one phase and work zone: " + groupId);
            }
            members.forEach(member -> replacements.put(member.id(), new BuildTask(
                    member.id(),
                    member.position(),
                    member.operation(),
                    member.materialRequirement(),
                    externalDependencies,
                    member.phase(),
                    member.workZone(),
                    member.optional(),
                    member.atomicGroupId())));
        });
        return tasks.stream().map(task -> replacements.getOrDefault(task.id(), task)).toList();
    }

    private static TaskOperation operation(BlockStateSpec before, BlockStateSpec after) {
        boolean beforeAir = before.blockId().equals(AIR);
        boolean afterAir = after.blockId().equals(AIR);
        if (!beforeAir && afterAir) {
            return TaskOperation.REMOVE;
        }
        if (beforeAir && !afterAir) {
            return TaskOperation.PLACE;
        }
        if (!beforeAir) {
            return TaskOperation.REPLACE;
        }
        throw new IllegalArgumentException("Terrain task cannot replace air with air");
    }

    private static String taskId(String prefix, GridPos position) {
        Objects.requireNonNull(position, "position");
        return prefix + ":" + position.x() + ":" + position.y() + ":" + position.z();
    }
}
