package dev.ssa.construction.job;

import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.construction.journal.JournalEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public record BuildJob(
        String jobId,
        String ownerId,
        String hutId,
        String blueprintId,
        String blueprintHash,
        NamespacedId worldId,
        GridPos origin,
        int rotation,
        BuildJobState state,
        Optional<BuildPhase> currentPhase,
        Set<String> completedTaskIds,
        List<Diagnostic> failedTaskDiagnostics,
        List<JournalEntry> blockJournal,
        long revision,
        int formatVersion) {
    public static final int CURRENT_FORMAT_VERSION = 1;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.:/-]{1,160}");
    private static final Pattern BLUEPRINT_HASH = Pattern.compile("[0-9a-f]{16,128}");

    public BuildJob {
        requireIdentifier(jobId, "jobId");
        requireOwner(ownerId);
        requireIdentifier(hutId, "hutId");
        requireIdentifier(blueprintId, "blueprintId");
        Objects.requireNonNull(blueprintHash, "blueprintHash");
        if (!BLUEPRINT_HASH.matcher(blueprintHash).matches()) {
            throw new IllegalArgumentException("blueprintHash must be lowercase hexadecimal");
        }
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(origin, "origin");
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
            throw new IllegalArgumentException("Rotation must be 0, 90, 180, or 270 degrees");
        }
        Objects.requireNonNull(state, "state");
        currentPhase = Objects.requireNonNull(currentPhase, "currentPhase");
        completedTaskIds = Set.copyOf(Objects.requireNonNull(completedTaskIds, "completedTaskIds"));
        failedTaskDiagnostics = List.copyOf(
                Objects.requireNonNull(failedTaskDiagnostics, "failedTaskDiagnostics"));
        blockJournal = List.copyOf(Objects.requireNonNull(blockJournal, "blockJournal"));
        if (completedTaskIds.size() > 30_000) {
            throw new IllegalArgumentException("Completed task count exceeds schema bound");
        }
        if (failedTaskDiagnostics.size() > 1_024 || blockJournal.size() > 50_000) {
            throw new IllegalArgumentException("Build job collection exceeds schema bound");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("Revision must not be negative");
        }
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported BuildJob format version: " + formatVersion);
        }
        failedTaskDiagnostics.forEach(diagnostic -> {
            if (diagnostic.revision() > revision) {
                throw new IllegalArgumentException(
                        "Diagnostic is newer than the BuildJob revision");
            }
        });
        validateJournal(blockJournal, completedTaskIds, revision);
    }

    public static BuildJob create(
            String jobId,
            String ownerId,
            String hutId,
            String blueprintId,
            String blueprintHash,
            NamespacedId worldId,
            GridPos origin,
            int rotation) {
        return new BuildJob(
                jobId,
                ownerId,
                hutId,
                blueprintId,
                blueprintHash,
                worldId,
                origin,
                rotation,
                BuildJobState.IDLE,
                Optional.empty(),
                Set.of(),
                List.of(),
                List.of(),
                0,
                CURRENT_FORMAT_VERSION);
    }

    public BuildJob transitionTo(BuildJobState nextState) {
        Objects.requireNonNull(nextState, "nextState");
        if (state == nextState) {
            return this;
        }
        if (!state.canTransitionTo(nextState)) {
            throw new IllegalStateException("Illegal BuildJob transition: " + state + " -> " + nextState);
        }
        if (nextState.transitionRequiresDiagnostic()) {
            throw new IllegalStateException(
                    "BuildJob failure transition requires an exact diagnostic: " + nextState);
        }
        return copy(nextState, currentPhase, completedTaskIds, failedTaskDiagnostics, blockJournal, revision + 1);
    }

    public BuildJob recordDiagnosticAndTransition(Diagnostic diagnostic, BuildJobState nextState) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        Objects.requireNonNull(nextState, "nextState");
        if (!nextState.transitionRequiresDiagnostic()) {
            throw new IllegalArgumentException(
                    "Diagnostic transition requires a failure state: " + nextState);
        }
        if (!state.canTransitionTo(nextState)) {
            throw new IllegalStateException("Illegal BuildJob transition: " + state + " -> " + nextState);
        }
        if (diagnostic.revision() != revision + 1) {
            throw new IllegalArgumentException(
                    "Diagnostic revision must be the next job revision");
        }
        List<Diagnostic> nextDiagnostics = new ArrayList<>(failedTaskDiagnostics);
        nextDiagnostics.add(diagnostic);
        return copy(
                nextState,
                currentPhase,
                completedTaskIds,
                nextDiagnostics,
                blockJournal,
                revision + 1);
    }

    public BuildJob recordCompletion(String taskId, JournalEntry entry) {
        requireIdentifier(taskId, "taskId");
        Objects.requireNonNull(entry, "entry");
        if (!taskId.equals(entry.taskId())) {
            throw new IllegalArgumentException("Journal task ID does not match completed task ID");
        }
        return recordCompletions(List.of(entry));
    }

    public BuildJob recordCompletions(List<JournalEntry> entries) {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("A committed operation must contain journal entries");
        }
        Set<String> taskIds = new HashSet<>();
        String operationId = entries.getFirst().operationId();
        for (JournalEntry entry : entries) {
            if (!operationId.equals(entry.operationId())) {
                throw new IllegalArgumentException(
                        "One committed operation must share one operation ID");
            }
            if (!taskIds.add(entry.taskId())) {
                throw new IllegalArgumentException(
                        "One committed operation cannot repeat a task ID");
            }
        }

        boolean allAlreadyCompleted = completedTaskIds.containsAll(taskIds);
        if (allAlreadyCompleted) {
            List<JournalEntry> existingOperationEntries = blockJournal.stream()
                    .filter(entry -> entry.operationId().equals(operationId))
                    .toList();
            return existingOperationEntries.equals(entries)
                    ? this
                    : failConflictingCompletion(taskIds);
        }
        if (taskIds.stream().anyMatch(completedTaskIds::contains)) {
            return failConflictingCompletion(taskIds);
        }
        if (!state.canRecordConstructionProgress()) {
            throw new IllegalStateException(
                    "BuildJob cannot record construction progress while " + state);
        }

        long priorSequence = blockJournal.isEmpty() ? -1 : blockJournal.getLast().sequence();
        for (JournalEntry entry : entries) {
            if (entry.committedRevision() != revision + 1) {
                throw new IllegalArgumentException(
                        "Journal committed revision must be the next job revision");
            }
            if (entry.sequence() <= priorSequence) {
                throw new IllegalArgumentException("Journal sequence must increase monotonically");
            }
            priorSequence = entry.sequence();
        }
        Set<String> nextCompleted = new HashSet<>(completedTaskIds);
        nextCompleted.addAll(taskIds);
        List<JournalEntry> nextJournal = new ArrayList<>(blockJournal);
        nextJournal.addAll(entries);
        return copy(
                state,
                currentPhase,
                nextCompleted,
                failedTaskDiagnostics,
                nextJournal,
                revision + 1);
    }

    private BuildJob copy(
            BuildJobState nextState,
            Optional<BuildPhase> nextPhase,
            Set<String> nextCompleted,
            List<Diagnostic> nextDiagnostics,
            List<JournalEntry> nextJournal,
            long nextRevision) {
        return new BuildJob(
                jobId,
                ownerId,
                hutId,
                blueprintId,
                blueprintHash,
                worldId,
                origin,
                rotation,
                nextState,
                nextPhase,
                nextCompleted,
                nextDiagnostics,
                nextJournal,
                nextRevision,
                formatVersion);
    }

    private BuildJob failConflictingCompletion(Set<String> taskIds) {
        throw new IllegalStateException("Completed task has different journal evidence: " + taskIds);
    }

    private static void validateJournal(
            List<JournalEntry> journal,
            Set<String> completedTaskIds,
            long revision) {
        Set<Long> sequences = new HashSet<>();
        Set<String> entryIds = new HashSet<>();
        Set<String> journaledTasks = new HashSet<>();
        java.util.Map<Long, String> operationByRevision = new java.util.HashMap<>();
        java.util.Map<String, Long> revisionByOperation = new java.util.HashMap<>();
        long priorSequence = -1;
        long priorCommittedRevision = 0;
        for (JournalEntry entry : journal) {
            Objects.requireNonNull(entry, "journal entry");
            if (!sequences.add(entry.sequence()) || entry.sequence() <= priorSequence) {
                throw new IllegalArgumentException("Journal sequences must be unique and increasing");
            }
            if (!entryIds.add(entry.entryId())) {
                throw new IllegalArgumentException("Journal entry IDs must be unique");
            }
            if (!journaledTasks.add(entry.taskId())) {
                throw new IllegalArgumentException("Completed task has duplicate journal evidence");
            }
            if (!completedTaskIds.contains(entry.taskId())) {
                throw new IllegalArgumentException("Journal entry references an incomplete task");
            }
            if (entry.committedRevision() == 0
                    || entry.committedRevision() > revision
                    || entry.committedRevision() < priorCommittedRevision) {
                throw new IllegalArgumentException(
                        "Journal committed revisions must be positive, ordered, and not newer than the BuildJob");
            }
            String priorOperation = operationByRevision.putIfAbsent(
                    entry.committedRevision(), entry.operationId());
            if (priorOperation != null && !priorOperation.equals(entry.operationId())) {
                throw new IllegalArgumentException(
                        "One job revision cannot commit multiple operations");
            }
            Long priorRevision = revisionByOperation.putIfAbsent(
                    entry.operationId(), entry.committedRevision());
            if (priorRevision != null && priorRevision != entry.committedRevision()) {
                throw new IllegalArgumentException(
                        "One operation cannot span multiple job revisions");
            }
            priorSequence = entry.sequence();
            priorCommittedRevision = entry.committedRevision();
        }
        if (!journaledTasks.containsAll(completedTaskIds)) {
            throw new IllegalArgumentException("Every completed task must have journal evidence");
        }
    }

    private static void requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a valid durable identifier");
        }
    }

    private static void requireOwner(String ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        if (ownerId.isBlank() || ownerId.length() > 80) {
            throw new IllegalArgumentException("ownerId must contain 1 to 80 characters");
        }
    }

    public record Diagnostic(
            String code,
            Severity severity,
            String message,
            boolean recoverable,
            long revision,
            Optional<GridPos> position) {
        private static final Pattern CODE = Pattern.compile("[A-Z0-9_]{3,80}");

        public Diagnostic {
            Objects.requireNonNull(code, "code");
            if (!CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("Diagnostic code is invalid");
            }
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(message, "message");
            if (message.isBlank() || message.length() > 500) {
                throw new IllegalArgumentException("Diagnostic message must contain 1 to 500 characters");
            }
            if (revision < 0) {
                throw new IllegalArgumentException("Diagnostic revision must not be negative");
            }
            position = Objects.requireNonNull(position, "position");
        }
    }

    public enum Severity {
        INFO,
        WARNING,
        ERROR,
        CRITICAL
    }
}
