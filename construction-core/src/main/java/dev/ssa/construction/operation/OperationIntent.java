package dev.ssa.construction.operation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record OperationIntent(
        String operationId,
        String jobId,
        Optional<String> taskId,
        Optional<String> atomicGroupId,
        long jobRevision,
        OperationKind kind,
        OperationStatus status,
        List<OperationDelta> deltas) {
    public OperationIntent {
        requireIdentifier(operationId, "operationId");
        requireIdentifier(jobId, "jobId");
        taskId = requireOptionalIdentifier(taskId, "taskId");
        atomicGroupId = requireOptionalIdentifier(atomicGroupId, "atomicGroupId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        if (jobRevision < 0) {
            throw new IllegalArgumentException("jobRevision must not be negative");
        }
        deltas = List.copyOf(Objects.requireNonNull(deltas, "deltas"));

        long inventoryCount = deltas.stream().filter(InventoryDelta.class::isInstance).count();
        long worldCount = deltas.stream().filter(WorldDelta.class::isInstance).count();
        if (inventoryCount > 256 || worldCount > 64) {
            throw new IllegalArgumentException("intent exceeds schema delta bounds");
        }
        if (kind == OperationKind.MATERIAL_TRANSFER && (inventoryCount < 2 || worldCount != 0)) {
            throw new IllegalArgumentException("material transfer requires at least two inventory deltas and no world delta");
        }
        if (kind == OperationKind.WORLD_MUTATION && worldCount < 1) {
            throw new IllegalArgumentException("world mutation requires at least one world delta");
        }

        Set<String> keys = new HashSet<>();
        for (OperationDelta delta : deltas) {
            if (!keys.add(delta.evidenceKey())) {
                throw new IllegalArgumentException("intent contains duplicate evidence key: " + delta.evidenceKey());
            }
        }
    }

    public static OperationIntent prepared(
            String operationId,
            String jobId,
            long jobRevision,
            OperationKind kind,
            List<OperationDelta> deltas) {
        return prepared(operationId, jobId, Optional.empty(), Optional.empty(), jobRevision, kind, deltas);
    }

    public static OperationIntent prepared(
            String operationId,
            String jobId,
            Optional<String> taskId,
            Optional<String> atomicGroupId,
            long jobRevision,
            OperationKind kind,
            List<OperationDelta> deltas) {
        return new OperationIntent(
                operationId,
                jobId,
                taskId,
                atomicGroupId,
                jobRevision,
                kind,
                OperationStatus.PREPARED,
                deltas);
    }

    public OperationIntent withStatus(OperationStatus nextStatus) {
        return new OperationIntent(
                operationId,
                jobId,
                taskId,
                atomicGroupId,
                jobRevision,
                kind,
                nextStatus,
                deltas);
    }

    private static void requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException(name + " must contain 1 to 160 characters");
        }
    }

    private static Optional<String> requireOptionalIdentifier(Optional<String> value, String name) {
        Optional<String> trusted = Objects.requireNonNull(value, name);
        trusted.ifPresent(identifier -> requireIdentifier(identifier, name));
        return trusted;
    }
}
