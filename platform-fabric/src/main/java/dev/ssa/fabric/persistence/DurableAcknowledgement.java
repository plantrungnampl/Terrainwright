package dev.ssa.fabric.persistence;

import dev.ssa.construction.operation.OperationStatus;
import java.util.Objects;

public record DurableAcknowledgement(OperationStatus status, long latencyNanos, String ioThread) {
    public DurableAcknowledgement {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(ioThread, "ioThread");
        if (latencyNanos < 0) {
            throw new IllegalArgumentException("latencyNanos must not be negative");
        }
    }
}
