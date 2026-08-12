package dev.ssa.construction.journal;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import java.util.Objects;
import java.util.regex.Pattern;

public record JournalEntry(
        long sequence,
        String entryId,
        String operationId,
        String taskId,
        GridPos position,
        BlockStateSpec previousState,
        BlockStateSpec writtenState,
        long committedRevision) {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.:/-]{1,160}");

    public JournalEntry {
        if (sequence < 0) {
            throw new IllegalArgumentException("Journal sequence must not be negative");
        }
        requireIdentifier(entryId, "entryId");
        requireIdentifier(operationId, "operationId");
        requireIdentifier(taskId, "taskId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(previousState, "previousState");
        Objects.requireNonNull(writtenState, "writtenState");
        if (previousState.equals(writtenState)) {
            throw new IllegalArgumentException("Journal entry must describe a world-state change");
        }
        if (committedRevision < 0) {
            throw new IllegalArgumentException("Committed revision must not be negative");
        }
    }

    private static void requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a valid durable identifier");
        }
    }
}
