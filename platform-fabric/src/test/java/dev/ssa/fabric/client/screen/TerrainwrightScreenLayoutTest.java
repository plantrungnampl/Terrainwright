package dev.ssa.fabric.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class TerrainwrightScreenLayoutTest {
    private static final List<Viewport> VIEWPORTS = List.of(
            new Viewport(320, 180),
            new Viewport(378, 226),
            new Viewport(640, 360));

    // Mutation caught: a rail or content-region calculation escapes the viewport or collides with another region.
    @Test
    void architectRegionsFitAndRemainSeparateAtSupportedViewports() {
        for (Viewport viewport : VIEWPORTS) {
            TerrainwrightScreenLayout.ArchitectLayout layout =
                    TerrainwrightScreenLayout.architect(viewport.width(), viewport.height());
            List<TerrainwrightScreenLayout.Bounds> content = List.of(
                    layout.configurationRail(),
                    layout.worldCanvas(),
                    layout.statusRail(),
                    layout.secondaryActions());

            assertFits(viewport, layout.stepRail(), layout.actionRail());
            assertFits(viewport, content.toArray(TerrainwrightScreenLayout.Bounds[]::new));
            for (TerrainwrightScreenLayout.Bounds bounds : content) {
                assertFalse(layout.stepRail().overlaps(bounds), viewport.toString());
                assertFalse(layout.actionRail().overlaps(bounds), viewport.toString());
            }
            assertPairwiseSeparate(List.of(
                    layout.configurationRail(), layout.worldCanvas(), layout.statusRail()), viewport);
            assertFalse(layout.secondaryActions().overlaps(layout.configurationRail()), viewport.toString());
            assertFalse(layout.secondaryActions().overlaps(layout.worldCanvas()), viewport.toString());
            assertFalse(layout.secondaryActions().overlaps(layout.statusRail()), viewport.toString());
            assertTrue(layout.worldCanvas().width() > 0, viewport.toString());
            assertTrue(layout.worldCanvas().height() > 0, viewport.toString());
        }
    }

    // Mutation caught: the compact breakpoint changes or a four-button slot is placed outside or atop another slot.
    @Test
    void architectCompactModeAndActionSlotsMatchTheThreeProfiles() {
        for (int index = 0; index < VIEWPORTS.size(); index++) {
            Viewport viewport = VIEWPORTS.get(index);
            TerrainwrightScreenLayout.ArchitectLayout layout =
                    TerrainwrightScreenLayout.architect(viewport.width(), viewport.height());

            assertEquals(index == 0, layout.compact(), viewport.toString());
            assertEquals(4, layout.actionSlots().size(), viewport.toString());
            assertPairwiseSeparate(layout.actionSlots(), viewport);
            for (TerrainwrightScreenLayout.Bounds slot : layout.actionSlots()) {
                assertTrue(slot.x() >= layout.actionRail().x(), viewport.toString());
                assertTrue(slot.y() >= layout.actionRail().y(), viewport.toString());
                assertTrue(slot.right() <= layout.actionRail().right(), viewport.toString());
                assertTrue(slot.bottom() <= layout.actionRail().bottom(), viewport.toString());
            }
        }
    }

    // Mutation caught: builder sections are stacked or sized without reserving distinct viewport space.
    @Test
    void builderRegionsFitAndRemainSeparateAtSupportedViewports() {
        for (Viewport viewport : VIEWPORTS) {
            TerrainwrightScreenLayout.BuilderLayout layout =
                    TerrainwrightScreenLayout.builder(viewport.width(), viewport.height());
            List<TerrainwrightScreenLayout.Bounds> content = List.of(
                    layout.header(), layout.progress(), layout.materials(), layout.diagnostics());

            assertFits(viewport, content.toArray(TerrainwrightScreenLayout.Bounds[]::new));
            assertFits(viewport, layout.actionRail());
            assertPairwiseSeparate(content, viewport);
            for (TerrainwrightScreenLayout.Bounds bounds : content) {
                assertFalse(layout.actionRail().overlaps(bounds), viewport.toString());
            }
        }
    }

    // Mutation caught: minimum-size validation is removed and unsupported dimensions produce invalid geometry.
    @Test
    void rejectsViewportsBelowTheSupportedMinimum() {
        assertThrows(IllegalArgumentException.class, () -> TerrainwrightScreenLayout.architect(319, 180));
        assertThrows(IllegalArgumentException.class, () -> TerrainwrightScreenLayout.architect(320, 179));
        assertThrows(IllegalArgumentException.class, () -> TerrainwrightScreenLayout.builder(319, 180));
        assertThrows(IllegalArgumentException.class, () -> TerrainwrightScreenLayout.builder(320, 179));
    }

    private static void assertFits(
            Viewport viewport, TerrainwrightScreenLayout.Bounds... bounds) {
        for (TerrainwrightScreenLayout.Bounds region : bounds) {
            assertTrue(region.fitsInside(viewport.width(), viewport.height()),
                    region + " outside " + viewport);
        }
    }

    private static void assertPairwiseSeparate(
            List<TerrainwrightScreenLayout.Bounds> bounds, Viewport viewport) {
        for (int first = 0; first < bounds.size(); first++) {
            for (int second = first + 1; second < bounds.size(); second++) {
                assertFalse(bounds.get(first).overlaps(bounds.get(second)),
                        bounds.get(first) + " overlaps " + bounds.get(second) + " at " + viewport);
            }
        }
    }

    private record Viewport(int width, int height) {}
}
