package dev.ssa.fabric.job;

import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.job.BuildJob.Diagnostic;
import dev.ssa.construction.job.BuildJob.Severity;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.construction.operation.OperationIntent;
import dev.ssa.construction.operation.OperationStatus;
import dev.ssa.fabric.lifecycle.BuilderLifecycleTombstone;
import dev.ssa.fabric.persistence.OperationIntentStore;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Durable lifecycle gate used before a loaded Builder may enter OperationIntent recovery. */
public final class JobRecoveryService {
    private final ServerBuildJobRepository repository;

    public JobRecoveryService(ServerBuildJobRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public void observeDeath(UUID builderId, long observedGameTime) {
        observeLoss(builderId, observedGameTime, BuilderLifecycleTombstone.Cause.DEATH);
    }

    public void observeRemoval(UUID builderId, long observedGameTime) {
        observeLoss(builderId, observedGameTime, BuilderLifecycleTombstone.Cause.REMOVAL);
    }

    public void quarantineUnresolvedIntent(UUID builderId) {
        List<ServerBuildJobRepository.HutState> owners = owners(builderId);
        if (owners.isEmpty()) {
            return;
        }
        owners.getFirst().activeJobId().flatMap(repository::findJob).ifPresent(job -> {
            if (job.state().canTransitionTo(BuildJobState.QUARANTINED_RECOVERY)) {
                repository.saveJob(job.recordDiagnosticAndTransition(
                        new Diagnostic(
                                "BUILDER_INTENT_QUARANTINED",
                                Severity.ERROR,
                                "Lost Builder has an unresolved OperationIntent; privileged recovery is required",
                                true,
                                job.revision() + 1,
                                Optional.empty()),
                        BuildJobState.QUARANTINED_RECOVERY));
            }
        });
    }

    public CompletableFuture<IntentOutcome> quarantineLostBuilderIntent(
            UUID builderId,
            OperationIntentStore store,
            Executor serverExecutor) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(serverExecutor, "serverExecutor");
        return store.loadActive().thenCompose(active -> {
            if (active.isEmpty()) {
                return CompletableFuture.completedFuture(IntentOutcome.NO_ACTIVE_INTENT);
            }
            OperationIntent intent = active.orElseThrow();
            CompletableFuture<?> durableQuarantine = intent.status() == OperationStatus.QUARANTINED
                    ? CompletableFuture.completedFuture(null)
                    : store.transition(intent.operationId(), OperationStatus.QUARANTINED);
            return durableQuarantine.thenApplyAsync(ignored -> {
                quarantineUnresolvedIntent(builderId);
                return IntentOutcome.QUARANTINED;
            }, serverExecutor);
        });
    }

    public Reconciliation reconcileLoadedBuilder(UUID builderId) {
        List<ServerBuildJobRepository.HutState> owners = owners(builderId);
        if (owners.isEmpty()) {
            return Reconciliation.of(Outcome.UNLINKED, Optional.empty(), Optional.empty());
        }
        ServerBuildJobRepository.HutState hut = owners.getFirst();
        Optional<String> jobId = hut.activeJobId();
        BuilderLifecycleTombstone lifecycle = hut.builderLifecycle().orElseThrow();
        if (jobId.isEmpty()) {
            return Reconciliation.of(Outcome.NO_ACTIVE_JOB, Optional.of(hut.hutId()), Optional.empty());
        }
        BuildJob job = repository.findJob(jobId.orElseThrow()).orElse(null);
        if (job == null || repository.findPlan(jobId.orElseThrow()).isEmpty()) {
            return Reconciliation.of(Outcome.INVALID_DURABLE_STATE, Optional.of(hut.hutId()), jobId);
        }
        if (job.state() == BuildJobState.QUARANTINED_RECOVERY) {
            return Reconciliation.of(Outcome.QUARANTINED, Optional.of(hut.hutId()), jobId);
        }
        if (job.state() == BuildJobState.STOPPING) {
            return Reconciliation.of(Outcome.STOPPING, Optional.of(hut.hutId()), jobId);
        }
        if (job.state() == BuildJobState.STOPPED) {
            return Reconciliation.of(Outcome.STOPPED, Optional.of(hut.hutId()), jobId);
        }
        if (job.state() == BuildJobState.UNDOING) {
            return Reconciliation.of(Outcome.UNDOING, Optional.of(hut.hutId()), jobId);
        }
        if (job.state() == BuildJobState.UNDO_COMPLETED) {
            return Reconciliation.of(Outcome.UNDO_COMPLETED, Optional.of(hut.hutId()), jobId);
        }
        if (lifecycle.isTombstoned()) {
            return Reconciliation.of(Outcome.NO_BUILDER, Optional.of(hut.hutId()), jobId);
        }
        if (lifecycle.status() == BuilderLifecycleTombstone.Status.SUSPENDED) {
            return Reconciliation.of(Outcome.SUSPENDED, Optional.of(hut.hutId()), jobId);
        }
        if (job.state() == BuildJobState.NO_BUILDER) {
            return Reconciliation.of(Outcome.NO_BUILDER, Optional.of(hut.hutId()), jobId);
        }
        if (job.state() == BuildJobState.ORPHANED) {
            return Reconciliation.of(Outcome.ORPHANED, Optional.of(hut.hutId()), jobId);
        }
        return Reconciliation.of(
                Outcome.READY_FOR_OPERATION_RECOVERY,
                Optional.of(hut.hutId()),
                jobId);
    }

