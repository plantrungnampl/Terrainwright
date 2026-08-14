package dev.ssa.fabric.client.screen;

import java.util.List;
import net.minecraft.client.Minecraft;

public final class ArchitectScreenAssertions {
    private ArchitectScreenAssertions() {}

    public static void assertCompactWorkflowBadgesConveyStateAndFit(ArchitectScreen screen) {
        String[] expectedLabels = {"Design OK", "Site OK", "Preview Wait", "Hut Lock", "Confirm Lock"};
        List<ArchitectScreen.WorkflowBadge> badges = screen.workflowBadges();
        assertState(badges.size() == expectedLabels.length,
                "Compact Architect workflow did not render all five badges");
        for (int index = 0; index < expectedLabels.length; index++) {
            ArchitectScreen.WorkflowBadge badge = badges.get(index);
            assertState(
                    badge.text().getString().equals(expectedLabels[index]),
                    "Compact Architect workflow badge did not expose its actual state: expected "
                            + expectedLabels[index] + ", got " + badge.text().getString());
            int renderedWidth = Minecraft.getInstance().font.width(badge.text());
            assertState(
                    renderedWidth <= badge.bounds().width(),
                    "Compact Architect workflow badge was clipped: " + badge.text().getString()
                            + " width=" + renderedWidth + " available=" + badge.bounds().width());
        }
        for (int first = 0; first < badges.size(); first++) {
            for (int second = first + 1; second < badges.size(); second++) {
                assertState(
                        !overlaps(badges.get(first).bounds(), badges.get(second).bounds()),
                        "Compact Architect workflow badges overlap: "
                                + badges.get(first).text().getString() + " / "
                                + badges.get(second).text().getString());
            }
        }
    }

    private static boolean overlaps(
            TerrainwrightScreenLayout.Bounds first, TerrainwrightScreenLayout.Bounds second) {
        return first.x() < second.right()
                && first.right() > second.x()
                && first.y() < second.bottom()
                && first.bottom() > second.y();
    }

    private static void assertState(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
