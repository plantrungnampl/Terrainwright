package dev.ssa.construction.spike.persistence;

public enum RecoveryAction {
    ABORT_PREPARED,
    COMPLETE_SUFFIX,
    FINALIZE_COMMIT,
    QUARANTINE_UNKNOWN_EVIDENCE
}
