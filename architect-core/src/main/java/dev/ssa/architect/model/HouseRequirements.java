package dev.ssa.architect.model;

import java.util.Objects;

public record HouseRequirements(
        StyleId styleId,
        int targetWidth,
        int targetDepth,
        int floors,
        int bedrooms,
        boolean kitchen,
        boolean storage,
        boolean balcony,
        boolean chimney,
        EntrancePreference entrancePreference,
        long seed) {
    public static final int MIN_TARGET_SIZE = 9;
    public static final int MAX_TARGET_SIZE = 31;
    public static final int MIN_FLOORS = 1;
    public static final int MAX_FLOORS = 3;
    public static final int MIN_BEDROOMS = 0;
    public static final int MAX_BEDROOMS = 6;

    public HouseRequirements {
        Objects.requireNonNull(styleId, "styleId");
        Objects.requireNonNull(entrancePreference, "entrancePreference");
        requireRange(targetWidth, MIN_TARGET_SIZE, MAX_TARGET_SIZE, "targetWidth");
        requireRange(targetDepth, MIN_TARGET_SIZE, MAX_TARGET_SIZE, "targetDepth");
        requireRange(floors, MIN_FLOORS, MAX_FLOORS, "floors");
        requireRange(bedrooms, MIN_BEDROOMS, MAX_BEDROOMS, "bedrooms");
    }

    public TerrainAdaptation terrainAdaptation() {
        return TerrainAdaptation.LIGHT;
    }

    private static void requireRange(int value, int minimum, int maximum, String label) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(label + " must be between " + minimum + " and " + maximum);
        }
    }
}
