package dev.ssa.architect.style;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.StyleId;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

final class StyleIdentityTest {
    private final StylePack medieval = new MedievalStyle();
    private final StylePack japanese = new JapaneseStyle();
    private final StylePack modern = new ModernStyle();

    @Test
    void builtInIdsAndGeometryRemainDistinctWithoutLookingAtPalette() {
        assertEquals(StyleId.parse("smart_survival_architect:medieval"), medieval.id());
        assertEquals(StyleId.parse("smart_survival_architect:japanese"), japanese.id());
        assertEquals(StyleId.parse("smart_survival_architect:modern"), modern.id());
        assertTrue(medieval.version() > 0 && japanese.version() > 0 && modern.version() > 0);

        assertEquals(StylePack.RoofFamily.GABLE, medieval.roofRules().primaryFamily());
        assertEquals(StylePack.RoofFamily.WIDE_OVERHANG_HIP, japanese.roofRules().primaryFamily());
        assertEquals(StylePack.RoofFamily.FLAT, modern.roofRules().primaryFamily());
        assertNotEquals(medieval.roofRules().overhangRange(), japanese.roofRules().overhangRange());

        assertEquals(StylePack.FoundationFamily.STONE_BASE, medieval.foundationRules().primaryFamily());
        assertEquals(StylePack.FoundationFamily.RAISED_PILLAR, japanese.foundationRules().primaryFamily());
        assertEquals(StylePack.FoundationFamily.SLAB, modern.foundationRules().primaryFamily());
        assertTrue(modern.openingRules().glazingRatio() > japanese.openingRules().glazingRatio());
        assertTrue(japanese.proportionRules().symmetryBias() > medieval.proportionRules().symmetryBias());
        assertTrue(modern.proportionRules().openPlanBias() > medieval.proportionRules().openPlanBias());

        assertEquals(3, java.util.stream.Stream.of(medieval, japanese, modern)
                .map(StyleIdentityTest::geometrySignature)
                .distinct()
                .count());
    }

    @Test
    void everyBuiltInStyleHasImmutableFallbacksForAllCanonicalRoles() {
        for (StylePack style : List.of(medieval, japanese, modern)) {
            assertEquals(EnumSet.allOf(MaterialRole.class), style.fallbackPalette().keySet());
            assertTrue(style.fallbackPalette().values().stream().allMatch(candidates -> !candidates.isEmpty()));
            assertThrows(UnsupportedOperationException.class, () -> style.fallbackPalette().clear());
            assertThrows(UnsupportedOperationException.class,
                    () -> style.fallbackPalette().get(MaterialRole.ROOF_PRIMARY).clear());
        }
    }

    private static List<Object> geometrySignature(StylePack style) {
        return List.of(
                style.proportionRules(),
                style.foundationRules(),
                style.roofRules(),
                style.openingRules());
    }
}
