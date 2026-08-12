package dev.ssa.construction.undo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.construction.conflict.ConflictClassifier;
import dev.ssa.construction.journal.JournalEntry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class UndoPlannerTest {
    private static final GridPos POSITION = new GridPos(4, 70, -2);
    private static final BlockStateSpec GRASS = state("minecraft:grass_block");
    private static final BlockStateSpec PLANKS = state("minecraft:oak_planks");
    private static final BlockStateSpec DIAMOND = state("minecraft:diamond_block");
    private final UndoPlanner planner = new UndoPlanner();

    @Test
    void undoPreservesExternalEdit() {
        UndoPlanner.UndoDecision decision = planner.decide(
                entry(7, GRASS, PLANKS), DIAMOND, true, false, false);

        assertEquals(UndoPlanner.UndoDecision.Type.CONFLICT_PRESERVE_CURRENT, decision.type());
        assertEquals(DIAMOND, decision.currentState());
    }

    @Test
    void undoRestoresOnlyTheExactJournaledWrittenStateWithPermission() {
        JournalEntry entry = entry(7, GRASS, PLANKS);

        UndoPlanner.UndoDecision restore = planner.decide(entry, PLANKS, true, false, false);
        UndoPlanner.UndoDecision protectedCell = planner.decide(entry, PLANKS, false, false, false);

        assertEquals(UndoPlanner.UndoDecision.Type.RESTORE_PREVIOUS, restore.type());
        assertEquals(GRASS, restore.restoreState().orElseThrow());
        assertEquals(
                UndoPlanner.UndoDecision.Type.CONFLICT_PRESERVE_CURRENT,
                protectedCell.type());
    }

    @Test
    void undoDoesNotRestoreBlockEntityStateWithoutASafePayload() {
        UndoPlanner.UndoDecision decision = planner.decide(
                entry(7, state("minecraft:chest"), PLANKS),
                PLANKS,
                true,
                false,
                true);

        assertEquals(UndoPlanner.UndoDecision.Type.CONFLICT_PRESERVE_CURRENT, decision.type());
        assertEquals(UndoPlanner.Reason.PREVIOUS_BLOCK_ENTITY_DATA_UNAVAILABLE, decision.reason());
    }

    @Test
    void undoOrdersJournalNewestFirstAndRejectsDuplicateSequences() {
        JournalEntry first = entry(1, GRASS, PLANKS);
        JournalEntry second = entry(2, PLANKS, DIAMOND);

        assertEquals(List.of(second, first), planner.reverseJournal(List.of(first, second)));
        assertThrows(
                IllegalArgumentException.class,
                () -> planner.reverseJournal(List.of(first, entry(1, PLANKS, DIAMOND))));
    }

    @Test
    void conflictClassifierFailsClosedExceptForExplicitSafeTerrainEquivalence() {
        ConflictClassifier classifier = new ConflictClassifier();

        assertEquals(
                ConflictClassifier.Type.UNCHANGED,
                classifier.classify(GRASS, GRASS, true, false, false).type());
        assertEquals(
                ConflictClassifier.Type.SAFE_CHANGED,
                classifier.classify(GRASS, state("minecraft:dirt"), true, false, true).type());
        assertEquals(
                ConflictClassifier.Reason.PROTECTED,
                classifier.classify(GRASS, GRASS, false, false, false).reason());
        assertEquals(
                ConflictClassifier.Reason.BLOCK_ENTITY_PRESENT,
                classifier.classify(GRASS, GRASS, true, true, false).reason());
        assertEquals(
                ConflictClassifier.Type.CONFLICT,
                classifier.classify(GRASS, DIAMOND, true, false, false).type());
    }

    private static JournalEntry entry(
            long sequence,
            BlockStateSpec previous,
            BlockStateSpec written) {
        return new JournalEntry(
                sequence,
                "entry-" + sequence,
                "operation-" + sequence,
                "task-" + sequence,
                POSITION,
                previous,
                written,
                sequence);
    }

    private static BlockStateSpec state(String id) {
        return new BlockStateSpec(NamespacedId.parse(id), Map.of());
    }
}
