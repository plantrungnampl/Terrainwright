package dev.ssa.fabric.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
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
