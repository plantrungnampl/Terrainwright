package dev.ssa.fabric.spike.persistence;

public enum CoordinatorOutcome {
    NO_ACTIVE_INTENT,
    ABORTED,
    COMMITTED,
    QUARANTINED
}
