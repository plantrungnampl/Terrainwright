package dev.ssa.fabric.network;

import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

public final class JobControlAuthorityGameTest {
    @GameTest(maxTicks = 20)
    public void onlyOwnerControlsBuilderJobAndStaleRetryCannotMutate(GameTestHelper context) {
        ServerPlayer owner = context.makeMockServerPlayerInLevel();
        ServerPlayer other = context.makeMockServerPlayerInLevel();
        context.assertTrue(!owner.getUUID().equals(other.getUUID()), "mock players did not have distinct identities");
        BuildJob job = BuildJob.create(
                "job-two-player-authority-" + UUID.randomUUID(),
                owner.getUUID().toString(),
                "11111111-1111-1111-1111-111111111111",
                "ssa:test-blueprint",
                "0123456789abcdef",
                NamespacedId.parse(context.getLevel().dimension().identifier().toString()),
                new GridPos(0, 64, 0),
                0);
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(context.getLevel());
        repository.saveJob(job);
        RecordingExecutor executor = new RecordingExecutor();
        JobReplicationService service = new JobReplicationService(
                repository,
                (requester, position) -> true,
                executor);

        JobReplicationService.CommandResult rejected = service
                .pause(job.jobId(), other.getUUID(), job.revision())
                .join();
        JobReplicationService.CommandResult accepted = service
                .pause(job.jobId(), owner.getUUID(), job.revision())
                .join();
        JobReplicationService.CommandResult stale = service
                .pause(job.jobId(), owner.getUUID(), job.revision())
                .join();

        context.assertValueEqual(
                rejected.rejection(), JobReplicationService.Rejection.NOT_OWNER, "non-owner command");
        context.assertTrue(accepted.accepted(), "owner command was rejected");
        context.assertValueEqual(stale.rejection(), JobReplicationService.Rejection.STALE_REVISION, "stale retry");
        context.assertValueEqual(
                repository.findJob(job.jobId()).orElseThrow().state(), BuildJobState.PAUSED, "durable state");
        context.assertValueEqual(executor.pauseCount.get(), 1, "authoritative pause executions");
        context.succeed();
    }

    private static final class RecordingExecutor implements JobReplicationService.CommandExecutor {
        private final AtomicInteger pauseCount = new AtomicInteger();

        @Override
        public CompletableFuture<Void> pause(BuildJob pausedJob) {
            pauseCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
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
    }
}
