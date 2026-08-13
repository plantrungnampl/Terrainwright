package dev.ssa.fabric.builder;

import java.util.Objects;
import net.minecraft.core.BlockPos;

/** Detects bounded navigation stagnation without deciding how recovery mutates the world. */
public final class StuckDetector {
    private final int threshold;
    private BlockPos lastPosition;
    private long lastTick = Long.MIN_VALUE;
    private int stationaryObservations;

    public StuckDetector(int threshold) {
        if (threshold < 1) {
            throw new IllegalArgumentException("stuck threshold must be positive");
        }
        this.threshold = threshold;
    }

    public Observation observe(long tick, BlockPos position, NavigationStatus status) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(status, "status");
        if (tick < lastTick) {
            throw new IllegalArgumentException("navigation ticks must be monotonic");
        }
        lastTick = tick;
        if (status != NavigationStatus.MOVING) {
            lastPosition = position.immutable();
            stationaryObservations = 0;
            return new Observation(0, false);
        }
        if (lastPosition == null) {
            stationaryObservations = 1;
        } else if (lastPosition.equals(position)) {
            stationaryObservations++;
        } else {
            stationaryObservations = 0;
        }
        lastPosition = position.immutable();
        return new Observation(stationaryObservations, stationaryObservations >= threshold);
    }

    public record Observation(int stationaryObservations, boolean stuck) {
    }

    public enum NavigationStatus {
        MOVING,
        ARRIVED,
        BLOCKED,
        SUSPENDED
    }
}
