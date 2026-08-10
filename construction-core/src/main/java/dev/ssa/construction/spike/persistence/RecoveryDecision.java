package dev.ssa.construction.spike.persistence;

import java.util.Objects;

public record RecoveryDecision(RecoveryAction action, int completedPrefixLength) {
    public RecoveryDecision {
        Objects.requireNonNull(action, "action");
        if (completedPrefixLength < 0) {
            throw new IllegalArgumentException("completedPrefixLength must not be negative");
        }
    }

    public static RecoveryDecision abortPrepared() {
        return new RecoveryDecision(RecoveryAction.ABORT_PREPARED, 0);
    }

    public static RecoveryDecision completeSuffix(int completedPrefixLength) {
        if (completedPrefixLength < 1) {
            throw new IllegalArgumentException("a completed prefix must contain at least one delta");
        }
        return new RecoveryDecision(RecoveryAction.COMPLETE_SUFFIX, completedPrefixLength);
    }

    public static RecoveryDecision finalizeCommit(int deltaCount) {
        return new RecoveryDecision(RecoveryAction.FINALIZE_COMMIT, deltaCount);
    }

    public static RecoveryDecision quarantine() {
        return new RecoveryDecision(RecoveryAction.QUARANTINE_UNKNOWN_EVIDENCE, 0);
    }
}
