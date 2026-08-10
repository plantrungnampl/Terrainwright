package dev.ssa.fabric.spike.persistence;

public interface OperationBoundaryListener {
    OperationBoundaryListener NONE = new OperationBoundaryListener() { };

    default void beforePrepare() {
    }

    default void afterPrepared(DurableAcknowledgement acknowledgement) {
    }

    default void afterDelta(int deltaIndex) {
    }

    default void afterAllDeltas() {
    }

    default void afterJournalCommit() {
    }

    default void afterCommit() {
    }

    default void afterClear() {
    }
}
