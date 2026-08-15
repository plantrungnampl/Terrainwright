package dev.ssa.fabric.lifecycle;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record BuilderLifecycleTombstone(
        UUID builderId,
        Status status,
        Optional<Observation> tombstone,
        long revision,
        int formatVersion) {
    public static final int CURRENT_FORMAT_VERSION = 2;

    public BuilderLifecycleTombstone {
        Objects.requireNonNull(builderId, "builderId");
        Objects.requireNonNull(status, "status");
        tombstone = Objects.requireNonNull(tombstone, "tombstone");
        if (revision < 0) {
            throw new IllegalArgumentException("Builder lifecycle revision must not be negative");
        }
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported Builder lifecycle format version: " + formatVersion);
        }
        if ((status == Status.TOMBSTONED) != tombstone.isPresent()) {
            throw new IllegalArgumentException("Only TOMBSTONED lifecycle state carries tombstone evidence");
        }
        if (status == Status.TOMBSTONED && revision == 0) {
            throw new IllegalArgumentException("A lifecycle tombstone requires a positive revision");
        }
    }

    public static BuilderLifecycleTombstone active(UUID builderId) {
        return new BuilderLifecycleTombstone(
                builderId, Status.ACTIVE, Optional.empty(), 0, CURRENT_FORMAT_VERSION);
    }

    public BuilderLifecycleTombstone observeUnload() {
        if (status != Status.ACTIVE) {
            return this;
        }
        return new BuilderLifecycleTombstone(
                builderId, Status.SUSPENDED, Optional.empty(), revision + 1, formatVersion);
    }

    public BuilderLifecycleTombstone observeLoad() {
        if (status != Status.SUSPENDED) {
            return this;
        }
        return new BuilderLifecycleTombstone(
                builderId, Status.ACTIVE, Optional.empty(), revision + 1, formatVersion);
    }

    public BuilderLifecycleTombstone observeDeath(long observedGameTime) {
        return tombstone(Cause.DEATH, observedGameTime);
    }

    public BuilderLifecycleTombstone observeRemoval(long observedGameTime) {
        return tombstone(Cause.REMOVAL, observedGameTime);
    }

    public BuilderLifecycleTombstone observeSpawnFailure(long observedGameTime) {
        return tombstone(Cause.SPAWN_FAILURE, observedGameTime);
    }

    public boolean isTombstoned() {
        return status == Status.TOMBSTONED;
    }

    public boolean canReplace() {
        return isTombstoned();
    }

    public BuilderLifecycleTombstone replaceWith(UUID replacementId) {
        Objects.requireNonNull(replacementId, "replacementId");
        if (!canReplace()) {
            throw new IllegalStateException("Builder replacement requires durable tombstone evidence");
        }
        if (builderId.equals(replacementId)) {
            throw new IllegalArgumentException("Replacement Builder must have a new identity");
        }
        return new BuilderLifecycleTombstone(
                replacementId, Status.ACTIVE, Optional.empty(), revision + 1, formatVersion);
    }

    private BuilderLifecycleTombstone tombstone(Cause cause, long observedGameTime) {
        if (tombstone.isPresent()) {
            return this;
        }
        return new BuilderLifecycleTombstone(
                builderId,
                Status.TOMBSTONED,
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
        REMOVAL,
        SPAWN_FAILURE
    }

    public enum Status {
        ACTIVE,
        SUSPENDED,
        TOMBSTONED
    }
}
