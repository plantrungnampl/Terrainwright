package dev.ssa.architect.blueprint;

import java.util.List;

public enum BuildPhase {
    SITE_PREPARATION,
    FOUNDATION,
    FLOOR_FRAME,
    WALL_FRAME,
    WALLS,
    UPPER_FLOOR,
    STAIRS,
    ROOF,
    WINDOWS_DOORS,
    INTERIOR,
    DECORATION,
    CLEANUP;

    private static final List<BuildPhase> CANONICAL_ORDER = List.of(values());

    public static List<BuildPhase> canonicalOrder() {
        return CANONICAL_ORDER;
    }
}
