package dev.ssa.fabric.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.ssa.fabric.debug.DebugMetrics.Counter;
import dev.ssa.fabric.debug.DebugMetrics.Timing;
import org.junit.jupiter.api.Test;

final class DebugMetricsTest {
    @Test
    void enabledMetricsPublishImmutableCountersTimingsAndRejectionReasons() {
        DebugMetrics metrics = DebugMetrics.enabled();

        metrics.increment(Counter.GENERATION_REQUEST);
        metrics.add(Counter.SCAFFOLD_BLOCK, 3);
        metrics.recordNanos(Timing.GENERATION, 40);
        metrics.recordNanos(Timing.GENERATION, 60);
        metrics.recordCandidateRejection("BLUEPRINT_INVALID");
        metrics.recordReconciliationOutcome("SUSPENDED");
        DebugMetrics.Snapshot snapshot = metrics.snapshot();

        assertEquals(1, snapshot.counter(Counter.GENERATION_REQUEST));
        assertEquals(3, snapshot.counter(Counter.SCAFFOLD_BLOCK));
        assertEquals(2, snapshot.timing(Timing.GENERATION).samples());
        assertEquals(100, snapshot.timing(Timing.GENERATION).totalNanos());
        assertEquals(60, snapshot.timing(Timing.GENERATION).maximumNanos());
        assertEquals(1, snapshot.candidateRejections().get("BLUEPRINT_INVALID"));
        assertEquals(1, snapshot.counter(Counter.RECONCILIATION));
        assertEquals(1, snapshot.reconciliationOutcomes().get("SUSPENDED"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.counters().put(Counter.CONFLICT, 1L));
    }

    @Test
    void disabledMetricsAreNoOps() {
        DebugMetrics metrics = DebugMetrics.disabled();

        metrics.increment(Counter.PATH_ATTEMPT);
        metrics.recordNanos(Timing.PATHFINDING, 50);
        metrics.recordCandidateRejection("IGNORED");

        assertEquals(DebugMetrics.Snapshot.empty(), metrics.snapshot());
    }
}
