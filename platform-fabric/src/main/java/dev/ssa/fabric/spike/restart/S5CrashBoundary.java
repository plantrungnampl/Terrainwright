package dev.ssa.fabric.spike.restart;

import dev.ssa.fabric.construction.CoordinatorOutcome;
import java.util.Arrays;
import java.util.Set;

public enum S5CrashBoundary {
    BEFORE_PREPARED_APPEND(
            "before_prepared_append",
            Set.of(CoordinatorOutcome.NO_ACTIVE_INTENT),
            false,
            true),
    AFTER_APPEND_BEFORE_FSYNC_ACK(
            "after_append_before_fsync_ack",
            Set.of(CoordinatorOutcome.NO_ACTIVE_INTENT, CoordinatorOutcome.ABORTED),
            false,
            true),
    AFTER_DURABLE_PREPARED(
            "after_durable_prepared",
            Set.of(CoordinatorOutcome.ABORTED),
            false,
            true),
    AFTER_DELTA_1("after_delta_1", Set.of(CoordinatorOutcome.COMMITTED), true, true),
    AFTER_DELTA_2("after_delta_2", Set.of(CoordinatorOutcome.COMMITTED), true, true),
    AFTER_DELTA_3("after_delta_3", Set.of(CoordinatorOutcome.COMMITTED), true, true),
    AFTER_ALL_DELTAS_BEFORE_COMMIT(
            "after_all_deltas_before_commit", Set.of(CoordinatorOutcome.COMMITTED), true, true),
    AFTER_JOURNAL_COMMIT_BEFORE_WAL_COMMIT(
            "after_journal_commit_before_wal_commit", Set.of(CoordinatorOutcome.COMMITTED), true, true),
    AFTER_WAL_COMMIT_BEFORE_CLEAR(
            "after_wal_commit_before_clear", Set.of(CoordinatorOutcome.COMMITTED), true, true),
    AFTER_CLEAR_CHECKPOINT(
            "after_clear_checkpoint", Set.of(CoordinatorOutcome.NO_ACTIVE_INTENT), false, true),
    FOREIGN_EVIDENCE(
            "foreign_evidence", Set.of(CoordinatorOutcome.QUARANTINED), false, false);

    private final String externalName;
    private final Set<CoordinatorOutcome> expectedFirstOutcomes;
    private final boolean appliedWindow;
    private final boolean allowsScheduling;

    S5CrashBoundary(
            String externalName,
            Set<CoordinatorOutcome> expectedFirstOutcomes,
            boolean appliedWindow,
            boolean allowsScheduling) {
        this.externalName = externalName;
        this.expectedFirstOutcomes = Set.copyOf(expectedFirstOutcomes);
        this.appliedWindow = appliedWindow;
        this.allowsScheduling = allowsScheduling;
    }

    public String externalName() {
        return externalName;
    }

    public Set<CoordinatorOutcome> expectedFirstOutcomes() {
        return expectedFirstOutcomes;
    }

    public boolean isAppliedWindow() {
        return appliedWindow;
    }

    public boolean expectsCommittedEvidence() {
        return appliedWindow || this == AFTER_CLEAR_CHECKPOINT;
    }

    public boolean allowsScheduling() {
        return allowsScheduling;
    }

    public CoordinatorOutcome expectedSecondOutcome() {
        return this == FOREIGN_EVIDENCE
                ? CoordinatorOutcome.QUARANTINED
                : CoordinatorOutcome.NO_ACTIVE_INTENT;
    }

    public static S5CrashBoundary fromExternalName(String name) {
        return Arrays.stream(values())
                .filter(boundary -> boundary.externalName.equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown S5 crash boundary: " + name));
    }
}
