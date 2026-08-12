package dev.ssa.fabric.lifecycle;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record BuilderLifecycleTombstone(
        UUID builderId,
        Optional<Observation> tombstone,
        long revision,
        int formatVersion) {
    public static final int CURRENT_FORMAT_VERSION = 1;

    public BuilderLifecycleTombstone {
        Objects.requireNonNull(builderId, "builderId");
        tombstone = Objects.requireNonNull(tombstone, "tombstone");
        if (revision < 0) {
            throw new IllegalArgumentException("Builder lifecycle revision must not be negative");
        }
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported Builder lifecycle format version: " + formatVersion);
        }
        if (tombstone.isPresent() && revision == 0) {
            throw new IllegalArgumentException("A lifecycle tombstone requires a positive revision");
        }
    }

    public static BuilderLifecycleTombstone active(UUID builderId) {
        return new BuilderLifecycleTombstone(
                builderId, Optional.empty(), 0, CURRENT_FORMAT_VERSION);
    }

    public BuilderLifecycleTombstone observeUnload() {
        return this;
    }

    public BuilderLifecycleTombstone observeDeath(long observedGameTime) {
        return tombstone(Cause.DEATH, observedGameTime);
    }

    public BuilderLifecycleTombstone observeRemoval(long observedGameTime) {
        return tombstone(Cause.REMOVAL, observedGameTime);
    }

    public boolean isTombstoned() {
        return tombstone.isPresent();
    }

    public boolean canReplace() {
        return isTombstoned();
    }

    private BuilderLifecycleTombstone tombstone(Cause cause, long observedGameTime) {
        if (tombstone.isPresent()) {
            return this;
        }
        return new BuilderLifecycleTombstone(
                builderId,
                Optional.of(new Observation(cause, observedGameTime)),
                revision + 1,
                formatVersion);
    }

    public record Observation(Cause cause, long observedGameTime) {
        public Observation {
            Objects.requireNonNull(cause, "cause");
            if (observedGameTime < 0) {
                throw new IllegalArgumentException("Observed game time must not be negative");
            }
        }
    }

    public enum Cause {
        DEATH,
        REMOVAL
    }
}
