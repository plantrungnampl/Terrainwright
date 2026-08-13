package dev.ssa.fabric.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.construction.journal.JournalEntry;
import dev.ssa.construction.plan.TaskGraph;
import dev.ssa.construction.scaffold.ScaffoldPlan;
import dev.ssa.construction.scaffold.ScaffoldProvenance;
import dev.ssa.construction.schedule.WorkZone;
import dev.ssa.construction.task.BuildTask;
import dev.ssa.construction.task.TaskOperation;
import dev.ssa.fabric.lifecycle.BuilderLifecycleTombstone;
import dev.ssa.fabric.link.ContainerBinding;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class ServerBuildJobRepositoryTest {
    private static final UUID HUT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BUILDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void codecRoundTripPreservesAuthoritativeJobHutBindingAndLifecycle() {
        BuildJob job = job(1);
        ContainerBinding binding = ContainerBinding.resolve(
                Identifier.parse("minecraft:overworld"),
                new BlockPos(5, 64, 8),
                Optional.of(new BlockPos(6, 64, 8)),
                Optional.empty());
        BuilderLifecycleTombstone lifecycle = BuilderLifecycleTombstone.active(BUILDER_ID).observeDeath(1200);
        ServerBuildJobRepository repository = new ServerBuildJobRepository();

        repository.saveJob(job);
        repository.savePlan(job.jobId(), plan());
        repository.saveScaffold(ScaffoldProvenance.planned(
                job.jobId(),
                "scaffold-1",
                "task-1",
                new ScaffoldPlan(List.of(new ScaffoldPlan.Placement(
                        new GridPos(11, 64, 10),
                        BlockStateSpec.of(NamespacedId.parse("minecraft:scaffolding"), Map.of())))))
                .recordPlaced(0, "scaffold-place-1"));
        repository.saveHutState(new ServerBuildJobRepository.HutState(
                HUT_ID,
                OWNER_ID,
                Optional.of(job.jobId()),
                Optional.of(binding),
                Optional.of(lifecycle),
                1));

        Tag encoded = ServerBuildJobRepository.CODEC.encodeStart(NbtOps.INSTANCE, repository).getOrThrow();
        ServerBuildJobRepository decoded = ServerBuildJobRepository.CODEC
                .parse(NbtOps.INSTANCE, encoded)
                .getOrThrow();

        assertEquals(repository.jobs(), decoded.jobs());
        assertEquals(repository.huts(), decoded.huts());
        assertEquals(repository.scaffolds(), decoded.scaffolds());
        assertEquals(
                BuilderPlanCodec.encode(repository.findPlan(job.jobId()).orElseThrow()),
                BuilderPlanCodec.encode(decoded.findPlan(job.jobId()).orElseThrow()));
        assertTrue(repository.isDirty());
        assertFalse(decoded.isDirty());
    }

    @Test
    void hutRemovalCannotEraseJobAndStaleJobWriteIsRejected() {
        ServerBuildJobRepository repository = new ServerBuildJobRepository();
        BuildJob current = job(1);
        repository.saveJob(current);
        repository.saveHutState(new ServerBuildJobRepository.HutState(
                HUT_ID, OWNER_ID, Optional.of(current.jobId()), Optional.empty(), Optional.empty(), 0));

        repository.removeHut(HUT_ID);

        assertEquals(Optional.of(current), repository.findJob(current.jobId()));
        assertTrue(repository.findHut(HUT_ID).isEmpty());
        assertThrows(IllegalStateException.class, () -> repository.saveJob(job(0)));
    }

    @Test
    void hutCannotClaimAJobOwnedByAnotherDurableIdentity() {
        ServerBuildJobRepository repository = new ServerBuildJobRepository();
        BuildJob job = job(1);
        repository.saveJob(job);

        ServerBuildJobRepository.HutState foreignHut = new ServerBuildJobRepository.HutState(
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                OWNER_ID,
                Optional.of(job.jobId()),
                Optional.empty(),
                Optional.empty(),
                0);

        assertThrows(IllegalStateException.class, () -> repository.saveHutState(foreignHut));
        assertTrue(repository.huts().isEmpty());
    }

    @Test
    void scaffoldMustReferenceADurableTaskAndBeTheOnlyActivePlanForItsJob() {
        ServerBuildJobRepository repository = new ServerBuildJobRepository();
        BuildJob job = job(1);
        repository.saveJob(job);
        repository.savePlan(job.jobId(), plan());

        assertThrows(IllegalStateException.class, () -> repository.saveScaffold(scaffold(
                job.jobId(), "unknown-task-plan", "unknown-task")));

        ScaffoldProvenance first = scaffold(job.jobId(), "scaffold-1", "task-1");
        repository.saveScaffold(first);
        assertThrows(IllegalStateException.class, () -> repository.saveScaffold(scaffold(
                job.jobId(), "scaffold-2", "task-1")));

        repository.saveScaffold(first.recordPlaced(0, "place-1").recordRemoved(0, "remove-1"));
        repository.saveScaffold(scaffold(job.jobId(), "scaffold-2", "task-1"));
        assertEquals(2, repository.scaffolds().size());
    }

    private static BuildJob job(long revision) {
        if (revision == 0) {
            return BuildJob.create(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    OWNER_ID.toString(),
                    HUT_ID.toString(),
                    "blueprint-1",
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    NamespacedId.parse("minecraft:overworld"),
                    new GridPos(10, 64, 10),
                    90);
        }
        JournalEntry journal = new JournalEntry(
                0,
                "entry-1",
                "operation-1",
                "task-1",
                new GridPos(10, 64, 10),
                BlockStateSpec.of(NamespacedId.parse("minecraft:air"), Map.of()),
                BlockStateSpec.of(NamespacedId.parse("minecraft:oak_planks"), Map.of()),
                1);
        return new BuildJob(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                OWNER_ID.toString(),
                HUT_ID.toString(),
                "blueprint-1",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                NamespacedId.parse("minecraft:overworld"),
                new GridPos(10, 64, 10),
                90,
                BuildJobState.BUILDING,
                Optional.of(BuildPhase.FOUNDATION),
                Set.of("task-1"),
                List.of(),
                List.of(journal),
                revision,
                BuildJob.CURRENT_FORMAT_VERSION);
    }

    private static TaskGraph plan() {
        GridPos position = new GridPos(10, 64, 10);
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

    private static ScaffoldProvenance scaffold(String jobId, String planId, String taskId) {
        return ScaffoldProvenance.planned(
                jobId,
                planId,
                taskId,
                new ScaffoldPlan(List.of(new ScaffoldPlan.Placement(
                        new GridPos(11, 64, 10),
                        BlockStateSpec.of(NamespacedId.parse("minecraft:scaffolding"), Map.of())))));
    }
}
