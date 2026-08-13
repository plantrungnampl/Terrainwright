package dev.ssa.fabric.construction;

import dev.ssa.construction.operation.ObservedEvidence;
import dev.ssa.construction.operation.OperationDelta;
import dev.ssa.construction.operation.OperationIntent;
import java.util.concurrent.CompletableFuture;

public interface OperationEvidencePort {
    default void validate(OperationIntent intent) {
    }

    ObservedEvidence observe(OperationIntent intent);

    void apply(OperationDelta delta);

    boolean isCommitted(String operationId);

    CompletableFuture<Void> commit(OperationIntent intent);
}
