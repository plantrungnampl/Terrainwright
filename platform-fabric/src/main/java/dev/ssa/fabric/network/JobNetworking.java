package dev.ssa.fabric.network;

import dev.ssa.fabric.SmartSurvivalArchitectMod;
import dev.ssa.fabric.builder.BuilderRuntimeService;
import dev.ssa.fabric.entity.BuilderEntity;
import dev.ssa.fabric.network.JobPayloads.JobCommand;
import dev.ssa.fabric.network.JobPayloads.JobCommandResult;
import dev.ssa.fabric.network.JobPayloads.JobDelta;
import dev.ssa.fabric.network.JobPayloads.JobSnapshot;
import dev.ssa.fabric.network.JobPayloads.RequestJobSnapshot;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-owned Builder Hut job replication and control transport. */
public final class JobNetworking {
    private static final Logger LOGGER = LoggerFactory.getLogger(SmartSurvivalArchitectMod.MOD_ID + "/jobs");
    private static boolean initialized;

    private JobNetworking() {}

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        PayloadTypeRegistry.serverboundPlay().register(RequestJobSnapshot.TYPE, RequestJobSnapshot.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(JobCommand.TYPE, JobCommand.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(JobSnapshot.TYPE, JobSnapshot.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(JobDelta.TYPE, JobDelta.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(JobCommandResult.TYPE, JobCommandResult.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RequestJobSnapshot.TYPE, JobNetworking::requestSnapshot);
        ServerPlayNetworking.registerGlobalReceiver(JobCommand.TYPE, JobNetworking::command);
    }

    private static void requestSnapshot(RequestJobSnapshot payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(player.level());
        repository.findHut(payload.hutId())
                .filter(hut -> hut.ownerId().equals(player.getUUID()))
                .flatMap(ServerBuildJobRepository.HutState::activeJobId)
                .ifPresent(jobId -> sendSnapshot(player, repository, jobId));
    }

    private static void command(JobCommand payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        ServerLevel level = player.level();
        CompletableFuture<JobReplicationService.CommandResult> result = switch (payload.action()) {
            case PAUSE -> BuilderRuntimeService.pauseJob(
                    level, payload.jobId(), player.getUUID(), payload.expectedRevision());
            case RESUME -> BuilderRuntimeService.resumeJob(
                    level, payload.jobId(), player.getUUID(), payload.expectedRevision());
            case STOP -> BuilderRuntimeService.stopJob(
                    level, payload.jobId(), player.getUUID(), payload.expectedRevision());
            case UNDO -> BuilderRuntimeService.undoJob(
                    level, payload.jobId(), player.getUUID(), payload.expectedRevision());
        };
        result.whenComplete((outcome, failure) -> context.server().execute(() -> {
            ServerBuildJobRepository repository = ServerBuildJobRepository.get(level);
            if (failure != null) {
                LOGGER.error("Builder job command failed for {}", payload.jobId(), failure);
                long revision = repository.findJob(payload.jobId())
                        .map(dev.ssa.construction.job.BuildJob::revision)
                        .orElse(-1L);
                sendResult(player, new JobCommandResult(
                        payload.jobId(),
                        false,
                        JobReplicationService.Rejection.EXECUTION_FAILED,
                        revision));
                return;
            }
            sendResult(player, new JobCommandResult(
                    payload.jobId(),
                    outcome.accepted(),
                    outcome.rejection(),
                    outcome.revision()));
            sendDelta(player, repository, payload.jobId());
        }));
    }

    private static void sendSnapshot(
            ServerPlayer player,
            ServerBuildJobRepository repository,
            String jobId) {
        if (!ServerPlayNetworking.canSend(player, JobSnapshot.TYPE)) {
            return;
        }
        repository.findJob(jobId).flatMap(job -> repository.findPlan(jobId)
                        .map(plan -> JobReplicationService.snapshot(
                                job, plan, missingMaterials(player.level(), repository, job.hutId()))))
                .ifPresent(snapshot -> ServerPlayNetworking.send(player, snapshot));
    }

    private static void sendDelta(
            ServerPlayer player,
            ServerBuildJobRepository repository,
            String jobId) {
        if (!ServerPlayNetworking.canSend(player, JobDelta.TYPE)) {
            return;
        }
        repository.findJob(jobId).flatMap(job -> repository.findPlan(jobId)
                        .map(plan -> JobReplicationService.snapshot(
                                job, plan, missingMaterials(player.level(), repository, job.hutId()))))
                .map(JobNetworking::delta)
                .ifPresent(delta -> ServerPlayNetworking.send(player, delta));
    }

    private static Map<String, Integer> missingMaterials(
            ServerLevel level,
            ServerBuildJobRepository repository,
            String hutId) {
        try {
            return repository.findHut(UUID.fromString(hutId))
                    .flatMap(ServerBuildJobRepository.HutState::builderLifecycle)
                    .map(lifecycle -> level.getEntity(lifecycle.builderId()))
                    .filter(BuilderEntity.class::isInstance)
                    .map(BuilderEntity.class::cast)
                    .map(builder -> builder.missingMaterials(level))
                    .orElse(Map.of());
        } catch (IllegalArgumentException ignored) {
            return Map.of();
        }
    }

    private static JobDelta delta(JobSnapshot snapshot) {
        return new JobDelta(
                snapshot.jobId(),
                snapshot.hutId(),
                snapshot.ownerId(),
                snapshot.revision(),
                snapshot.state(),
                snapshot.completedTasks(),
                snapshot.totalTasks(),
                snapshot.missingMaterials(),
                snapshot.conflicts(),
                snapshot.diagnostics());
    }

    private static void sendResult(ServerPlayer player, JobCommandResult result) {
        if (ServerPlayNetworking.canSend(player, JobCommandResult.TYPE)) {
            ServerPlayNetworking.send(player, result);
        }
    }
}
