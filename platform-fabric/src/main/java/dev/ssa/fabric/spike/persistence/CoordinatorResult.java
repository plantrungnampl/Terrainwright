package dev.ssa.fabric.spike.persistence;

import java.util.Objects;

public record CoordinatorResult(CoordinatorOutcome outcome, int completedPrefixLength) {
    public CoordinatorResult {
        Objects.requireNonNull(outcome, "outcome");
        if (completedPrefixLength < 0) {
            throw new IllegalArgumentException("completedPrefixLength must not be negative");
        }
    }
}
