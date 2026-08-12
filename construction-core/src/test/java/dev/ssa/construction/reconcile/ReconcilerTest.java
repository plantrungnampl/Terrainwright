package dev.ssa.construction.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.construction.journal.JournalEntry;
import dev.ssa.construction.schedule.WorkZone;
import dev.ssa.construction.task.BuildTask;
import dev.ssa.construction.task.TaskOperation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ReconcilerTest {
    private static final GridPos POSITION = new GridPos(1, 64, 1);
    private static final BlockStateSpec AIR = state("minecraft:air");
    private static final BlockStateSpec PLANKS = state("minecraft:oak_planks");
    private static final BlockStateSpec DIAMOND = state("minecraft:diamond_block");
    private final Reconciler reconciler = new Reconciler();

    @Test
    void incompleteTaskAlreadyMatchingJournaledResultDoesNotConsumeAgain() {
        Reconciler.Decision decision = reconciler.decide(
                task(),
                false,
                PLANKS,
                Optional.of(journal()));

        assertEquals(Reconciler.Decision.MARK_COMPLETE_WITHOUT_CONSUME, decision);
    }

    @Test
    void restartEvidenceFailsClosedWhenJournalOrWorldDoesNotMatch() {
        assertEquals(
                Reconciler.Decision.KEEP_PENDING,
                reconciler.decide(task(), false, PLANKS, Optional.empty()));
        assertEquals(
                Reconciler.Decision.QUARANTINE_INCONSISTENT_EVIDENCE,
                reconciler.decide(task(), false, DIAMOND, Optional.of(journal())));
        assertEquals(
                Reconciler.Decision.QUARANTINE_INCONSISTENT_EVIDENCE,
                reconciler.decide(task(), true, PLANKS, Optional.empty()));
        JournalEntry wrongWrittenState = new JournalEntry(
                0,
                "entry-wrong",
                "operation-wrong",
                "task-1",
                POSITION,
                AIR,
                DIAMOND,
                1);
        assertEquals(
                Reconciler.Decision.QUARANTINE_INCONSISTENT_EVIDENCE,
                reconciler.decide(task(), false, DIAMOND, Optional.of(wrongWrittenState)));
    }

    @Test
    void durableCompletionIsPreservedAfterAValidCommitEvenIfWorldWasLaterEdited() {
        assertEquals(
                Reconciler.Decision.PRESERVE_COMPLETED,
                reconciler.decide(task(), true, DIAMOND, Optional.of(journal())));
    }

    @Test
    void reconciliationDerivesRemovalResultAndRejectsTemporaryProvenance() {
        BuildTask removal = new BuildTask(
                "terrain-remove",
                POSITION,
                TaskOperation.REMOVE,
                Optional.empty(),
                Set.of(),
                BuildPhase.SITE_PREPARATION,
                WorkZone.containing(POSITION),
                false,
                Optional.empty());
        JournalEntry removalJournal = new JournalEntry(
                0,
                "entry-remove",
                "operation-remove",
                removal.id(),
                POSITION,
                state("minecraft:stone"),
                AIR,
                1);
        BuildTask temporary = new BuildTask(
                "scaffold-place",
                POSITION,
                TaskOperation.TEMP_PLACE,
                Optional.of(new BuildTask.MaterialRequirement(MaterialRole.STRUCTURAL_WOOD, PLANKS)),
                Set.of(),
                BuildPhase.WALLS,
                WorkZone.containing(POSITION),
                false,
                Optional.empty());

        assertEquals(
                Reconciler.Decision.MARK_COMPLETE_WITHOUT_CONSUME,
                reconciler.decide(removal, false, AIR, Optional.of(removalJournal)));
        assertThrows(
                IllegalArgumentException.class,
                () -> reconciler.decide(temporary, false, PLANKS, Optional.of(journal())));
    }

    @Test
    void buildJobRecordsCommittedProgressOnceAndKeepsCollectionsImmutable() {
        BuildJob initial = buildingJob();
        JournalEntry committed = journalAtRevision(initial.revision() + 1);
        BuildJob completed = initial.recordCompletion("task-1", committed);

        assertEquals(Set.of("task-1"), completed.completedTaskIds());
        assertEquals(List.of(committed), completed.blockJournal());
        assertEquals(4, completed.revision());
        assertSame(completed, completed.recordCompletion("task-1", committed));
        BuildJob terminal = completed.transitionTo(BuildJobState.COMPLETED);
        assertSame(terminal, terminal.recordCompletion("task-1", committed));
        BuildJob quarantined = completed.recordDiagnosticAndTransition(
                new BuildJob.Diagnostic(
                        "RECOVERY_EVIDENCE_CONFLICT",
                        BuildJob.Severity.CRITICAL,
                        "Restart evidence is inconsistent",
                        false,
                        completed.revision() + 1,
                        Optional.of(POSITION)),
                BuildJobState.QUARANTINED_RECOVERY);
        assertSame(quarantined, quarantined.recordCompletion("task-1", committed));
        assertThrows(
                UnsupportedOperationException.class,
                () -> completed.completedTaskIds().add("foreign"));
    }

    @Test
    void buildJobRecordsEveryMemberOfOneCommittedOperationAtOneRevision() {
        BuildJob building = buildingJob();
        JournalEntry first = journalAtRevision(building.revision() + 1);
        JournalEntry second = new JournalEntry(
                1,
                "entry-2",
                first.operationId(),
                "task-2",
                new GridPos(1, 65, 1),
                AIR,
                PLANKS,
                building.revision() + 1);

        BuildJob completed = building.recordCompletions(List.of(first, second));

        assertEquals(Set.of("task-1", "task-2"), completed.completedTaskIds());
        assertEquals(List.of(first, second), completed.blockJournal());
        assertEquals(4, completed.revision());
        assertSame(completed, completed.recordCompletions(List.of(first, second)));
        assertThrows(
                IllegalStateException.class,
                () -> completed.recordCompletions(List.of(first)));
    }

    @Test
    void terminalAndQuarantinedJobsCannotRecordNewConstructionProgress() {
        BuildJob quarantined = job().recordDiagnosticAndTransition(
                new BuildJob.Diagnostic(
                        "RECOVERY_EVIDENCE_CONFLICT",
                        BuildJob.Severity.CRITICAL,
                        "Restart evidence is inconsistent",
                        false,
                        1,
                        Optional.of(POSITION)),
                BuildJobState.QUARANTINED_RECOVERY);

        assertThrows(
                IllegalStateException.class,
                () -> job().recordCompletion("task-1", journal()));
        assertThrows(
                IllegalStateException.class,
                () -> job().transitionTo(BuildJobState.COMPLETED)
                        .recordCompletion("task-1", journal()));
        assertThrows(
                IllegalStateException.class,
                () -> quarantined.recordCompletion("task-1", journal()));
    }

    @Test
    void buildJobRejectsIllegalTerminalTransitionsAndInvalidBlueprintHash() {
        BuildJob completed = job().transitionTo(BuildJobState.COMPLETED);

        assertThrows(
                IllegalStateException.class,
                () -> completed.transitionTo(BuildJobState.BUILDING));
        assertThrows(
                IllegalStateException.class,
                () -> job().transitionTo(BuildJobState.UNDO_COMPLETED));
        assertThrows(
                IllegalStateException.class,
                () -> job().transitionTo(BuildJobState.NO_BUILDER));
        BuildJob quarantined = job().recordDiagnosticAndTransition(
                new BuildJob.Diagnostic(
                        "RECOVERY_EVIDENCE_CONFLICT",
                        BuildJob.Severity.CRITICAL,
                        "Restart evidence is inconsistent",
                        false,
                        1,
                        Optional.of(POSITION)),
                BuildJobState.QUARANTINED_RECOVERY);
        assertThrows(
                IllegalStateException.class,
                () -> quarantined.transitionTo(BuildJobState.PAUSED));
        assertThrows(
                IllegalStateException.class,
                () -> quarantined.transitionTo(BuildJobState.STOPPING));
        BuildJob pausedForMaterial = new BuildJob(
                "job-paused",
                "owner-1",
                "hut-1",
                "blueprint-1",
                "0123456789abcdef",
                NamespacedId.parse("minecraft:overworld"),
                POSITION,
                0,
                BuildJobState.PAUSED_MISSING_MATERIAL,
                Optional.of(BuildPhase.FOUNDATION),
                Set.of(),
                List.of(),
                List.of(),
                7,
                BuildJob.CURRENT_FORMAT_VERSION);
        assertEquals(BuildJobState.PAUSED_MISSING_MATERIAL, pausedForMaterial.state());
        assertThrows(
                IllegalArgumentException.class,
                () -> BuildJob.create(
                        "job-1",
                        "owner-1",
                        "hut-1",
                        "blueprint-1",
                        "not-a-hash",
                        NamespacedId.parse("minecraft:overworld"),
                        POSITION,
                        0));
    }

    @Test
    void jobStatesMatchTheCanonicalPersistedContract() {
        assertEquals(
                List.of(
                        "IDLE", "PREPARING", "WAIT_MATERIAL", "FETCHING_MATERIAL", "NAVIGATING",
                        "BUILDING", "PAUSED", "PAUSED_MISSING_MATERIAL", "PAUSED_NO_CHEST",
                        "PAUSED_BLOCKED", "PAUSED_CONFLICT", "PAUSED_PROTECTED",
                        "SUSPENDED_CHUNK_UNLOADED", "NO_BUILDER", "ORPHANED", "STOPPING",
                        "STOPPED", "UNDOING", "UNDO_COMPLETED", "COMPLETED",
                        "QUARANTINED_RECOVERY"),
                List.of(BuildJobState.values()).stream().map(Enum::name).toList());
    }

    private static BuildJob job() {
        return BuildJob.create(
                "job-1",
                "owner-1",
                "hut-1",
                "blueprint-1",
                "0123456789abcdef",
                NamespacedId.parse("minecraft:overworld"),
                POSITION,
                0);
    }

    private static BuildJob buildingJob() {
        return job()
                .transitionTo(BuildJobState.PREPARING)
                .transitionTo(BuildJobState.NAVIGATING)
                .transitionTo(BuildJobState.BUILDING);
    }

    private static BuildTask task() {
        return new BuildTask(
                "task-1",
                POSITION,
                TaskOperation.PLACE,
                Optional.of(new BuildTask.MaterialRequirement(MaterialRole.STRUCTURAL_WOOD, PLANKS)),
                Set.of(),
                BuildPhase.WALLS,
                WorkZone.containing(POSITION),
                false,
                Optional.empty());
    }

    private static JournalEntry journal() {
        return journalAtRevision(1);
    }

    private static JournalEntry journalAtRevision(long revision) {
        return new JournalEntry(
                0,
                "entry-1",
                "operation-1",
                "task-1",
                POSITION,
                AIR,
                PLANKS,
                revision);
    }

    private static BlockStateSpec state(String id) {
        return new BlockStateSpec(NamespacedId.parse(id), Map.of());
    }
}
