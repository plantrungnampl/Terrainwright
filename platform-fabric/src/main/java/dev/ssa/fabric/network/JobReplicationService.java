package dev.ssa.fabric.network;

import dev.ssa.common.permission.PermissionPort;
import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
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

    public CompletableFuture<CommandResult> stop(
            String jobId,
            UUID requester,
            long expectedRevision) {
        return command(jobId, requester, expectedRevision, BuildJobState.STOPPING, executor::stop);
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
        if (!permissions.canModify(requester, current.origin())) {
            return rejected(Rejection.PROTECTED, current.revision());
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
            return CompletableFuture.failedFuture(failure);
        }
        return execution.thenApply(ignored -> CommandResult.accepted(next.revision()));
    }

    private static CompletableFuture<CommandResult> rejected(Rejection rejection, long revision) {
        return CompletableFuture.completedFuture(CommandResult.rejected(rejection, revision));
    }

    public interface CommandExecutor {
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
        INVALID_STATE
    }
}
