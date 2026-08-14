package dev.ssa.fabric.client.screen;

import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class TerrainwrightButton extends AbstractWidget {
    private final Runnable action;
    private final Style style;

    public TerrainwrightButton(
            int x,
            int y,
            int width,
            int height,
            Component message,
            Style style,
            Runnable action) {
        super(x, y, width, height, Objects.requireNonNull(message, "message"));
        this.style = Objects.requireNonNull(style, "style");
        this.action = Objects.requireNonNull(action, "action");
    }

    @Override
    protected void extractWidgetRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        TerrainwrightUiTheme.button(
                graphics,
                Minecraft.getInstance().font,
                new TerrainwrightScreenLayout.Bounds(getX(), getY(), getWidth(), getHeight()),
                getMessage(),
                style,
                active,
                isHoveredOrFocused());
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        action.run();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active || !event.isConfirmation()) {
            return false;
        }
        playDownSound(Minecraft.getInstance().getSoundManager());
        action.run();
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    public enum Style {
        NORMAL,
        PRIMARY,
        CAUTION,
        DANGER
    }
}
