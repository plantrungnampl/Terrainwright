package dev.ssa.fabric.client.job;

import dev.ssa.fabric.network.JobPayloads.JobDelta;
import dev.ssa.fabric.network.JobPayloads.JobCommandResult;
import dev.ssa.fabric.network.JobPayloads.JobSnapshot;
import java.util.Objects;
import java.util.Optional;

/** Read-only replicated job state. All authoritative mutations remain server commands. */
public final class JobClientState {
    private JobSnapshot snapshot;
    private JobCommandResult lastCommandResult;

    public boolean accept(JobSnapshot incoming) {
        Objects.requireNonNull(incoming, "incoming");
        if (snapshot != null
                && snapshot.jobId().equals(incoming.jobId())
                && incoming.revision() <= snapshot.revision()) {
            return incoming.equals(snapshot);
        }
        snapshot = incoming;
        lastCommandResult = null;
        return true;
    }

    public boolean accept(JobDelta incoming) {
        Objects.requireNonNull(incoming, "incoming");
        if (snapshot == null
                || !snapshot.jobId().equals(incoming.jobId())
                || !snapshot.hutId().equals(incoming.hutId())
                || !snapshot.ownerId().equals(incoming.ownerId())
                || incoming.revision() <= snapshot.revision()) {
            return false;
        }
        snapshot = incoming.snapshot();
        lastCommandResult = null;
        return true;
    }

    public boolean accept(JobCommandResult incoming) {
        Objects.requireNonNull(incoming, "incoming");
        if (snapshot == null || !snapshot.jobId().equals(incoming.jobId())) {
            return false;
        }
        lastCommandResult = incoming;
        return true;
    }

    public Optional<JobSnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public Optional<JobCommandResult> lastCommandResult() {
        return Optional.ofNullable(lastCommandResult);
    }

    public void clear() {
        snapshot = null;
        lastCommandResult = null;
    }
}
