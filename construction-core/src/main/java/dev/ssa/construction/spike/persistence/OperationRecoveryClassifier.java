package dev.ssa.construction.spike.persistence;

import java.util.List;
import java.util.Objects;

public final class OperationRecoveryClassifier {
    public RecoveryDecision classify(OperationIntent intent, ObservedEvidence evidence) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(evidence, "evidence");
        List<OperationDelta> deltas = intent.deltas();
        List<EvidenceObservation> observations = evidence.observations();
        if (deltas.size() != observations.size()) {
            return RecoveryDecision.quarantine();
        }

        int completedPrefix = 0;
        boolean reachedBeforeSuffix = false;
        for (int index = 0; index < deltas.size(); index++) {
            OperationDelta delta = deltas.get(index);
            EvidenceObservation observation = observations.get(index);
            if (!delta.evidenceKey().equals(observation.evidenceKey())) {
                return RecoveryDecision.quarantine();
            }
            if (delta.after().equals(observation.value())) {
                if (reachedBeforeSuffix) {
                    return RecoveryDecision.quarantine();
                }
                completedPrefix++;
            } else if (delta.before().equals(observation.value())) {
                reachedBeforeSuffix = true;
            } else {
                return RecoveryDecision.quarantine();
            }
        }

        if (intent.status() == OperationStatus.COMMITTED) {
            return completedPrefix == deltas.size()
                    ? RecoveryDecision.finalizeCommit(deltas.size())
                    : RecoveryDecision.quarantine();
        }
        if (completedPrefix == 0) {
            return RecoveryDecision.abortPrepared();
        }
        if (completedPrefix == deltas.size()) {
            return RecoveryDecision.finalizeCommit(deltas.size());
        }
        return RecoveryDecision.completeSuffix(completedPrefix);
    }
}
