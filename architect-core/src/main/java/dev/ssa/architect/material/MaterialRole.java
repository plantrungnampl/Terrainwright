package dev.ssa.architect.material;

import java.util.Objects;

public enum MaterialRole {
    FOUNDATION_STONE,
    FOUNDATION_FILL,
    STRUCTURAL_WOOD,
    STRUCTURAL_PRIMARY,
    WALL_PRIMARY,
    WALL_SECONDARY,
    FLOOR_PRIMARY,
    FLOOR_SECONDARY,
    ROOF_PRIMARY,
    ROOF_ACCENT,
    TRIM,
    WINDOW,
    DOOR,
    RAILING,
    STAIR,
    INTERIOR_PRIMARY,
    LIGHTING,
    TEMP_SCAFFOLD;

    public static MaterialRole parse(String value) {
        Objects.requireNonNull(value, "value");
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown canonical material role: " + value, exception);
        }
    }
}
