package dev.ssa.architect.material;

import java.util.Objects;

public enum BlockCapability {
    FULL_CUBE,
    STAIR,
    SLAB,
    PANE,
    DOOR,
    TRAPDOOR,
    FENCE,
    FENCE_OR_WALL,
    LIGHT_SOURCE,
    ORIENTABLE_AXIS,
    HORIZONTAL_FACING;

    public static BlockCapability parse(String value) {
        Objects.requireNonNull(value, "value");
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown canonical block capability: " + value, exception);
        }
    }
}
