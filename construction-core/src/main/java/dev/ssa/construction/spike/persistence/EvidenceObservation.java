package dev.ssa.construction.spike.persistence;

import java.util.Objects;

public record EvidenceObservation(String evidenceKey, EvidenceSnapshot value) {
    public EvidenceObservation {
        Objects.requireNonNull(evidenceKey, "evidenceKey");
        Objects.requireNonNull(value, "value");
        if (evidenceKey.isBlank()) {
            throw new IllegalArgumentException("evidenceKey must not be blank");
        }
    }
}
