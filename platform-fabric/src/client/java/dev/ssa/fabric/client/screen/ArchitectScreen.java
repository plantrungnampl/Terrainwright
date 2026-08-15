package dev.ssa.fabric.client.screen;

import dev.ssa.architect.model.EntrancePreference;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.StyleId;
import dev.ssa.fabric.client.preview.PreviewClientState;
import dev.ssa.fabric.network.PreviewPayloads.ConfirmPreview;
import dev.ssa.fabric.network.PreviewPayloads.PreviewResult;
import dev.ssa.fabric.network.PreviewPayloads.RequestPreview;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ArchitectScreen extends Screen {
    private static final String KEY = "screen.smart_survival_architect.architect.";
    private static final List<StyleId> STYLES = List.of(
            StyleId.parse("smart_survival_architect:medieval"),
            StyleId.parse("smart_survival_architect:japanese"),
            StyleId.parse("smart_survival_architect:modern"));
    private static final int[][] SIZES = {{9, 11}, {15, 19}, {21, 25}};

    private final PreviewClientState previewState;
    private final Runnable selectSite;
    private final Runnable selectHut;
    private int styleIndex;
    private int sizeIndex = 1;
    private int floors = 2;
    private int bedrooms = 2;
    private boolean kitchen = true;
    private boolean storage = true;
    private boolean balcony = true;
    private boolean chimney;
    private int entranceIndex;
    private long seed;
    private int requestedRotation;
    private TerrainwrightScreenLayout.ArchitectLayout layout;
    private TerrainwrightButton generateButton;
    private TerrainwrightButton selectHutButton;
    private TerrainwrightButton rotateButton;
    private TerrainwrightButton moveButton;
    private TerrainwrightButton confirmButton;
    private StringWidget recoveryText;
    private boolean renderedCanRequest;
    private PreviewResult renderedPreview;
    private boolean renderedConfirmation;
    private PreviewResult synchronizedPreview;

    public ArchitectScreen(PreviewClientState previewState) {
        this(previewState, () -> {}, () -> {});
    }

    public ArchitectScreen(PreviewClientState previewState, Runnable selectSite, Runnable selectHut) {
        super(component("title"));
        this.previewState = java.util.Objects.requireNonNull(previewState, "previewState");
        this.selectSite = java.util.Objects.requireNonNull(selectSite, "selectSite");
        this.selectHut = java.util.Objects.requireNonNull(selectHut, "selectHut");
    }

    public void selectHut(UUID hutId) {
        previewState.selectHut(hutId);
    }

    @Override
    protected void init() {
        layout = TerrainwrightScreenLayout.architect(width, height);
        synchronizeStyleWithAuthoritativePreview();
        addConfigurationControls();
        addSecondaryActions();
        addWorkflowActions();
        addRecoveryGuidance();
        rememberRenderedAuthority();
        updateActions();
    }

    @Override
    public void tick() {
        if (authorityChanged()) {
            rebuildWidgets();
            return;
        }
        updateActions();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, TerrainwrightUiTheme.WORLD_VEIL);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        drawWorkflowRail(graphics);
        drawConfigurationRail(graphics);
        drawWorldCanvas(graphics);
        drawStatusRail(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void addConfigurationControls() {
        TerrainwrightScreenLayout.Bounds rail = layout.configurationRail();
        int padding = 3;
        int gap = layout.compact() ? 2 : 3;
        int rowHeight = Math.max(9, Math.min(20, (rail.height() - padding * 2 - gap * 5) / 6));
        int innerWidth = rail.width() - padding * 2;
        int halfWidth = (innerWidth - gap) / 2;
        boolean compactPairLabels = layout.compact() || halfWidth < 80;
        int x = rail.x() + padding;
        int y = rail.y() + padding;

        addConfigButton(
                new TerrainwrightScreenLayout.Bounds(x, y, innerWidth, rowHeight),
                fieldLabel("style", styleOption(), layout.compact()),
                component("tooltip.style", styleOption()),
                () -> {
                    styleIndex = (styleIndex + 1) % STYLES.size();
                    rebuildWidgets();
                });
        y += rowHeight + gap;
        addConfigButton(
                new TerrainwrightScreenLayout.Bounds(x, y, innerWidth, rowHeight),
                fieldLabel("size", sizeValue(), layout.compact()),
                component("tooltip.size", sizeValue()),
                () -> {
                    sizeIndex = (sizeIndex + 1) % SIZES.length;
                    rebuildWidgets();
                });
        y += rowHeight + gap;
        addConfigPair(
                x,
                y,
                halfWidth,
                rowHeight,
                gap,
                fieldLabel("floors", floors, compactPairLabels),
                component("tooltip.floors", floors),
                () -> {
                    floors = floors % HouseRequirements.MAX_FLOORS + 1;
                    rebuildWidgets();
                },
                fieldLabel("bedrooms", bedrooms, compactPairLabels),
                component("tooltip.bedrooms", bedrooms),
                () -> {
                    bedrooms = (bedrooms + 1) % (HouseRequirements.MAX_BEDROOMS + 1);
                    rebuildWidgets();
                });
        y += rowHeight + gap;
        addConfigPair(
                x,
                y,
                halfWidth,
                rowHeight,
                gap,
                toggleLabel("kitchen", kitchen, compactPairLabels),
                component("tooltip.kitchen", toggleOption(kitchen)),
                () -> {
                    kitchen = !kitchen;
                    rebuildWidgets();
                },
                toggleLabel("storage", storage, compactPairLabels),
                component("tooltip.storage", toggleOption(storage)),
                () -> {
                    storage = !storage;
                    rebuildWidgets();
                });
        y += rowHeight + gap;
        addConfigPair(
                x,
                y,
                halfWidth,
                rowHeight,
                gap,
                toggleLabel("balcony", balcony, compactPairLabels),
                component("tooltip.balcony", toggleOption(balcony)),
                () -> {
                    balcony = !balcony;
                    rebuildWidgets();
                },
                toggleLabel("chimney", chimney, compactPairLabels),
                component("tooltip.chimney", toggleOption(chimney)),
                () -> {
                    chimney = !chimney;
                    rebuildWidgets();
                });
        y += rowHeight + gap;
        addConfigButton(
                new TerrainwrightScreenLayout.Bounds(x, y, innerWidth, rowHeight),
                fieldLabel("entrance", entranceOption(), layout.compact()),
                component("tooltip.entrance", entranceOption()),
                () -> {
                    entranceIndex = (entranceIndex + 1) % EntrancePreference.values().length;
                    rebuildWidgets();
                });
    }

    private void addConfigPair(
            int x,
            int y,
            int width,
            int height,
            int gap,
            Component leftLabel,
            Component leftTooltip,
            Runnable leftAction,
            Component rightLabel,
            Component rightTooltip,
            Runnable rightAction) {
        addConfigButton(
                new TerrainwrightScreenLayout.Bounds(x, y, width, height),
                leftLabel,
                leftTooltip,
                leftAction);
        addConfigButton(
                new TerrainwrightScreenLayout.Bounds(x + width + gap, y, width, height),
                rightLabel,
                rightTooltip,
                rightAction);
    }

    private void addConfigButton(
            TerrainwrightScreenLayout.Bounds bounds,
            Component message,
            Component tooltip,
            Runnable action) {
        TerrainwrightButton button =
                addButton(bounds, message, TerrainwrightButton.Style.NORMAL, action, tooltip);
        button.setTooltip(Tooltip.create(tooltip));
    }

    private void addSecondaryActions() {
        TerrainwrightScreenLayout.Bounds rail = layout.secondaryActions();
        TerrainwrightScreenLayout.Bounds canvas = layout.worldCanvas();
        int gap = layout.compact() ? 4 : 8;
        int actionWidth = (canvas.width() - gap) / 2;
        TerrainwrightScreenLayout.Bounds rotateBounds = new TerrainwrightScreenLayout.Bounds(
                canvas.x(), rail.y(), actionWidth, rail.height());
        TerrainwrightScreenLayout.Bounds moveBounds = new TerrainwrightScreenLayout.Bounds(
                canvas.x() + actionWidth + gap,
                rail.y(),
                canvas.width() - actionWidth - gap,
                rail.height());

        rotateButton = addButton(
                rotateBounds,
                actionLabel("rotate"),
                TerrainwrightButton.Style.NORMAL,
                () -> requestPreview(true),
                component("tooltip.rotate"));
        moveButton = addButton(
                moveBounds,
                actionLabel("move"),
                TerrainwrightButton.Style.NORMAL,
                selectSite,
                component("tooltip.move"));
    }

    private void addWorkflowActions() {
        boolean previewAvailable = previewState.preview().isPresent();
        boolean canRequest = previewState.canRequestPreview();
        boolean canConfirm = previewState.confirmation().isPresent();
        List<TerrainwrightScreenLayout.Bounds> slots = layout.actionSlots();

        addButton(
                slots.get(0),
                actionLabel("select_site"),
                primaryWhen(!previewAvailable && !canRequest),
                selectSite,
                component("tooltip.select_site"));
        String generateAction = previewAvailable ? "regenerate" : "generate";
        generateButton = addButton(
                slots.get(1),
                actionLabel(generateAction),
                primaryWhen(!previewAvailable && canRequest),
                () -> requestPreview(false),
                component("tooltip." + generateAction));
        selectHutButton = addButton(
                slots.get(2),
                actionLabel("select_hut"),
                primaryWhen(previewAvailable && !canConfirm),
                selectHut,
                component("tooltip.select_hut"));
        confirmButton = addButton(
                slots.get(3),
                actionLabel("confirm"),
                primaryWhen(canConfirm),
                () -> previewState.confirmation().ifPresent(ClientPlayNetworking::send),
                component("tooltip.confirm"));
    }

    private TerrainwrightButton addButton(
            TerrainwrightScreenLayout.Bounds bounds,
            Component message,
            TerrainwrightButton.Style style,
            Runnable action,
            Component compactTooltip) {
        TerrainwrightButton button = addRenderableWidget(new TerrainwrightButton(
                bounds.x(), bounds.y(), bounds.width(), bounds.height(), message, style, action));
        if (layout.compact()) {
            button.setTooltip(Tooltip.create(compactTooltip));
        }
        return button;
    }

    private TerrainwrightButton.Style primaryWhen(boolean condition) {
        return condition ? TerrainwrightButton.Style.PRIMARY : TerrainwrightButton.Style.NORMAL;
    }

    private void addRecoveryGuidance() {
        boolean generateRecovery = previewState.lastFailure().isEmpty()
                && (previewState.canRequestPreview() || previewState.preview().isPresent());
        TerrainwrightScreenLayout.Bounds anchor = layout.compact() && generateRecovery
                ? layout.statusRail()
                : layout.configurationRail();
        TerrainwrightScreenLayout.Bounds rail = layout.secondaryActions();
        recoveryText = addRenderableWidget(new StringWidget(
                anchor.x(),
                rail.y(),
                anchor.width(),
                rail.height(),
                siteRecovery(previewState.preview().isPresent(), previewState.canRequestPreview()),
                font));
    }

    private void drawWorkflowRail(GuiGraphicsExtractor graphics) {
        TerrainwrightScreenLayout.Bounds rail = layout.stepRail();
        TerrainwrightUiTheme.panel(graphics, rail);
        for (WorkflowBadge badge : workflowBadges()) {
            TerrainwrightUiTheme.statusBadge(
                    graphics, font, badge.bounds(), badge.text(), badge.stateColor());
        }
    }

    List<WorkflowBadge> workflowBadges() {
        TerrainwrightScreenLayout.Bounds rail = layout.stepRail();
        boolean previewAvailable = previewState.preview().isPresent();
        boolean canRequest = previewState.canRequestPreview();
        boolean canConfirm = previewState.confirmation().isPresent();
        WorkflowState[] states = {
            WorkflowState.READY,
            canRequest || previewAvailable ? WorkflowState.READY : WorkflowState.WAITING,
            previewAvailable
                    ? WorkflowState.READY
                    : canRequest ? WorkflowState.WAITING : WorkflowState.LOCKED,
            canConfirm
                    ? WorkflowState.READY
                    : previewAvailable ? WorkflowState.WAITING : WorkflowState.LOCKED,
            canConfirm ? WorkflowState.READY : WorkflowState.LOCKED
        };
        String[] steps = {"design", "site", "preview", "hut", "confirm"};
        int gap = 2;
        int slotWidth = (rail.width() - gap * (steps.length - 1)) / steps.length;
        List<WorkflowBadge> badges = new ArrayList<>(steps.length);
        for (int index = 0; index < steps.length; index++) {
            int x = rail.x() + index * (slotWidth + gap);
            int width = index == steps.length - 1 ? rail.right() - x : slotWidth;
            badges.add(new WorkflowBadge(
                    layout.compact()
                            ? component("workflow." + steps[index] + ".compact", states[index].label(true))
                            : component("workflow." + steps[index], states[index].label(false)),
                    new TerrainwrightScreenLayout.Bounds(x, rail.y(), width, rail.height()),
                    states[index].color));
        }
        return List.copyOf(badges);
    }

    record WorkflowBadge(Component text, TerrainwrightScreenLayout.Bounds bounds, int stateColor) {}

    private void drawConfigurationRail(GuiGraphicsExtractor graphics) {
        TerrainwrightScreenLayout.Bounds rail = layout.configurationRail();
        graphics.fill(rail.x(), rail.y(), rail.right(), rail.bottom(), TerrainwrightUiTheme.PARCHMENT_FILL);
        graphics.outline(rail.x(), rail.y(), rail.width(), rail.height(), TerrainwrightUiTheme.AGED_COPPER);
    }

    private void drawWorldCanvas(GuiGraphicsExtractor graphics) {
        TerrainwrightScreenLayout.Bounds canvas = layout.worldCanvas();
        drawCopperCorners(graphics, canvas);
        Optional<dev.ssa.fabric.network.PreviewPayloads.PreviewResult> preview = previewState.preview();
        if (preview.isEmpty()) {
            graphics.text(
                    font,
                    component("canvas.live" + (layout.compact() ? ".compact" : "")),
                    canvas.x() + 4,
                    canvas.y() + 4,
                    TerrainwrightUiTheme.WARM_OFF_WHITE);
            return;
        }

        var result = preview.orElseThrow();
        var blueprint = result.blueprint();
        int x = canvas.x() + 4;
        int y = canvas.y() + 4;
        int lineHeight = font.lineHeight + (layout.compact() ? 0 : 2);
        Component[] lines = {
            previewLabel("style", styleOption(blueprint.styleId())),
            previewLabel("floors", blueprint.floors()),
            previewLabel("blocks", blueprint.blocks().size()),
            previewLabel(
                    "terrain",
                    blueprint.terrainPlan().removedCount(),
                    blueprint.terrainPlan().filledCount()),
            previewLabel("origin", result.origin().toShortString()),
            previewLabel("rotation", result.rotation())
        };
        for (Component line : lines) {
            graphics.text(font, line, x, y, TerrainwrightUiTheme.WARM_OFF_WHITE);
            y += lineHeight;
        }
    }

    private void drawCopperCorners(
            GuiGraphicsExtractor graphics, TerrainwrightScreenLayout.Bounds bounds) {
        int length = Math.max(4, Math.min(12, Math.min(bounds.width(), bounds.height()) / 4));
        int color = TerrainwrightUiTheme.AGED_COPPER;
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + length, bounds.y() + 1, color);
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + 1, bounds.y() + length, color);
        graphics.fill(bounds.right() - length, bounds.y(), bounds.right(), bounds.y() + 1, color);
        graphics.fill(bounds.right() - 1, bounds.y(), bounds.right(), bounds.y() + length, color);
        graphics.fill(bounds.x(), bounds.bottom() - 1, bounds.x() + length, bounds.bottom(), color);
        graphics.fill(bounds.x(), bounds.bottom() - length, bounds.x() + 1, bounds.bottom(), color);
        graphics.fill(bounds.right() - length, bounds.bottom() - 1, bounds.right(), bounds.bottom(), color);
        graphics.fill(bounds.right() - 1, bounds.bottom() - length, bounds.right(), bounds.bottom(), color);
    }

    private void drawStatusRail(GuiGraphicsExtractor graphics) {
        TerrainwrightScreenLayout.Bounds rail = layout.statusRail();
        int gap = layout.compact() ? 2 : 4;
        int cardHeight = (rail.height() - gap) / 2;
        boolean previewAvailable = previewState.preview().isPresent();
        boolean canRequest = previewState.canRequestPreview();
        boolean canConfirm = previewState.confirmation().isPresent();
        WorkflowState siteState = canRequest || previewAvailable
                ? WorkflowState.READY
                : WorkflowState.WAITING;
        WorkflowState hutState = canConfirm
                ? WorkflowState.READY
                : previewAvailable ? WorkflowState.WAITING : WorkflowState.LOCKED;

        drawStatusCard(
                graphics,
                new TerrainwrightScreenLayout.Bounds(rail.x(), rail.y(), rail.width(), cardHeight),
                "site",
                siteState);
        drawStatusCard(
                graphics,
                new TerrainwrightScreenLayout.Bounds(
                        rail.x(), rail.y() + cardHeight + gap, rail.width(), rail.height() - cardHeight - gap),
                "hut",
                hutState);
    }

    private void drawStatusCard(
            GuiGraphicsExtractor graphics,
            TerrainwrightScreenLayout.Bounds bounds,
            String name,
            WorkflowState state) {
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), TerrainwrightUiTheme.DARK_OAK_PANEL);
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + 3, bounds.bottom(), state.color);
        graphics.outline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), TerrainwrightUiTheme.AGED_COPPER);
        graphics.text(
                font,
                component(
                        "status." + name + (layout.compact() ? ".compact" : ""),
                        state.label(layout.compact())),
                bounds.x() + 5,
                bounds.y() + 4,
                TerrainwrightUiTheme.WARM_OFF_WHITE);
    }

    private Component siteRecovery(boolean previewAvailable, boolean canRequest) {
        Optional<dev.ssa.fabric.network.PreviewPayloads.PreviewFailure.Reason> failure =
                previewState.lastFailure();
        if (failure.isPresent() && !canRequest) {
            return component(
                    "failure." + failure.orElseThrow().name().toLowerCase(Locale.ROOT)
                            + (layout.compact() ? ".compact" : ""));
        }
        String recovery = previewAvailable
                ? "preview_ready"
                : canRequest ? "generate" : "select_site";
        return component("recovery." + recovery + (layout.compact() ? ".compact" : ""));
    }

    private void requestPreview(boolean rotate) {
        if (rotate) {
            requestedRotation = (requestedRotation + 90) % 360;
        } else if (previewState.preview().isPresent()) {
            seed++;
        }
        RequestPreview request = previewState.requestPreview(requirements(), requestedRotation);
        ClientPlayNetworking.send(request);
        updateActions();
    }

    private HouseRequirements requirements() {
        int[] size = SIZES[sizeIndex];
        return new HouseRequirements(
                STYLES.get(styleIndex),
                size[0],
                size[1],
                floors,
                bedrooms,
                kitchen,
                storage,
                balcony,
                chimney,
                EntrancePreference.values()[entranceIndex],
                seed);
    }

    private void synchronizeStyleWithAuthoritativePreview() {
        PreviewResult authoritativePreview = previewState.preview().orElse(null);
        if (java.util.Objects.equals(synchronizedPreview, authoritativePreview)) {
            return;
        }
        synchronizedPreview = authoritativePreview;
        if (authoritativePreview == null) {
            return;
        }
        int authoritativeIndex = STYLES.indexOf(authoritativePreview.blueprint().styleId());
        if (authoritativeIndex >= 0) {
            styleIndex = authoritativeIndex;
        }
    }

    private void updateActions() {
        if (generateButton == null) {
            return;
        }
        boolean canRequest = previewState.canRequestPreview();
        boolean previewAvailable = previewState.preview().isPresent();
        selectHutButton.active = previewAvailable;
        generateButton.active = canRequest;
        rotateButton.active = canRequest && previewAvailable;
        moveButton.active = previewAvailable;
        Optional<ConfirmPreview> confirmation = previewState.confirmation();
        confirmButton.active = confirmation.isPresent();
        recoveryText.setMessage(siteRecovery(previewAvailable, canRequest));
    }

    private boolean authorityChanged() {
        return renderedCanRequest != previewState.canRequestPreview()
                || !java.util.Objects.equals(renderedPreview, previewState.preview().orElse(null))
                || renderedConfirmation != previewState.confirmation().isPresent();
    }

    private void rememberRenderedAuthority() {
        renderedCanRequest = previewState.canRequestPreview();
        renderedPreview = previewState.preview().orElse(null);
        renderedConfirmation = previewState.confirmation().isPresent();
    }

    private Component actionLabel(String action) {
        return component("action." + action + (layout.compact() ? ".compact" : ""));
    }

    private Component fieldLabel(String field, Object value, boolean compact) {
        return component("field." + field + (compact ? ".compact" : ""), value);
    }

    private Component toggleLabel(String field, boolean enabled, boolean compact) {
        return fieldLabel(field, toggleOption(enabled), compact);
    }

    private Component previewLabel(String field, Object... values) {
        return component("preview." + field + (layout.compact() ? ".compact" : ""), values);
    }

    private Component styleOption() {
        return styleOption(STYLES.get(styleIndex));
    }

    private Component styleOption(StyleId style) {
        return component("option.style." + style.value().path());
    }

    private Component entranceOption() {
        return component("option.entrance."
                + EntrancePreference.values()[entranceIndex].name().toLowerCase(Locale.ROOT));
    }

    private Component toggleOption(boolean enabled) {
        return component(enabled ? "option.on" : "option.off");
    }

    private Component sizeValue() {
        return component("option.size", SIZES[sizeIndex][0], SIZES[sizeIndex][1]);
    }

    private static Component component(String suffix, Object... arguments) {
        return Component.translatable(KEY + suffix, arguments);
    }

    private enum WorkflowState {
        READY("ready", TerrainwrightUiTheme.STATE_GREEN),
        WAITING("waiting", TerrainwrightUiTheme.STATE_AMBER),
        LOCKED("locked", TerrainwrightUiTheme.STATE_GRAY);

        private final String key;
        private final int color;

        WorkflowState(String key, int color) {
            this.key = key;
            this.color = color;
        }

        private Component label(boolean compact) {
            return component("state." + key + (compact ? ".compact" : ""));
        }
    }
}
