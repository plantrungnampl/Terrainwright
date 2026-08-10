package dev.ssa.fabric.spike.persistence;

import dev.ssa.construction.spike.persistence.ObservedEvidence;
import dev.ssa.construction.spike.persistence.OperationDelta;
import dev.ssa.construction.spike.persistence.OperationIntent;

public interface OperationEvidencePort {
    ObservedEvidence observe(OperationIntent intent);

    void apply(OperationDelta delta);

    boolean isCommitted(String operationId);

    void commit(OperationIntent intent);
}