    private void observeLoss(
            UUID builderId,
            long observedGameTime,
            BuilderLifecycleTombstone.Cause cause) {
        List<ServerBuildJobRepository.HutState> owners = owners(builderId);
        if (owners.isEmpty()) {
            return;
        }
        ServerBuildJobRepository.HutState hut = owners.getFirst();
        BuilderLifecycleTombstone current = hut.builderLifecycle().orElseThrow();
        BuilderLifecycleTombstone tombstoned = cause == BuilderLifecycleTombstone.Cause.DEATH
                ? current.observeDeath(observedGameTime)
                : current.observeRemoval(observedGameTime);
        if (tombstoned == current) {
            return;
        }
        hut.activeJobId().flatMap(repository::findJob).ifPresent(job -> {
            if (job.state() == BuildJobState.IDLE) {
                job = job.transitionTo(BuildJobState.PREPARING);
            }
            if (job.state().canTransitionTo(BuildJobState.NO_BUILDER)) {
                repository.saveJob(job.transitionTo(BuildJobState.NO_BUILDER));
            }
        });
        repository.saveHutState(new ServerBuildJobRepository.HutState(
                hut.hutId(),
                hut.ownerId(),
                hut.activeJobId(),
                hut.containerBinding(),
                Optional.of(tombstoned),
                hut.revision() + 1));
    }

    private List<ServerBuildJobRepository.HutState> owners(UUID builderId) {
        Objects.requireNonNull(builderId, "builderId");
        List<ServerBuildJobRepository.HutState> owners = repository.huts().values().stream()
                .filter(hut -> hut.builderLifecycle().stream()
                        .anyMatch(lifecycle -> lifecycle.builderId().equals(builderId)))
                .toList();
        if (owners.size() > 1) {
            throw new IllegalStateException("One Builder identity is linked to multiple Huts");
        }
        return owners;
    }

    public record Reconciliation(
            Outcome outcome,
            Optional<UUID> hutId,
            Optional<String> jobId) {
        public Reconciliation {
            Objects.requireNonNull(outcome, "outcome");
            hutId = Objects.requireNonNull(hutId, "hutId");
            jobId = Objects.requireNonNull(jobId, "jobId");
        }

        private static Reconciliation of(
                Outcome outcome,
                Optional<UUID> hutId,
                Optional<String> jobId) {
            return new Reconciliation(outcome, hutId, jobId);
        }
    }

    public enum Outcome {
        READY_FOR_OPERATION_RECOVERY,
        STOPPING,
        STOPPED,
        UNDOING,
        UNDO_COMPLETED,
        SUSPENDED,
        NO_BUILDER,
        ORPHANED,
        NO_ACTIVE_JOB,
        INVALID_DURABLE_STATE,
        QUARANTINED,
        UNLINKED
    }

    public enum IntentOutcome {
        NO_ACTIVE_INTENT,
        QUARANTINED
    }
}
