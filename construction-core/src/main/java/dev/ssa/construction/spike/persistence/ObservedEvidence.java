package dev.ssa.construction.spike.persistence;

import java.util.List;
import java.util.Objects;

public record ObservedEvidence(List<EvidenceObservation> observations) {
    public ObservedEvidence {
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
    }
}
