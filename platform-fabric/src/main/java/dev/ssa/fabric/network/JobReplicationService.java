package dev.ssa.fabric.network;

import dev.ssa.common.permission.PermissionPort;
import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.construction.plan.TaskGraph;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Server authority for revisioned Builder job control commands. */
public final class JobReplicationService {
    private final ServerBuildJobRepository repository;
    private final PermissionPort permissions;
    private final CommandExecutor executor;

    public JobReplicationService(
            ServerBuildJobRepository repository,
            PermissionPort permissions,
            CommandExecutor executor) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public static JobPayloads.JobSnapshot snapshot(BuildJob job, TaskGraph plan) {
        return snapshot(job, plan, Map.of());
    }

    public static JobPayloads.JobSnapshot snapshot(
            BuildJob job,
            TaskGraph plan,
            Map<String, Integer> missingMaterials) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(missingMaterials, "missingMaterials");
        List<JobPayloads.DiagnosticView> diagnostics = job.failedTaskDiagnostics().stream()
                .limit(64)
                .map(diagnostic -> new JobPayloads.DiagnosticView(
                        diagnostic.code(),
                        diagnostic.message(),
                        diagnostic.recoverable(),
                        diagnostic.position()))
                .toList();
        List<dev.ssa.architect.model.GridPos> conflicts = job.failedTaskDiagnostics().stream()
                .filter(diagnostic -> diagnostic.code().contains("CONFLICT"))
                .flatMap(diagnostic -> diagnostic.position().stream())
                .distinct()
                .limit(512)
                .toList();
        return new JobPayloads.JobSnapshot(
                job.jobId(),
                UUID.fromString(job.hutId()),
                UUID.fromString(job.ownerId()),
                job.revision(),
                job.state(),
                job.completedTaskIds().size(),
                plan.tasks().size(),
                missingMaterials,
                conflicts,
                diagnostics);
    }

    public CompletableFuture<CommandResult> stop(
            String jobId,
            UUID requester,
            long expectedRevision) {
        return command(jobId, requester, expectedRevision, BuildJobState.STOPPING, executor::stop);
    }

    public CompletableFuture<CommandResult> pause(
            String jobId,
            UUID requester,
            long expectedRevision) {
        return command(jobId, requester, expectedRevision, BuildJobState.PAUSED, executor::pause);
    }

    public CompletableFuture<CommandResult> resume(
            String jobId,
            UUID requester,
            long expectedRevision) {
        return command(jobId, requester, expectedRevision, BuildJobState.PREPARING, executor::resume);
    }

    public CompletableFuture<CommandResult> undo(
            String jobId,
            UUID requester,
            long expectedRevision) {
        return command(jobId, requester, expectedRevision, BuildJobState.UNDOING, executor::undo);
    }

    private CompletableFuture<CommandResult> command(
            String jobId,
            UUID requester,
            long expectedRevision,
            BuildJobState targetState,
            java.util.function.Function<BuildJob, CompletableFuture<Void>> action) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(requester, "requester");
        BuildJob current = repository.findJob(jobId).orElse(null);
        if (current == null) {
            return rejected(Rejection.UNKNOWN_JOB, -1);
        }
        if (!current.ownerId().equals(requester.toString())) {
            return rejected(Rejection.NOT_OWNER, current.revision());
        }
        if (current.revision() != expectedRevision) {
            return rejected(Rejection.STALE_REVISION, current.revision());
        }
        if (!permissions.canModify(requester, current.worldId().toString(), current.origin())) {
            return rejected(Rejection.PROTECTED, current.revision());
        }
        if (targetState == BuildJobState.PREPARING && current.state() != BuildJobState.PAUSED) {
            return rejected(Rejection.INVALID_STATE, current.revision());
        }
        if (current.state() == targetState) {
            return CompletableFuture.completedFuture(CommandResult.accepted(current.revision()));
        }
        if (!current.state().canTransitionTo(targetState)) {
            return rejected(Rejection.INVALID_STATE, current.revision());
        }

        BuildJob next = current.transitionTo(targetState);
        repository.saveJob(next);
        CompletableFuture<Void> execution;
        try {
            execution = Objects.requireNonNull(action.apply(next), "command execution future");
        } catch (RuntimeException failure) {
            return rejected(Rejection.EXECUTION_FAILED, next.revision());
        }
        return execution.handle((ignored, failure) -> failure == null
                ? CommandResult.accepted(next.revision())
                : CommandResult.rejected(Rejection.EXECUTION_FAILED, next.revision()));
    }

    private static CompletableFuture<CommandResult> rejected(Rejection rejection, long revision) {
        return CompletableFuture.completedFuture(CommandResult.rejected(rejection, revision));
    }

    public interface CommandExecutor {
        CompletableFuture<Void> pause(BuildJob pausedJob);

        CompletableFuture<Void> resume(BuildJob resumedJob);

        CompletableFuture<Void> stop(BuildJob stoppingJob);

        CompletableFuture<Void> undo(BuildJob undoingJob);
    }

    public record CommandResult(boolean accepted, Rejection rejection, long revision) {
        public CommandResult {
            Objects.requireNonNull(rejection, "rejection");
            if (accepted != (rejection == Rejection.NONE)) {
                throw new IllegalArgumentException("Accepted commands must have no rejection");
            }
        }

        private static CommandResult accepted(long revision) {
            return new CommandResult(true, Rejection.NONE, revision);
        }

        private static CommandResult rejected(Rejection rejection, long revision) {
            if (rejection == Rejection.NONE) {
                throw new IllegalArgumentException("Rejected command requires an exact reason");
            }
            return new CommandResult(false, rejection, revision);
        }
    }

    public enum Rejection {
        NONE,
        UNKNOWN_JOB,
        NOT_OWNER,
        STALE_REVISION,
        PROTECTED,
        INVALID_STATE,
        EXECUTION_FAILED
    }
}
