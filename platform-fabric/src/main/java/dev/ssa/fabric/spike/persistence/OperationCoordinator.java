package dev.ssa.fabric.spike.persistence;

import dev.ssa.construction.spike.persistence.OperationIntent;
import dev.ssa.construction.spike.persistence.OperationRecoveryClassifier;
import dev.ssa.construction.spike.persistence.OperationStatus;
import dev.ssa.construction.spike.persistence.RecoveryAction;
import dev.ssa.construction.spike.persistence.RecoveryDecision;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class OperationCoordinator {
    private final FileOperationIntentStore store;
    private final Executor serverExecutor;
    private final OperationBoundaryListener listener;
    private final OperationRecoveryClassifier classifier = new OperationRecoveryClassifier();

    public OperationCoordinator(
            FileOperationIntentStore store,
            Executor serverExecutor,
            OperationBoundaryListener listener) {
        this.store = Objects.requireNonNull(store, "store");
        this.serverExecutor = Objects.requireNonNull(serverExecutor, "serverExecutor");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public CompletableFuture<CoordinatorResult> execute(OperationIntent intent, OperationEvidencePort evidence) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(evidence, "evidence");
        return CompletableFuture.runAsync(() -> {
                    listener.beforePrepare();
                    RecoveryDecision before = classifier.classify(intent, evidence.observe(intent));
                    if (before.action() != RecoveryAction.ABORT_PREPARED) {
                        throw new IllegalStateException("operation evidence is not exact all-before state");
                    }
                }, serverExecutor)
                .thenCompose(ignored -> store.prepare(intent))
                .thenCompose(acknowledgement -> CompletableFuture.runAsync(() -> {
                    listener.afterPrepared(acknowledgement);
                    for (int index = 0; index < intent.deltas().size(); index++) {
                        evidence.apply(intent.deltas().get(index));
                        listener.afterDelta(index);
                    }
                    listener.afterAllDeltas();
                    RecoveryDecision after = classifier.classify(intent, evidence.observe(intent));
                    if (after.action() != RecoveryAction.FINALIZE_COMMIT) {
                        throw new IllegalStateException("operation did not produce exact all-after evidence");
                    }
                    evidence.commit(intent);
                    listener.afterJournalCommit();
                }, serverExecutor))
                .thenCompose(ignored -> store.transition(intent.operationId(), OperationStatus.COMMITTED))
                .thenCompose(ignored -> CompletableFuture.runAsync(listener::afterCommit, serverExecutor))
                .thenCompose(ignored -> store.clear(intent.operationId()))
                .thenApplyAsync(ignored -> {
                    listener.afterClear();
                    return new CoordinatorResult(CoordinatorOutcome.COMMITTED, intent.deltas().size());
                }, serverExecutor);
    }

    public CompletableFuture<CoordinatorResult> recover(OperationEvidencePort evidence) {
        Objects.requireNonNull(evidence, "evidence");
        return store.loadActive().thenCompose(active -> recover(active, evidence));
    }

    private CompletableFuture<CoordinatorResult> recover(
            Optional<OperationIntent> active,
            OperationEvidencePort evidence) {
        if (active.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new CoordinatorResult(CoordinatorOutcome.NO_ACTIVE_INTENT, 0));
        }
        OperationIntent intent = active.orElseThrow();
        return CompletableFuture.supplyAsync(
                        () -> classifier.classify(intent, evidence.observe(intent)),
                        serverExecutor)
                .thenCompose(decision -> recover(intent, evidence, decision));
    }

    private CompletableFuture<CoordinatorResult> recover(
            OperationIntent intent,
            OperationEvidencePort evidence,
            RecoveryDecision decision) {
        return switch (decision.action()) {
            case ABORT_PREPARED -> intent.status() == OperationStatus.ABORTED
                    ? clear(intent, new CoordinatorResult(CoordinatorOutcome.ABORTED, 0))
                    : terminalAndClear(
                            intent,
                            OperationStatus.ABORTED,
                            new CoordinatorResult(CoordinatorOutcome.ABORTED, 0));
            case COMPLETE_SUFFIX -> completeSuffix(intent, evidence, decision.completedPrefixLength());
            case FINALIZE_COMMIT -> finalizeCommit(intent, evidence, decision.completedPrefixLength());
            case QUARANTINE_UNKNOWN_EVIDENCE -> intent.status() == OperationStatus.QUARANTINED
                    ? CompletableFuture.completedFuture(
                            new CoordinatorResult(CoordinatorOutcome.QUARANTINED, 0))
                    : store.transition(intent.operationId(), OperationStatus.QUARANTINED)
                            .thenApply(ignored -> new CoordinatorResult(CoordinatorOutcome.QUARANTINED, 0));
        };
    }

    private CompletableFuture<CoordinatorResult> completeSuffix(
            OperationIntent intent,
            OperationEvidencePort evidence,
            int completedPrefixLength) {
        return CompletableFuture.runAsync(() -> {
                    for (int index = completedPrefixLength; index < intent.deltas().size(); index++) {
                        evidence.apply(intent.deltas().get(index));
                    }
                    RecoveryDecision verification = classifier.classify(intent, evidence.observe(intent));
                    if (verification.action() != RecoveryAction.FINALIZE_COMMIT) {
                        throw new IllegalStateException("completed suffix did not produce exact all-after evidence");
                    }
                    if (!evidence.isCommitted(intent.operationId())) {
                        evidence.commit(intent);
                    }
                }, serverExecutor)
                .thenCompose(ignored -> terminalAndClear(
                        intent,
                        OperationStatus.COMMITTED,
                        new CoordinatorResult(CoordinatorOutcome.COMMITTED, intent.deltas().size())));
    }

    private CompletableFuture<CoordinatorResult> finalizeCommit(
            OperationIntent intent,
            OperationEvidencePort evidence,
            int completedPrefixLength) {
        return CompletableFuture.runAsync(() -> {
                    if (!evidence.isCommitted(intent.operationId())) {
                        evidence.commit(intent);
                    }
                }, serverExecutor)
                .thenCompose(ignored -> intent.status() == OperationStatus.COMMITTED
                        ? clear(intent, new CoordinatorResult(CoordinatorOutcome.COMMITTED, completedPrefixLength))
                        : terminalAndClear(
                                intent,
                                OperationStatus.COMMITTED,
                                new CoordinatorResult(CoordinatorOutcome.COMMITTED, completedPrefixLength)));
    }

    private CompletableFuture<CoordinatorResult> terminalAndClear(
            OperationIntent intent,
            OperationStatus terminalStatus,
            CoordinatorResult result) {
        return store.transition(intent.operationId(), terminalStatus)
                .thenCompose(ignored -> clear(intent, result));
    }

    private CompletableFuture<CoordinatorResult> clear(OperationIntent intent, CoordinatorResult result) {
        return store.clear(intent.operationId()).thenApply(ignored -> result);
    }
}
