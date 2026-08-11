package dev.ssa.architect.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.ssa.architect.terrain.TerrainBudget;
import org.junit.jupiter.api.Test;

final class HouseRequirementsTest {
    @Test
    void acceptsTheInclusiveV1BoundsAndFixesLightAdaptation() {
        HouseRequirements minimum = requirements(9, 9, 1, 0);
        HouseRequirements maximum = requirements(31, 31, 3, 6);

        assertEquals(TerrainAdaptation.LIGHT, minimum.terrainAdaptation());
        assertEquals(9, minimum.targetWidth());
        assertEquals(31, maximum.targetDepth());
    }

    @Test
    void rejectsOutOfRangeDimensionsFloorsAndBedrooms() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> requirements(8, 19, 2, 2)),
                () -> assertThrows(IllegalArgumentException.class, () -> requirements(15, 32, 2, 2)),
                () -> assertThrows(IllegalArgumentException.class, () -> requirements(15, 19, 0, 2)),
                () -> assertThrows(IllegalArgumentException.class, () -> requirements(15, 19, 4, 2)),
                () -> assertThrows(IllegalArgumentException.class, () -> requirements(15, 19, 2, -1)),
                () -> assertThrows(IllegalArgumentException.class, () -> requirements(15, 19, 2, 7)));
    }

    @Test
    void requiresStyleAndEntrancePreference() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new HouseRequirements(
                        null, 9, 9, 1, 0, false, false, false, false, EntrancePreference.AUTO, 1L)),
                () -> assertThrows(NullPointerException.class, () -> new HouseRequirements(
                        StyleId.parse("example:house"), 9, 9, 1, 0,
                        false, false, false, false, null, 1L)));
    }

    @Test
    void styleIdsAreOpenNamespacedValues() {
        assertEquals(
                "smart_survival_architect:modern",
                StyleId.parse("smart_survival_architect:modern").toString());
        assertEquals("example:custom_house", StyleId.parse("example:custom_house").toString());
        assertThrows(IllegalArgumentException.class, () -> StyleId.parse("modern"));
    }

    @Test
    void lightTerrainBudgetMatchesTheProductLimit() {
        assertEquals(new TerrainBudget(150, 180, 3, 4, false, false), TerrainBudget.light());
        assertThrows(IllegalArgumentException.class,
                () -> new TerrainBudget(-1, 180, 3, 4, false, false));
    }

    private static HouseRequirements requirements(int width, int depth, int floors, int bedrooms) {
        return new HouseRequirements(
                StyleId.parse("smart_survival_architect:medieval"),
                width,
                depth,
                floors,
                bedrooms,
                true,
                true,
                false,
                true,
                EntrancePreference.AUTO,
                42L);
    }
}
