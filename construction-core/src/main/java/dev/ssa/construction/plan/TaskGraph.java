package dev.ssa.construction.plan;

import dev.ssa.construction.task.BuildTask;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class TaskGraph {
    private final Map<String, BuildTask> tasks;
    private final Map<String, Set<String>> dependentsByTaskId;
    private final Map<String, Set<String>> atomicGroupMembersByTaskId;

    public TaskGraph(Collection<BuildTask> tasks) {
        Objects.requireNonNull(tasks, "tasks");
        TreeMap<String, BuildTask> ordered = new TreeMap<>();
        for (BuildTask task : tasks) {
            Objects.requireNonNull(task, "task");
            if (ordered.put(task.id(), task) != null) {
                throw new IllegalArgumentException("Duplicate build task ID: " + task.id());
            }
        }
        this.tasks = Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
        this.atomicGroupMembersByTaskId = buildAtomicGroups(this.tasks);
        this.dependentsByTaskId = buildDependents(this.tasks);
        requireAcyclic(this.tasks, dependentsByTaskId);
    }

    public Map<String, BuildTask> tasks() {
        return tasks;
    }

    public BuildTask task(String id) {
        BuildTask task = tasks.get(Objects.requireNonNull(id, "id"));
        if (task == null) {
            throw new IllegalArgumentException("Unknown build task ID: " + id);
        }
        return task;
    }

    public Set<String> eligibleTaskIds(Set<String> completedTaskIds) {
        return frontier(completedTaskIds).eligibleTaskIds();
    }

    public Frontier frontier(Set<String> completedTaskIds) {
        return new Frontier(completedTaskIds);
    }

    private static Map<String, Set<String>> buildDependents(Map<String, BuildTask> tasks) {
        Map<String, Set<String>> dependents = new HashMap<>();
        tasks.keySet().forEach(id -> dependents.put(id, new TreeSet<>()));
        for (BuildTask task : tasks.values()) {
            for (String dependencyId : task.dependencyIds()) {
                Set<String> dependencyDependents = dependents.get(dependencyId);
                if (dependencyDependents == null) {
                    throw new IllegalArgumentException(
                            "Task " + task.id() + " references missing dependency " + dependencyId);
                }
                dependencyDependents.add(task.id());
            }
        }
        Map<String, Set<String>> immutable = new LinkedHashMap<>();
        new TreeMap<>(dependents).forEach((id, ids) -> immutable.put(id, Set.copyOf(ids)));
        return Collections.unmodifiableMap(immutable);
    }

    private static Map<String, Set<String>> buildAtomicGroups(Map<String, BuildTask> tasks) {
        Map<String, Set<String>> membersByGroupId = new HashMap<>();
        tasks.values().forEach(task -> task.atomicGroupId().ifPresent(groupId ->
                membersByGroupId.computeIfAbsent(groupId, ignored -> new TreeSet<>()).add(task.id())));

        Map<String, Set<String>> membersByTaskId = new HashMap<>();
        membersByGroupId.forEach((groupId, memberIds) -> {
            Set<String> immutableMemberIds = Set.copyOf(memberIds);
            BuildTask first = tasks.get(memberIds.iterator().next());
            for (String memberId : memberIds) {
                BuildTask member = tasks.get(memberId);
                if (member.phase() != first.phase()
                        || !member.workZone().equals(first.workZone())
                        || !member.dependencyIds().equals(first.dependencyIds())
                        || member.dependencyIds().stream().anyMatch(memberIds::contains)) {
                    throw new IllegalArgumentException(
                            "Atomic task group must share one phase, work zone, and external dependencies: "
                                    + groupId);
                }
                membersByTaskId.put(memberId, immutableMemberIds);
            }
        });
        return Map.copyOf(membersByTaskId);
    }

    private static void requireAcyclic(
            Map<String, BuildTask> tasks,
            Map<String, Set<String>> dependents) {
        Map<String, Integer> remaining = new HashMap<>();
        PriorityQueue<String> ready = new PriorityQueue<>();
        tasks.values().forEach(task -> {
            int count = task.dependencyIds().size();
            remaining.put(task.id(), count);
            if (count == 0) {
                ready.add(task.id());
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String completed = ready.remove();
            visited++;
            for (String dependent : dependents.get(completed)) {
                int count = remaining.compute(dependent, (ignored, value) -> value - 1);
                if (count == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (visited != tasks.size()) {
            throw new IllegalArgumentException("Build task graph contains a dependency cycle");
        }
    }

    public final class Frontier {
        private final Set<String> completed = new HashSet<>();
        private final Map<String, Integer> remainingDependencies = new HashMap<>();
        private final TreeSet<String> eligible = new TreeSet<>();

        private Frontier(Set<String> completedTaskIds) {
            Set<String> initial = Set.copyOf(Objects.requireNonNull(completedTaskIds, "completedTaskIds"));
            for (String id : initial) {
                BuildTask task = tasks.get(id);
                if (task == null) {
                    throw new IllegalArgumentException("Unknown completed build task ID: " + id);
                }
                Set<String> completedUnit = atomicGroupMembersByTaskId.get(id);
                if (completedUnit != null && !initial.containsAll(completedUnit)) {
                    throw new IllegalArgumentException(
                            "Completed task set contains a partial atomic group at: " + id);
                }
                if (!initial.containsAll(task.dependencyIds())) {
                    throw new IllegalArgumentException(
                            "Completed task set is not dependency-closed at: " + id);
                }
            }
            completed.addAll(initial);
            for (BuildTask task : tasks.values()) {
                if (completed.contains(task.id())) {
                    continue;
                }
                int remaining = (int) task.dependencyIds().stream()
                        .filter(dependency -> !completed.contains(dependency))
                        .count();
                remainingDependencies.put(task.id(), remaining);
                if (remaining == 0) {
                    eligible.add(task.id());
                }
            }
        }

        public Set<String> eligibleTaskIds() {
            return Set.copyOf(eligible);
        }

        public Set<String> completedTaskIds() {
            return Set.copyOf(completed);
        }

        public List<BuildTask> eligibleTasks() {
            return eligible.stream().map(tasks::get).toList();
        }

        public Set<String> complete(String taskId) {
            Objects.requireNonNull(taskId, "taskId");
            if (!tasks.containsKey(taskId)) {
                throw new IllegalArgumentException("Unknown build task ID: " + taskId);
            }
            Set<String> completedUnit = atomicGroupMembersByTaskId.getOrDefault(
                    taskId,
                    Set.of(taskId));
            if (!eligible.containsAll(completedUnit)) {
                throw new IllegalArgumentException("Build task is not eligible: " + taskId);
            }
            eligible.removeAll(completedUnit);
            completed.addAll(completedUnit);
            completedUnit.forEach(remainingDependencies::remove);
            for (String completedTaskId : completedUnit) {
                for (String dependent : dependentsByTaskId.get(completedTaskId)) {
                    if (completed.contains(dependent)) {
                        continue;
                    }
                    int remaining = remainingDependencies.compute(
                            dependent,
                            (ignored, count) -> Objects.requireNonNull(count) - 1);
                    if (remaining == 0) {
                        eligible.add(dependent);
                    }
                }
            }
            return eligibleTaskIds();
        }
    }
}
