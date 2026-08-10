package dev.ssa.fabric.spike.restart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.fabric.spike.persistence.CoordinatorOutcome;
import dev.ssa.fabric.spike.persistence.DurableAcknowledgement;
import dev.ssa.fabric.spike.persistence.OperationBoundaryListener;
import dev.ssa.construction.spike.persistence.OperationStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class S5RestartScenarioTest {
    @Test
    void everyBoundaryNameRoundTripsAndMapsToExactlyOneInjectionCallback() throws Exception {
        Set<String> names = new HashSet<>();
        for (S5CrashBoundary boundary : S5CrashBoundary.values()) {
            assertTrue(names.add(boundary.externalName()));
            assertEquals(boundary, S5CrashBoundary.fromExternalName(boundary.externalName()));

            List<S5CrashBoundary> reached = new ArrayList<>();
            S5RestartScenario scenario = new S5RestartScenario(boundary, reached::add);
            OperationBoundaryListener listener = scenario.listener();
            listener.beforePrepare();
            scenario.appendProbe().afterWriteBeforeForce(Path.of("intent.wal"));
            listener.afterPrepared(new DurableAcknowledgement(OperationStatus.PREPARED, 1, "test"));
            listener.afterDelta(0);
            listener.afterDelta(1);
            listener.afterDelta(2);
            listener.afterAllDeltas();
            listener.afterJournalCommit();
            listener.afterCommit();
            listener.afterClear();

            int expectedCallbacks = boundary == S5CrashBoundary.FOREIGN_EVIDENCE ? 0 : 1;
            assertEquals(expectedCallbacks, reached.size(), boundary.externalName());
            if (expectedCallbacks == 1) {
                assertEquals(boundary, reached.getFirst());
            }
        }
    }

    @Test
    void recoveryContractsCoverAllS4WindowsAndForeignQuarantine() {
        assertEquals(
                Set.of(CoordinatorOutcome.NO_ACTIVE_INTENT),
                S5CrashBoundary.BEFORE_PREPARED_APPEND.expectedFirstOutcomes());
        assertEquals(
                Set.of(CoordinatorOutcome.NO_ACTIVE_INTENT, CoordinatorOutcome.ABORTED),
                S5CrashBoundary.AFTER_APPEND_BEFORE_FSYNC_ACK.expectedFirstOutcomes());
        assertEquals(
                Set.of(CoordinatorOutcome.ABORTED),
                S5CrashBoundary.AFTER_DURABLE_PREPARED.expectedFirstOutcomes());
        assertEquals(
                Set.of(CoordinatorOutcome.NO_ACTIVE_INTENT),
                S5CrashBoundary.AFTER_CLEAR_CHECKPOINT.expectedFirstOutcomes());
        assertEquals(
                Set.of(CoordinatorOutcome.QUARANTINED),
                S5CrashBoundary.FOREIGN_EVIDENCE.expectedFirstOutcomes());

        for (S5CrashBoundary boundary : S5CrashBoundary.values()) {
            if (boundary.isAppliedWindow()) {
                assertEquals(Set.of(CoordinatorOutcome.COMMITTED), boundary.expectedFirstOutcomes());
                assertTrue(boundary.expectsCommittedEvidence());
            }
            if (boundary == S5CrashBoundary.FOREIGN_EVIDENCE) {
                assertFalse(boundary.allowsScheduling());
                assertEquals(CoordinatorOutcome.QUARANTINED, boundary.expectedSecondOutcome());
            } else {
                assertTrue(boundary.allowsScheduling());
                assertEquals(CoordinatorOutcome.NO_ACTIVE_INTENT, boundary.expectedSecondOutcome());
            }
        }
    }

    @Test
    void fixtureAndIntentCarryAllRestartIdentities() {
        String fixtureId = "after-delta-1";
        String worldIdentity = "world-identity-1";
        RestartFixture fixture = S5RestartScenario.fixture(fixtureId, worldIdentity);
        var intent = S5RestartScenario.intent(fixtureId);

        assertEquals(fixtureId, fixture.fixtureId());
        assertEquals(worldIdentity, fixture.worldIdentity());
        assertEquals(fixture.buildJobId(), intent.jobId());
        assertEquals(fixture.jobRevision(), intent.jobRevision());
        assertEquals(fixture.builderId(), intent.deltas().getFirst().evidenceKey()
                .substring("inventory:".length(), intent.deltas().getFirst().evidenceKey().indexOf("@revision=")));
        assertEquals(3, intent.deltas().size());
    }
}
