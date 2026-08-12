package dev.ssa.construction.undo;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.construction.journal.JournalEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class UndoPlanner {
    public List<JournalEntry> reverseJournal(List<JournalEntry> journal) {
        Objects.requireNonNull(journal, "journal");
        List<JournalEntry> ordered = new ArrayList<>(journal.size());
        Set<Long> sequences = new HashSet<>();
        Set<String> entryIds = new HashSet<>();
        for (JournalEntry entry : journal) {
            Objects.requireNonNull(entry, "entry");
            if (!sequences.add(entry.sequence())) {
                throw new IllegalArgumentException(
                        "Duplicate journal sequence: " + entry.sequence());
            }
            if (!entryIds.add(entry.entryId())) {
                throw new IllegalArgumentException("Duplicate journal entry ID: " + entry.entryId());
            }
            ordered.add(entry);
        }
        ordered.sort(Comparator.comparingLong(JournalEntry::sequence).reversed());
        return List.copyOf(ordered);
    }

    public UndoDecision decide(
            JournalEntry entry,
            BlockStateSpec currentState,
            boolean permissionGranted,
            boolean currentHasBlockEntity,
            boolean previousBlockEntityDataUnavailable) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(currentState, "currentState");
        if (!permissionGranted) {
            return UndoDecision.preserve(entry, currentState, Reason.PROTECTED);
        }
        if (currentHasBlockEntity) {
            return UndoDecision.preserve(entry, currentState, Reason.BLOCK_ENTITY_PRESENT);
        }
        if (previousBlockEntityDataUnavailable) {
            return UndoDecision.preserve(
                    entry,
                    currentState,
                    Reason.PREVIOUS_BLOCK_ENTITY_DATA_UNAVAILABLE);
        }
        if (!currentState.equals(entry.writtenState())) {
            return UndoDecision.preserve(entry, currentState, Reason.EXTERNAL_EDIT);
        }
        return UndoDecision.restore(entry, currentState);
    }

    public enum Reason {
        EXACT_WRITTEN_STATE,
        EXTERNAL_EDIT,
        PROTECTED,
        BLOCK_ENTITY_PRESENT,
        PREVIOUS_BLOCK_ENTITY_DATA_UNAVAILABLE
    }

    public record UndoDecision(
            Type type,
            JournalEntry entry,
            BlockStateSpec currentState,
            Optional<BlockStateSpec> restoreState,
            Reason reason) {
        public UndoDecision {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(currentState, "currentState");
            restoreState = Objects.requireNonNull(restoreState, "restoreState");
            Objects.requireNonNull(reason, "reason");
            if ((type == Type.RESTORE_PREVIOUS) != restoreState.isPresent()) {
                throw new IllegalArgumentException("Only restore decisions may carry a restore state");
            }
        }

        private static UndoDecision restore(JournalEntry entry, BlockStateSpec currentState) {
            return new UndoDecision(
                    Type.RESTORE_PREVIOUS,
                    entry,
                    currentState,
                    Optional.of(entry.previousState()),
                    Reason.EXACT_WRITTEN_STATE);
        }

        private static UndoDecision preserve(
                JournalEntry entry,
                BlockStateSpec currentState,
                Reason reason) {
            return new UndoDecision(
                    Type.CONFLICT_PRESERVE_CURRENT,
                    entry,
                    currentState,
                    Optional.empty(),
                    reason);
        }

        public enum Type {
            RESTORE_PREVIOUS,
            CONFLICT_PRESERVE_CURRENT
        }
    }
}
