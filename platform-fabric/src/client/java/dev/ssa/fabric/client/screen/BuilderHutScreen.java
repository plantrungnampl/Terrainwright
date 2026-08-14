package dev.ssa.fabric.client.screen;

import dev.ssa.construction.job.BuildJobState;
import dev.ssa.fabric.client.job.JobClientState;
import dev.ssa.fabric.network.JobPayloads.JobCommand;
import dev.ssa.fabric.network.JobPayloads.JobSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public final class BuilderHutScreen extends Screen {
    private static final String KEY = "screen.smart_survival_architect.builder_hut.";
    private static final int REFRESH_INTERVAL_TICKS = 20;

    private final JobClientState jobState;
    private final Runnable refresh;
    private final Runnable beginChestSelection;
    private int refreshTicks;
    private TerrainwrightScreenLayout.BuilderLayout layout;
    private TerrainwrightButton pauseButton;
    private TerrainwrightButton resumeButton;
    private TerrainwrightButton stopButton;
    private TerrainwrightButton undoButton;
    private TerrainwrightButton linkChestButton;
    private StringWidget guidanceText;
    private boolean renderedCanLinkChest;

    public BuilderHutScreen(JobClientState jobState, Runnable refresh, Runnable beginChestSelection) {
        super(component("title"));
        this.jobState = Objects.requireNonNull(jobState, "jobState");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.beginChestSelection = Objects.requireNonNull(beginChestSelection, "beginChestSelection");
    }

    @Override
    protected void init() {
        layout = TerrainwrightScreenLayout.builder(width, height);
        addActionRail();
        addGuidance();
        renderedCanLinkChest = canLinkChest();
        updateActions();
    }

    @Override
    public void tick() {
        if (--refreshTicks <= 0) {
            refresh.run();
            refreshTicks = REFRESH_INTERVAL_TICKS;
        }
        if (renderedCanLinkChest != canLinkChest()) {
            rebuildWidgets();
            return;
        }
        updateActions();
        guidanceText.setMessage(currentGuidance());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, TerrainwrightUiTheme.WORLD_VEIL);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        drawHeader(graphics);
        drawProgress(graphics);
        drawMaterials(graphics);
        drawDiagnostics(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void addActionRail() {
        List<TerrainwrightScreenLayout.Bounds> slots = actionSlots();
        pauseButton = addAction(slots.get(0), "pause", TerrainwrightButton.Style.NORMAL, JobCommand.Action.PAUSE);
        resumeButton = addAction(slots.get(1), "resume", TerrainwrightButton.Style.NORMAL, JobCommand.Action.RESUME);
        stopButton = addAction(slots.get(2), "stop", TerrainwrightButton.Style.CAUTION, JobCommand.Action.STOP);
        undoButton = addAction(slots.get(3), "safe_undo", TerrainwrightButton.Style.DANGER, JobCommand.Action.UNDO);
        linkChestButton = addRenderableWidget(new TerrainwrightButton(
                slots.get(4).x(),
                slots.get(4).y(),
                slots.get(4).width(),
                slots.get(4).height(),
                component("action.link_chest"),
                canLinkChest() ? TerrainwrightButton.Style.PRIMARY : TerrainwrightButton.Style.NORMAL,
                beginChestSelection));
    }

    private List<TerrainwrightScreenLayout.Bounds> actionSlots() {
        TerrainwrightScreenLayout.Bounds rail = layout.actionRail();
        int gap = layout.compact() ? 4 : 8;
        if (layout.compact()) {
            int firstRowWidth = (rail.width() - gap * 2) / 3;
            int secondRowY = rail.y() + 20 + gap;
            int undoWidth = firstRowWidth;
            return List.of(
                    new TerrainwrightScreenLayout.Bounds(rail.x(), rail.y(), firstRowWidth, 20),
                    new TerrainwrightScreenLayout.Bounds(
                            rail.x() + firstRowWidth + gap, rail.y(), firstRowWidth, 20),
                    new TerrainwrightScreenLayout.Bounds(
                            rail.x() + (firstRowWidth + gap) * 2,
                            rail.y(),
                            rail.right() - (rail.x() + (firstRowWidth + gap) * 2),
                            20),
                    new TerrainwrightScreenLayout.Bounds(rail.x(), secondRowY, undoWidth, 20),
                    new TerrainwrightScreenLayout.Bounds(
                            rail.x() + undoWidth + gap,
                            secondRowY,
                            rail.width() - undoWidth - gap,
                            20));
        }

        int linkWidth = Math.min(180, Math.max(110, rail.width() / 3));
        int commandWidth = (rail.width() - linkWidth - gap * 4) / 4;
        List<TerrainwrightScreenLayout.Bounds> slots = new ArrayList<>(5);
        for (int index = 0; index < 4; index++) {
            slots.add(new TerrainwrightScreenLayout.Bounds(
                    rail.x() + index * (commandWidth + gap), rail.y(), commandWidth, rail.height()));
        }
        int linkX = rail.x() + 4 * (commandWidth + gap);
        slots.add(new TerrainwrightScreenLayout.Bounds(
                linkX, rail.y(), rail.right() - linkX, rail.height()));
        return List.copyOf(slots);
    }

    private TerrainwrightButton addAction(
            TerrainwrightScreenLayout.Bounds bounds,
            String label,
            TerrainwrightButton.Style style,
            JobCommand.Action action) {
        return addRenderableWidget(new TerrainwrightButton(
                bounds.x(),
                bounds.y(),
                bounds.width(),
                bounds.height(),
                component("action." + label),
                style,
                () -> send(action)));
    }

    private void addGuidance() {
        TerrainwrightScreenLayout.Bounds progress = layout.progress();
        int progressWidth = Math.max(96, progress.width() / 3);
        int guidanceX = progress.x() + progressWidth + 8;
        guidanceText = addRenderableWidget(new StringWidget(
                guidanceX,
                progress.y(),
                progress.right() - guidanceX - 4,
                progress.height(),
                currentGuidance(),
                font));
    }

    private void drawHeader(GuiGraphicsExtractor graphics) {
        TerrainwrightScreenLayout.Bounds header = layout.header();
        TerrainwrightUiTheme.panel(graphics, header);
        int padding = 4;
        int third = header.width() / 3;
        int textY = header.y() + Math.max(1, (header.height() - font.lineHeight) / 2);

        drawClippedLabel(graphics, title, header.x() + padding, textY, third - padding * 2);
        JobSnapshot snapshot = jobState.snapshot().orElse(null);
        Component state = snapshot == null
                ? component("state.none")
                : component("state.current", stateName(snapshot.state()));
        drawClippedLabel(graphics, state, header.x() + third + padding, textY, third - padding * 2);

        Component chest = jobState.hutSnapshot()
                .map(hut -> component(hut.chestLinked() ? "chest.linked" : "chest.unlinked"))
                .orElseGet(() -> component("chest.unavailable"));
        drawClippedLabel(graphics, chest, header.x() + third * 2 + padding, textY, header.width() - third * 2 - padding * 2);
    }

    private void drawClippedLabel(
            GuiGraphicsExtractor graphics, Component text, int x, int y, int availableWidth) {
        graphics.enableScissor(x, y, x + availableWidth, y + font.lineHeight);
        graphics.text(font, text, x, y, TerrainwrightUiTheme.WARM_OFF_WHITE);
        graphics.disableScissor();
    }

    private void drawProgress(GuiGraphicsExtractor graphics) {
        TerrainwrightScreenLayout.Bounds progress = layout.progress();
        TerrainwrightUiTheme.panel(graphics, progress);
        JobSnapshot snapshot = jobState.snapshot().orElse(null);
        if (snapshot == null) {
            TerrainwrightUiTheme.label(graphics, font, component("progress.none"), progress.x() + 4, progress.y() + 5);
            return;
        }

        int progressWidth = Math.max(96, progress.width() / 3);
        TerrainwrightUiTheme.label(
                graphics,
                font,
                component("progress", snapshot.completedTasks(), snapshot.totalTasks()),
                progress.x() + 4,
                progress.y() + 2);
        double completion = snapshot.totalTasks() == 0
                ? 0.0
                : (double) snapshot.completedTasks() / snapshot.totalTasks();
        TerrainwrightUiTheme.progressBar(
                graphics,
                new TerrainwrightScreenLayout.Bounds(
                        progress.x() + 4, progress.bottom() - 6, progressWidth - 8, 4),
                completion);
    }

    private void drawMaterials(GuiGraphicsExtractor graphics) {
        TerrainwrightScreenLayout.Bounds materials = layout.materials();
        TerrainwrightUiTheme.panel(graphics, materials);
        TerrainwrightUiTheme.label(graphics, font, component("materials.heading"), materials.x() + 4, materials.y() + 3);
        JobSnapshot snapshot = jobState.snapshot().orElse(null);
        if (snapshot == null || snapshot.missingMaterials().isEmpty()) {
            TerrainwrightUiTheme.label(graphics, font, component("materials.none"), materials.x() + 4, materials.y() + 17);
            return;
        }

        int rowY = materials.y() + 15;
        graphics.enableScissor(materials.x() + 1, rowY, materials.right() - 1, materials.bottom() - 1);
        for (Map.Entry<String, Integer> entry : snapshot.missingMaterials().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(4)
                .toList()) {
            renderMaterial(graphics, materials.x() + 4, rowY, entry);
            rowY += 16;
        }
        graphics.disableScissor();
    }

    private void renderMaterial(
            GuiGraphicsExtractor graphics, int x, int y, Map.Entry<String, Integer> entry) {
        Identifier identifier = Identifier.tryParse(entry.getKey());
        if (identifier != null) {
            var item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
            if (item != null) {
                ItemStack stack = new ItemStack(item);
                graphics.item(stack, x, y);
                graphics.text(
                        font,
                        component("material.count", stack.getHoverName(), entry.getValue()),
                        x + 20,
                        y + 4,
                        TerrainwrightUiTheme.WARM_OFF_WHITE);
                return;
            }
        }
        graphics.text(
                font,
                component("material.fallback", entry.getKey(), entry.getValue()),
                x,
                y + 4,
                TerrainwrightUiTheme.WARM_OFF_WHITE);
    }

    private void drawDiagnostics(GuiGraphicsExtractor graphics) {
        TerrainwrightScreenLayout.Bounds diagnostics = layout.diagnostics();
        TerrainwrightUiTheme.panel(graphics, diagnostics);
        graphics.enableScissor(
                diagnostics.x() + 1,
                diagnostics.y() + 1,
                diagnostics.right() - 1,
                diagnostics.bottom() - 1);
        JobSnapshot snapshot = jobState.snapshot().orElse(null);
        int x = diagnostics.x() + 4;
        int y = diagnostics.y() + 3;
        int textWidth = diagnostics.width() - 8;
        int conflicts = snapshot == null ? 0 : snapshot.conflicts().size();
        TerrainwrightUiTheme.label(graphics, font, component("conflicts.heading", conflicts), x, y);
        y += 14;

        int diagnosticCount = snapshot == null ? 0 : snapshot.diagnostics().size();
        TerrainwrightUiTheme.label(graphics, font, component("diagnostics.heading", diagnosticCount), x, y);
        y += 13;
        if (snapshot != null && !snapshot.diagnostics().isEmpty()) {
            var diagnostic = snapshot.diagnostics().getLast();
            TerrainwrightUiTheme.label(
                    graphics,
                    font,
                    component(diagnostic.recoverable() ? "diagnostics.recoverable" : "diagnostics.review"),
                    x,
                    y);
            y += 12;
            graphics.textWithWordWrap(
                    font,
                    component("diagnostics.latest", diagnostic.code(), diagnostic.message()),
                    x,
                    y,
                    textWidth,
                    TerrainwrightUiTheme.WARM_OFF_WHITE);
        }

        int resultY = diagnostics.bottom() - font.lineHeight - 3;
        var commandResult = jobState.lastCommandResult().orElse(null);
        if (commandResult != null) {
            Component result = commandResult.accepted()
                    ? component("result.command.accepted")
                    : component(
                            "result.command.rejected",
                            component("result.command.rejection."
                                    + commandResult.rejection().name().toLowerCase(Locale.ROOT)),
                            commandResult.currentRevision());
            graphics.text(font, result, x, resultY, commandResult.accepted()
                    ? TerrainwrightUiTheme.STATE_GREEN
                    : TerrainwrightUiTheme.STATE_RED);
            resultY -= font.lineHeight + 2;
        }
        var chestResult = jobState.lastChestLinkResult().orElse(null);
        if (chestResult != null) {
            Component result = chestResult.accepted()
                    ? component("result.chest_link.accepted")
                    : component(
                            "result.chest_link.rejected",
                            component("result.chest_link.failure."
                                    + chestResult.failure().name().toLowerCase(Locale.ROOT)));
            graphics.text(font, result, x, resultY, chestResult.accepted()
                    ? TerrainwrightUiTheme.STATE_GREEN
                    : TerrainwrightUiTheme.STATE_RED);
        }
        graphics.disableScissor();
    }

    private void send(JobCommand.Action action) {
        jobState.snapshot().ifPresent(snapshot -> ClientPlayNetworking.send(new JobCommand(
                snapshot.jobId(), snapshot.revision(), action)));
    }

    private void updateActions() {
        if (pauseButton == null) {
            return;
        }
        BuildJobState state = jobState.snapshot().map(JobSnapshot::state).orElse(null);
        pauseButton.active = state != null
                && state != BuildJobState.PAUSED
                && state.canTransitionTo(BuildJobState.PAUSED);
        resumeButton.active = state == BuildJobState.PAUSED;
        stopButton.active = state != null
                && state != BuildJobState.STOPPING
                && state.canTransitionTo(BuildJobState.STOPPING);
        undoButton.active = state == BuildJobState.STOPPED || state == BuildJobState.COMPLETED;
        linkChestButton.active = jobState.hutSnapshot().isPresent()
                && (state == null || state == BuildJobState.PAUSED_NO_CHEST);
    }

    private boolean canLinkChest() {
        BuildJobState state = jobState.snapshot().map(JobSnapshot::state).orElse(null);
        return jobState.hutSnapshot().isPresent()
                && (state == null || state == BuildJobState.PAUSED_NO_CHEST);
    }

    private Component currentGuidance() {
        JobSnapshot snapshot = jobState.snapshot().orElse(null);
        if (snapshot == null) {
            return component("guidance.no_active_job");
        }
        if (snapshot.state() == BuildJobState.WAIT_MATERIAL && snapshot.missingMaterials().isEmpty()) {
            return component("guidance.wait_material_unavailable");
        }
        return component("guidance." + snapshot.state().name().toLowerCase(Locale.ROOT));
    }

    private static Component stateName(BuildJobState state) {
        return Component.translatable("terrainwright.job.state." + state.name().toLowerCase(Locale.ROOT));
    }

    private static Component component(String suffix, Object... arguments) {
        return Component.translatable(KEY + suffix, arguments);
    }
}
