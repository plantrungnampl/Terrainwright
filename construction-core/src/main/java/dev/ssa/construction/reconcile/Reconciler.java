package dev.ssa.construction.reconcile;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.construction.journal.JournalEntry;
import dev.ssa.construction.task.BuildTask;
import java.util.Objects;
import java.util.Optional;

public final class Reconciler {
    private static final BlockStateSpec AIR = new BlockStateSpec(
            NamespacedId.parse("minecraft:air"),
            java.util.Map.of());

    public Decision decide(
            BuildTask task,
            boolean durablyCompleted,
            BlockStateSpec currentState,
            Optional<JournalEntry> journalEntry) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(currentState, "currentState");
        Objects.requireNonNull(journalEntry, "journalEntry");
        if (task.operation() == dev.ssa.construction.task.TaskOperation.TEMP_PLACE
                || task.operation() == dev.ssa.construction.task.TaskOperation.TEMP_REMOVE) {
            throw new IllegalArgumentException(
                    "Temporary task reconciliation uses the scaffold provenance registry");
        }
        BlockStateSpec expectedResult = expectedResult(task);

        if (journalEntry.isEmpty()) {
            return durablyCompleted
                    ? Decision.QUARANTINE_INCONSISTENT_EVIDENCE
                    : Decision.KEEP_PENDING;
        }
        JournalEntry journal = journalEntry.orElseThrow();
        if (!journal.taskId().equals(task.id())
                || !journal.position().equals(task.position())
                || !journal.writtenState().equals(expectedResult)) {
            return Decision.QUARANTINE_INCONSISTENT_EVIDENCE;
        }
        if (durablyCompleted) {
            return Decision.PRESERVE_COMPLETED;
        }
        return currentState.equals(expectedResult)
                ? Decision.MARK_COMPLETE_WITHOUT_CONSUME
                : Decision.QUARANTINE_INCONSISTENT_EVIDENCE;
    }

    private static BlockStateSpec expectedResult(BuildTask task) {
        return task.operation().requiresMaterial()
                ? task.materialRequirement().orElseThrow().state()
                : AIR;
    }

    public enum Decision {
        KEEP_PENDING,
        MARK_COMPLETE_WITHOUT_CONSUME,
        PRESERVE_COMPLETED,
        QUARANTINE_INCONSISTENT_EVIDENCE
    }
}
