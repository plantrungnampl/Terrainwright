package dev.ssa.fabric.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.construction.job.BuildJobState;
import dev.ssa.construction.operation.BlockStateSnapshot;
import dev.ssa.construction.operation.DropPolicy;
import dev.ssa.construction.operation.OperationIntent;
import dev.ssa.construction.operation.OperationKind;
import dev.ssa.construction.operation.OperationStatus;
import dev.ssa.construction.operation.WorldDelta;
import dev.ssa.fabric.lifecycle.BuilderLifecycleTombstone;
import dev.ssa.fabric.persistence.OperationIntentStore;
import dev.ssa.fabric.persistence.PersistenceExecutor;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JobRecoveryServiceTest {
    private static final UUID HUT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BUILDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void authoritativeDeathTombstonesTheIdentityAndRetainsTheJob() {
        ServerBuildJobRepository repository = LifecycleTestFixtures.repository(
                HUT_ID, OWNER_ID, BUILDER_ID, BuildJobState.PREPARING);
        JobRecoveryService service = new JobRecoveryService(repository);

        service.observeDeath(BUILDER_ID, 1200);

        assertEquals(BuildJobState.NO_BUILDER,
                repository.findJob(LifecycleTestFixtures.JOB_ID).orElseThrow().state());
        BuilderLifecycleTombstone lifecycle = repository.findHut(HUT_ID)
                .orElseThrow()
                .builderLifecycle()
                .orElseThrow();
        assertTrue(lifecycle.canReplace());
        assertEquals(BuilderLifecycleTombstone.Cause.DEATH,
                lifecycle.tombstone().orElseThrow().cause());
        assertEquals(
                JobRecoveryService.Outcome.NO_BUILDER,
                service.reconcileLoadedBuilder(BUILDER_ID).outcome());
    }

    @Test
    void activeIdentityReopensOnlyThroughOperationRecovery() {
        ServerBuildJobRepository repository = LifecycleTestFixtures.repository(
                HUT_ID, OWNER_ID, BUILDER_ID, BuildJobState.PREPARING);

        JobRecoveryService.Reconciliation reconciliation =
                new JobRecoveryService(repository).reconcileLoadedBuilder(BUILDER_ID);

        assertEquals(JobRecoveryService.Outcome.READY_FOR_OPERATION_RECOVERY, reconciliation.outcome());
        assertEquals(LifecycleTestFixtures.JOB_ID, reconciliation.jobId().orElseThrow());
    }

    @Test
    void unresolvedLostBuilderIntentQuarantinesTheWalAndRetainedJob(@TempDir Path temporaryDirectory) {
        ServerBuildJobRepository repository = LifecycleTestFixtures.repository(
                HUT_ID, OWNER_ID, BUILDER_ID, BuildJobState.PREPARING);
        JobRecoveryService service = new JobRecoveryService(repository);
        service.observeDeath(BUILDER_ID, 1200);
        try (PersistenceExecutor persistence = new PersistenceExecutor("lost-builder-intent-test")) {
            OperationIntentStore store = new OperationIntentStore(
                    temporaryDirectory.resolve("builder.wal"), persistence);
            store.prepare(OperationIntent.prepared(
                    "operation-1",
                    LifecycleTestFixtures.JOB_ID,
                    0,
                    OperationKind.WORLD_MUTATION,
                    List.of(new WorldDelta(
                            "minecraft:overworld",
                            1,
                            64,
                            1,
                            BlockStateSnapshot.of("minecraft:air", new byte[0]),
                            BlockStateSnapshot.of("minecraft:stone", new byte[0]),
                            DropPolicy.SUPPRESS))))
                    .join();

            assertEquals(
                    JobRecoveryService.IntentOutcome.QUARANTINED,
                    service.quarantineLostBuilderIntent(BUILDER_ID, store, Runnable::run).join());
            assertEquals(
                    OperationStatus.QUARANTINED,
                    store.loadActive().join().orElseThrow().status());
        }

        assertEquals(
                BuildJobState.QUARANTINED_RECOVERY,
                repository.findJob(LifecycleTestFixtures.JOB_ID).orElseThrow().state());
        assertEquals(
                JobRecoveryService.Outcome.QUARANTINED,
                service.reconcileLoadedBuilder(BUILDER_ID).outcome());
    }
}
