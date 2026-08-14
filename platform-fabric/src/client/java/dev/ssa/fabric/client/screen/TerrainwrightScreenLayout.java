package dev.ssa.fabric.client.screen;

import java.util.ArrayList;
import java.util.List;

public final class TerrainwrightScreenLayout {
    private static final int MIN_WIDTH = 320;
    private static final int MIN_HEIGHT = 180;
    private static final int REGULAR_MIN_WIDTH = 378;
    private static final int REGULAR_MIN_HEIGHT = 226;
    private static final int CONTROL_HEIGHT = 20;

    private TerrainwrightScreenLayout() {}

    public static ArchitectLayout architect(int width, int height) {
        validateViewport(width, height);
        boolean compact = compact(width, height);
        int margin = compact ? 4 : 8;
        int gap = margin;
        int topRailHeight = compact ? 16 : 20;
        int actionRailHeight = compact ? CONTROL_HEIGHT * 2 + gap : CONTROL_HEIGHT;
        int contentWidth = width - margin * 2;

        Bounds stepRail = new Bounds(margin, margin, contentWidth, topRailHeight);
        Bounds actionRail = new Bounds(
                margin, height - margin - actionRailHeight, contentWidth, actionRailHeight);
        Bounds secondaryActions = new Bounds(
                margin,
                actionRail.y() - gap - CONTROL_HEIGHT,
                contentWidth,
                CONTROL_HEIGHT);

        int contentTop = stepRail.bottom() + gap;
        int contentHeight = secondaryActions.y() - gap - contentTop;
        int sideWidth = compact
                ? 88
                : Math.min(144, (contentWidth - gap * 2) / 4);
        Bounds configurationRail = new Bounds(margin, contentTop, sideWidth, contentHeight);
        Bounds worldCanvas = new Bounds(
                configurationRail.right() + gap,
                contentTop,
                contentWidth - sideWidth * 2 - gap * 2,
                contentHeight);
        Bounds statusRail = new Bounds(
                worldCanvas.right() + gap, contentTop, sideWidth, contentHeight);

        return new ArchitectLayout(
                stepRail,
                configurationRail,
                worldCanvas,
                statusRail,
                secondaryActions,
                actionRail,
                architectActionSlots(actionRail, compact, gap),
                compact);
    }

    public static BuilderLayout builder(int width, int height) {
        validateViewport(width, height);
        boolean compact = compact(width, height);
        int margin = compact ? 4 : 8;
        int gap = margin;
        int topRailHeight = compact ? 16 : 20;
        int actionRailHeight = compact ? CONTROL_HEIGHT * 2 + gap : CONTROL_HEIGHT;
        int contentWidth = width - margin * 2;

        Bounds header = new Bounds(margin, margin, contentWidth, topRailHeight);
        Bounds actionRail = new Bounds(
                margin, height - margin - actionRailHeight, contentWidth, actionRailHeight);
        Bounds progress = new Bounds(
                margin, header.bottom() + gap, contentWidth, CONTROL_HEIGHT);
        int detailTop = progress.bottom() + gap;
        int detailHeight = actionRail.y() - gap - detailTop;
        int materialsWidth = (contentWidth - gap) / 2;
        Bounds materials = new Bounds(margin, detailTop, materialsWidth, detailHeight);
        Bounds diagnostics = new Bounds(
                materials.right() + gap,
                detailTop,
                contentWidth - materialsWidth - gap,
                detailHeight);

        return new BuilderLayout(header, progress, materials, diagnostics, actionRail, compact);
    }

    private static List<Bounds> architectActionSlots(Bounds rail, boolean compact, int gap) {
        int columns = compact ? 2 : 4;
        int rows = compact ? 2 : 1;
        int slotWidth = (rail.width() - gap * (columns - 1)) / columns;
        List<Bounds> slots = new ArrayList<>(4);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                slots.add(new Bounds(
                        rail.x() + column * (slotWidth + gap),
                        rail.y() + row * (CONTROL_HEIGHT + gap),
                        slotWidth,
                        CONTROL_HEIGHT));
            }
        }
        return List.copyOf(slots);
    }

    private static boolean compact(int width, int height) {
        return width < REGULAR_MIN_WIDTH || height < REGULAR_MIN_HEIGHT;
    }

    private static void validateViewport(int width, int height) {
        if (width < MIN_WIDTH || height < MIN_HEIGHT) {
            throw new IllegalArgumentException(
                    "Terrainwright screens require at least " + MIN_WIDTH + "x" + MIN_HEIGHT);
        }
    }

    public record Bounds(int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        boolean fitsInside(int viewportWidth, int viewportHeight) {
            return x >= 0
                    && y >= 0
                    && width > 0
                    && height > 0
                    && right() <= viewportWidth
                    && bottom() <= viewportHeight;
        }

        boolean overlaps(Bounds other) {
            return x < other.right()
                    && right() > other.x
                    && y < other.bottom()
                    && bottom() > other.y;
        }
    }

    public record ArchitectLayout(
            Bounds stepRail,
            Bounds configurationRail,
            Bounds worldCanvas,
            Bounds statusRail,
            Bounds secondaryActions,
            Bounds actionRail,
            List<Bounds> actionSlots,
            boolean compact) {
        public ArchitectLayout {
            actionSlots = List.copyOf(actionSlots);
        }
    }

    public record BuilderLayout(
            Bounds header,
            Bounds progress,
            Bounds materials,
            Bounds diagnostics,
            Bounds actionRail,
            boolean compact) {}
}
