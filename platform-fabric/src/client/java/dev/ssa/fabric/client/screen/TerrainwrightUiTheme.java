package dev.ssa.fabric.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public final class TerrainwrightUiTheme {
    public static final int WORLD_VEIL = 0x55000000;
    public static final int DARK_OAK_FRAME = 0xff241810;
    public static final int DARK_OAK_PANEL = 0xff34251a;
    public static final int PARCHMENT_FILL = 0xffe5d3a6;
    public static final int WARM_OFF_WHITE = 0xfff2e8d5;
    public static final int AGED_COPPER = 0xff9a6d46;
    public static final int MUTED_TEAL = 0xff3e7772;
    public static final int MUTED_TEAL_HIGHLIGHT = 0xff57938c;
    public static final int STATE_GREEN = 0xff5c8a5a;
    public static final int STATE_AMBER = 0xffc28a3c;
    public static final int STATE_RED = 0xffa84d43;
    public static final int STATE_GRAY = 0xff6e6a63;

    private TerrainwrightUiTheme() {}

    public static void panel(
            GuiGraphicsExtractor graphics, TerrainwrightScreenLayout.Bounds bounds) {
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), DARK_OAK_PANEL);
        graphics.outline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), AGED_COPPER);
    }

    public static void divider(
            GuiGraphicsExtractor graphics, int x, int y, int width) {
        graphics.fill(x, y, x + width, y + 1, AGED_COPPER);
    }

    public static void progressBar(
            GuiGraphicsExtractor graphics,
            TerrainwrightScreenLayout.Bounds bounds,
            double progress) {
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), DARK_OAK_FRAME);
        int innerWidth = Math.max(0, bounds.width() - 2);
        int filledWidth = (int) Math.round(innerWidth * Math.clamp(progress, 0.0, 1.0));
        if (filledWidth > 0) {
            graphics.fill(
                    bounds.x() + 1,
                    bounds.y() + 1,
                    bounds.x() + 1 + filledWidth,
                    bounds.bottom() - 1,
                    MUTED_TEAL);
        }
        graphics.outline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), AGED_COPPER);
    }

    public static void statusBadge(
            GuiGraphicsExtractor graphics,
            Font font,
            TerrainwrightScreenLayout.Bounds bounds,
            Component text,
            int stateColor) {
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), stateColor);
        graphics.outline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), AGED_COPPER);
        graphics.centeredText(
                font,
                text,
                bounds.x() + bounds.width() / 2,
                bounds.y() + (bounds.height() - font.lineHeight) / 2,
                WARM_OFF_WHITE);
    }

    public static void label(
            GuiGraphicsExtractor graphics, Font font, Component text, int x, int y) {
        graphics.text(font, text, x, y, WARM_OFF_WHITE);
    }

    static void button(
            GuiGraphicsExtractor graphics,
            Font font,
            TerrainwrightScreenLayout.Bounds bounds,
            Component text,
            TerrainwrightButton.Style style,
            boolean active,
            boolean highlighted) {
        int fill = buttonFill(style, active, highlighted);
        int outline = highlighted && active ? PARCHMENT_FILL : AGED_COPPER;
        int textColor = active ? WARM_OFF_WHITE : 0xffb7b0a6;
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), fill);
        graphics.outline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), outline);
        graphics.centeredText(
                font,
                text,
                bounds.x() + bounds.width() / 2,
                bounds.y() + (bounds.height() - font.lineHeight) / 2,
                textColor);
    }

    private static int buttonFill(
            TerrainwrightButton.Style style, boolean active, boolean highlighted) {
        if (!active) {
            return STATE_GRAY;
        }
        if (highlighted && (style == TerrainwrightButton.Style.NORMAL
                || style == TerrainwrightButton.Style.PRIMARY)) {
            return MUTED_TEAL_HIGHLIGHT;
        }
        return switch (style) {
            case NORMAL -> DARK_OAK_PANEL;
            case PRIMARY -> MUTED_TEAL;
            case CAUTION -> STATE_AMBER;
            case DANGER -> STATE_RED;
        };
    }
}
