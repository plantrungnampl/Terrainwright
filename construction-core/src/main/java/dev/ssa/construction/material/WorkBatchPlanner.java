package dev.ssa.construction.material;

import dev.ssa.construction.schedule.WorkZone;
import dev.ssa.construction.plan.TaskGraph;
import dev.ssa.construction.task.BuildTask;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

public final class WorkBatchPlanner {
    public Optional<WorkBatch> plan(
            TaskGraph.Frontier frontier,
            int maximumTasks,
            int materialItemCapacity) {
        Objects.requireNonNull(frontier, "frontier");
        return planEligibleTasks(frontier.eligibleTasks(), maximumTasks, materialItemCapacity);
    }

    private static Optional<WorkBatch> planEligibleTasks(
            Collection<BuildTask> eligibleTasks,
            int maximumTasks,
            int materialItemCapacity) {
        Objects.requireNonNull(eligibleTasks, "eligibleTasks");
        if (maximumTasks <= 0 || materialItemCapacity < 0) {
            throw new IllegalArgumentException(
                    "Task capacity must be positive and material capacity must not be negative");
        }
        if (eligibleTasks.isEmpty()) {
            return Optional.empty();
        }

        Map<String, BuildTask> byId = new HashMap<>();
        Map<String, WorkZone> atomicZones = new HashMap<>();
        Map<WorkZone, List<BuildTask>> byZone = new HashMap<>();
        for (BuildTask task : eligibleTasks) {
            Objects.requireNonNull(task, "task");
            if (byId.put(task.id(), task) != null) {
                throw new IllegalArgumentException("Duplicate eligible task ID: " + task.id());
            }
            task.atomicGroupId().ifPresent(group -> {
                WorkZone prior = atomicZones.putIfAbsent(group, task.workZone());
                if (prior != null && !prior.equals(task.workZone())) {
                    throw new IllegalArgumentException(
                            "Atomic group crosses work zones: " + group);
                }
            });
            byZone.computeIfAbsent(task.workZone(), ignored -> new ArrayList<>()).add(task);
        }

        List<Map.Entry<WorkZone, List<BuildTask>>> zones = new ArrayList<>(byZone.entrySet());
        zones.sort(Comparator
                .<Map.Entry<WorkZone, List<BuildTask>>>comparingInt(entry -> entry.getValue().size())
                .reversed()
                .thenComparing(entry -> entry.getKey().id()));
        for (Map.Entry<WorkZone, List<BuildTask>> zone : zones) {
            Optional<WorkBatch> batch = batchInZone(
                    zone.getKey(),
                    zone.getValue(),
                    maximumTasks,
                    materialItemCapacity);
            if (batch.isPresent()) {
                return batch;
            }
        }
        return Optional.empty();
    }

    private static Optional<WorkBatch> batchInZone(
            WorkZone workZone,
            List<BuildTask> tasks,
            int maximumTasks,
            int materialItemCapacity) {
        Map<String, List<BuildTask>> units = new TreeMap<>();
        tasks.forEach(task -> units.computeIfAbsent(
                task.atomicGroupId().map(group -> "atomic:" + group)
                        .orElse("single:" + task.id()),
                ignored -> new ArrayList<>()).add(task));
        units.values().forEach(unit -> unit.sort(Comparator.comparing(BuildTask::id)));

        List<BuildTask> selected = new ArrayList<>();
        int materialItems = 0;
        for (List<BuildTask> unit : units.values()) {
            int unitMaterialItems = (int) unit.stream()
                    .filter(task -> task.materialRequirement().isPresent())
                    .count();
            if (selected.size() + unit.size() > maximumTasks
                    || materialItems + unitMaterialItems > materialItemCapacity) {
                continue;
            }
            selected.addAll(unit);
            materialItems += unitMaterialItems;
        }
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        selected.sort(Comparator.comparing(BuildTask::id));

        Map<BuildTask.MaterialRequirement, Integer> counts = new HashMap<>();
        selected.stream()
                .map(BuildTask::materialRequirement)
                .flatMap(Optional::stream)
                .forEach(requirement -> counts.merge(requirement, 1, Integer::sum));
        List<Map.Entry<BuildTask.MaterialRequirement, Integer>> orderedCounts =
                new ArrayList<>(counts.entrySet());
        orderedCounts.sort(Comparator
                .comparing((Map.Entry<BuildTask.MaterialRequirement, Integer> entry) ->
                        entry.getKey().materialRole().name())
                .thenComparing(entry -> entry.getKey().state().blockId().toString())
                .thenComparing(entry -> entry.getKey().state().properties().toString()));
        Map<BuildTask.MaterialRequirement, Integer> materialCounts = new LinkedHashMap<>();
        orderedCounts.forEach(entry -> materialCounts.put(entry.getKey(), entry.getValue()));
        return Optional.of(new WorkBatch(
                workZone,
                selected.stream().map(BuildTask::id).toList(),
                materialCounts));
    }

    public record WorkBatch(
            WorkZone workZone,
            List<String> taskIds,
            Map<BuildTask.MaterialRequirement, Integer> materialCounts) {
        public WorkBatch {
            Objects.requireNonNull(workZone, "workZone");
            taskIds = List.copyOf(Objects.requireNonNull(taskIds, "taskIds"));
            materialCounts = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(materialCounts, "materialCounts")));
            if (taskIds.isEmpty()) {
                throw new IllegalArgumentException("Work batch must contain at least one task");
            }
            if (new HashSet<>(taskIds).size() != taskIds.size()) {
                throw new IllegalArgumentException("Work batch task IDs must be unique");
            }
            if (materialCounts.values().stream().anyMatch(count -> count <= 0)) {
                throw new IllegalArgumentException("Material counts must be positive");
            }
        }
    }
}
