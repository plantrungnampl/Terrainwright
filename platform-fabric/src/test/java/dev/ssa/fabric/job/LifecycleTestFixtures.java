package dev.ssa.fabric.job;

import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.construction.plan.TaskGraph;
import dev.ssa.construction.schedule.WorkZone;
import dev.ssa.construction.task.BuildTask;
import dev.ssa.construction.task.TaskOperation;
import dev.ssa.fabric.lifecycle.BuilderLifecycleTombstone;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class LifecycleTestFixtures {
    static final String JOB_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private LifecycleTestFixtures() {
    }

    static ServerBuildJobRepository repository(
            UUID hutId,
            UUID ownerId,
            UUID builderId,
            BuildJobState state) {
        ServerBuildJobRepository repository = new ServerBuildJobRepository();
        BuildJob job = BuildJob.create(
                JOB_ID,
                ownerId.toString(),
                hutId.toString(),
                "blueprint-1",
                "aaaaaaaaaaaaaaaa",
                NamespacedId.parse("minecraft:overworld"),
                new GridPos(0, 64, 0),
                0);
        if (state != BuildJobState.IDLE) {
            job = job.transitionTo(state);
        }
        repository.saveJob(job);
        repository.savePlan(JOB_ID, plan());
        repository.saveHutState(new ServerBuildJobRepository.HutState(
                hutId,
                ownerId,
                Optional.of(JOB_ID),
                Optional.empty(),
                Optional.of(BuilderLifecycleTombstone.active(builderId)),
                1));
        return repository;
    }

    private static TaskGraph plan() {
        GridPos position = new GridPos(0, 64, 0);
        return new TaskGraph(List.of(new BuildTask(
                "task-1",
                position,
                TaskOperation.PLACE,
                Optional.of(new BuildTask.MaterialRequirement(
                        MaterialRole.STRUCTURAL_WOOD,
                        BlockStateSpec.of(NamespacedId.parse("minecraft:oak_planks"), Map.of()))),
                Set.of(),
                BuildPhase.FOUNDATION,
                WorkZone.containing(position),
                false,
                Optional.empty())));
    }
}
