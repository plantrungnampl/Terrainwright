package dev.ssa.architect.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

final class BuildPhaseTest {
    @Test
    void followsTheCanonicalR2ConstructionOrder() {
        assertEquals(
                List.of(
                        BuildPhase.SITE_PREPARATION,
                        BuildPhase.FOUNDATION,
                        BuildPhase.FLOOR_FRAME,
                        BuildPhase.WALL_FRAME,
                        BuildPhase.WALLS,
                        BuildPhase.UPPER_FLOOR,
                        BuildPhase.STAIRS,
                        BuildPhase.ROOF,
                        BuildPhase.WINDOWS_DOORS,
                        BuildPhase.INTERIOR,
                        BuildPhase.DECORATION,
                        BuildPhase.CLEANUP),
                BuildPhase.canonicalOrder());
    }
}
