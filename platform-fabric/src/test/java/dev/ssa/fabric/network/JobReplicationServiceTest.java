package dev.ssa.fabric.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.job.BuildJob.Diagnostic;
import dev.ssa.construction.job.BuildJob.Severity;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.construction.plan.TaskGraph;
import dev.ssa.construction.schedule.WorkZone;
import dev.ssa.construction.task.BuildTask;
import dev.ssa.construction.task.TaskOperation;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class JobReplicationServiceTest {
    private static final UUID OWNER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_PLAYER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void onlyTheOwnerWithPermissionCanStopAndAStaleRetryIsRejected() {
        ServerBuildJobRepository repository = new ServerBuildJobRepository();
        BuildJob job = job("job-stop", OWNER);
        repository.saveJob(job);
        RecordingExecutor executor = new RecordingExecutor();
        JobReplicationService service = new JobReplicationService(
                repository,
                (owner, position) -> true,
                executor);

        JobReplicationService.CommandResult wrongOwner = service
                .stop(job.jobId(), OTHER_PLAYER, job.revision())
                .join();
        JobReplicationService.CommandResult accepted = service
                .stop(job.jobId(), OWNER, job.revision())
                .join();
        JobReplicationService.CommandResult stale = service
                .stop(job.jobId(), OWNER, job.revision())
                .join();

        assertFalse(wrongOwner.accepted());
        assertEquals(JobReplicationService.Rejection.NOT_OWNER, wrongOwner.rejection());
        assertTrue(accepted.accepted());
        assertEquals(BuildJobState.STOPPING, repository.findJob(job.jobId()).orElseThrow().state());
        assertFalse(stale.accepted());
        assertEquals(JobReplicationService.Rejection.STALE_REVISION, stale.rejection());
        assertEquals(1, executor.stopCount.get());
    }

    @Test
    void protectedOwnerCommandIsRejectedBeforeDurableMutation() {
        ServerBuildJobRepository repository = new ServerBuildJobRepository();
        BuildJob job = job("job-protected", OWNER);
        repository.saveJob(job);
        RecordingExecutor executor = new RecordingExecutor();
        JobReplicationService service = new JobReplicationService(
                repository,
                (owner, position) -> false,
                executor);

        JobReplicationService.CommandResult result = service
                .stop(job.jobId(), OWNER, job.revision())
                .join();

        assertFalse(result.accepted());
        assertEquals(JobReplicationService.Rejection.PROTECTED, result.rejection());
        assertEquals(job, repository.findJob(job.jobId()).orElseThrow());
        assertEquals(0, executor.stopCount.get());
    }

    @Test
    void failedPauseCheckpointReturnsExecutionFailureAtAuthoritativeRevision() {
        ServerBuildJobRepository repository = new ServerBuildJobRepository();
        BuildJob job = job("job-pause-failure", OWNER);
        repository.saveJob(job);
        JobReplicationService service = new JobReplicationService(
                repository,
                (owner, position) -> true,
                new JobReplicationService.CommandExecutor() {
                    @Override
                    public CompletableFuture<Void> pause(BuildJob pausedJob) {
                        return CompletableFuture.failedFuture(new IllegalStateException("checkpoint failed"));
                    }

                    @Override
                    public CompletableFuture<Void> resume(BuildJob resumedJob) {
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletableFuture<Void> stop(BuildJob stoppingJob) {
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletableFuture<Void> undo(BuildJob undoingJob) {
                        return CompletableFuture.completedFuture(null);
                    }
                });

        JobReplicationService.CommandResult result = service
                .pause(job.jobId(), OWNER, job.revision())
                .join();
        BuildJob authoritative = repository.findJob(job.jobId()).orElseThrow();

        assertFalse(result.accepted());
        assertEquals(JobReplicationService.Rejection.EXECUTION_FAILED, result.rejection());
        assertEquals(authoritative.revision(), result.revision());
        assertEquals(BuildJobState.PAUSED, authoritative.state());
    }

    @Test
    void undoRequiresAStoppedOrCompletedJobAndPersistsUndoingFirst() {
        ServerBuildJobRepository repository = new ServerBuildJobRepository();
        BuildJob stopped = job("job-undo", OWNER)
                .transitionTo(BuildJobState.STOPPING)
                .transitionTo(BuildJobState.STOPPED);
        repository.saveJob(stopped);
        RecordingExecutor executor = new RecordingExecutor();
        JobReplicationService service = new JobReplicationService(
                repository,
                (owner, position) -> true,
                executor);

        JobReplicationService.CommandResult result = service
                .undo(stopped.jobId(), OWNER, stopped.revision())
                .join();

        assertTrue(result.accepted());
        assertEquals(BuildJobState.UNDOING, repository.findJob(stopped.jobId()).orElseThrow().state());
        assertEquals(1, executor.undoCount.get());
    }

    @Test
    void pauseAndResumeUseTheSameRevisionedServerAuthority() {
        ServerBuildJobRepository repository = new ServerBuildJobRepository();
        BuildJob job = job("job-pause", OWNER);
        repository.saveJob(job);
        RecordingExecutor executor = new RecordingExecutor();
        JobReplicationService service = new JobReplicationService(
                repository,
                (owner, position) -> true,
                executor);

        JobReplicationService.CommandResult paused = service
                .pause(job.jobId(), OWNER, job.revision())
                .join();
        BuildJob durablePause = repository.findJob(job.jobId()).orElseThrow();
        JobReplicationService.CommandResult resumed = service
                .resume(job.jobId(), OWNER, durablePause.revision())
                .join();

        assertTrue(paused.accepted());
        assertEquals(BuildJobState.PAUSED, durablePause.state());
        assertTrue(resumed.accepted());
        assertEquals(BuildJobState.PREPARING, repository.findJob(job.jobId()).orElseThrow().state());
        assertEquals(1, executor.pauseCount.get());
        assertEquals(1, executor.resumeCount.get());
    }

    @Test
    void resumeCommandCannotBypassLostBuilderRecovery() {
        ServerBuildJobRepository repository = new ServerBuildJobRepository();
        BuildJob lostBuilder = job("job-lost-builder", OWNER)
                .transitionTo(BuildJobState.PREPARING)
                .transitionTo(BuildJobState.NO_BUILDER);
        repository.saveJob(lostBuilder);
        RecordingExecutor executor = new RecordingExecutor();
        JobReplicationService service = new JobReplicationService(
                repository,
                (owner, position) -> true,
                executor);

        JobReplicationService.CommandResult result = service
                .resume(lostBuilder.jobId(), OWNER, lostBuilder.revision())
                .join();

        assertFalse(result.accepted());
        assertEquals(JobReplicationService.Rejection.INVALID_STATE, result.rejection());
        assertEquals(lostBuilder, repository.findJob(lostBuilder.jobId()).orElseThrow());
        assertEquals(0, executor.resumeCount.get());
    }

    @Test
    void snapshotCarriesAuthoritativeProgressAndDiagnostics() {
        GridPos conflict = new GridPos(2, 3, 4);
        BuildJob job = job("job-view", OWNER).recordDiagnosticAndTransition(
                new Diagnostic(
                        "WORLD_CONFLICT",
                        Severity.WARNING,
                        "A player changed the target block",
                        true,
                        1,
                        Optional.of(conflict)),
                BuildJobState.PAUSED_CONFLICT);
        BuildTask task = new BuildTask(
                "task-1",
                conflict,
                TaskOperation.REMOVE,
                Optional.empty(),
                Set.of(),
                BuildPhase.FOUNDATION,
                WorkZone.containing(conflict),
                false,
                Optional.empty());

        JobPayloads.JobSnapshot snapshot = JobReplicationService.snapshot(
                job,
                new TaskGraph(List.of(task)),
                Map.of("minecraft:oak_planks", 3));

        assertEquals(job.jobId(), snapshot.jobId());
        assertEquals(OWNER, snapshot.ownerId());
        assertEquals(job.revision(), snapshot.revision());
        assertEquals(BuildJobState.PAUSED_CONFLICT, snapshot.state());
        assertEquals(0, snapshot.completedTasks());
        assertEquals(1, snapshot.totalTasks());
        assertEquals(Map.of("minecraft:oak_planks", 3), snapshot.missingMaterials());
        assertEquals(List.of(conflict), snapshot.conflicts());
        assertEquals("WORLD_CONFLICT", snapshot.diagnostics().getFirst().code());
    }

    private static BuildJob job(String jobId, UUID owner) {
        return BuildJob.create(
                jobId,
                owner.toString(),
                "11111111-1111-1111-1111-111111111111",
                "ssa:test-blueprint",
                "0123456789abcdef",
                NamespacedId.parse("minecraft:overworld"),
                new GridPos(0, 64, 0),
                0);
    }

    private static final class RecordingExecutor implements JobReplicationService.CommandExecutor {
        private final AtomicInteger stopCount = new AtomicInteger();
        private final AtomicInteger undoCount = new AtomicInteger();
        private final AtomicInteger pauseCount = new AtomicInteger();
        private final AtomicInteger resumeCount = new AtomicInteger();

        @Override
        public CompletableFuture<Void> pause(BuildJob pausedJob) {
            assertEquals(BuildJobState.PAUSED, pausedJob.state());
            pauseCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> resume(BuildJob resumedJob) {
            assertEquals(BuildJobState.PREPARING, resumedJob.state());
            resumeCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> stop(BuildJob stoppingJob) {
            assertEquals(BuildJobState.STOPPING, stoppingJob.state());
            stopCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> undo(BuildJob undoingJob) {
            assertEquals(BuildJobState.UNDOING, undoingJob.state());
            undoCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }
}
