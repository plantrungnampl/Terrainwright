package dev.ssa.construction.conflict;

import dev.ssa.architect.model.BlockStateSpec;
import java.util.Objects;

public final class ConflictClassifier {
    public Classification classify(
            BlockStateSpec expectedState,
            BlockStateSpec currentState,
            boolean permissionGranted,
            boolean currentHasBlockEntity,
            boolean approvedEquivalentNaturalTerrain) {
        Objects.requireNonNull(expectedState, "expectedState");
        Objects.requireNonNull(currentState, "currentState");
        if (!permissionGranted) {
            return new Classification(Type.CONFLICT, Reason.PROTECTED);
        }
        if (currentHasBlockEntity) {
            return new Classification(Type.CONFLICT, Reason.BLOCK_ENTITY_PRESENT);
        }
        if (currentState.equals(expectedState)) {
            return new Classification(Type.UNCHANGED, Reason.NONE);
        }
        if (approvedEquivalentNaturalTerrain) {
            return new Classification(Type.SAFE_CHANGED, Reason.SAFE_TERRAIN_EQUIVALENT);
        }
        return new Classification(Type.CONFLICT, Reason.UNEXPECTED_STATE);
    }

    public enum Type {
        UNCHANGED,
        SAFE_CHANGED,
        CONFLICT
    }

    public enum Reason {
        NONE,
        SAFE_TERRAIN_EQUIVALENT,
        PROTECTED,
        BLOCK_ENTITY_PRESENT,
        UNEXPECTED_STATE
    }

    public record Classification(Type type, Reason reason) {
        public Classification {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(reason, "reason");
            if ((type == Type.UNCHANGED && reason != Reason.NONE)
                    || (type == Type.SAFE_CHANGED && reason != Reason.SAFE_TERRAIN_EQUIVALENT)
                    || (type == Type.CONFLICT
                            && (reason == Reason.NONE || reason == Reason.SAFE_TERRAIN_EQUIVALENT))) {
                throw new IllegalArgumentException("Conflict type and reason do not agree");
            }
        }
    }
}
