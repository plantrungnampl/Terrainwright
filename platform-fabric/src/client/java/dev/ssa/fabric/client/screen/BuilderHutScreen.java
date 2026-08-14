package dev.ssa.fabric.client.screen;

import dev.ssa.construction.job.BuildJobState;
import dev.ssa.fabric.client.job.JobClientState;
import dev.ssa.fabric.network.JobPayloads.JobCommand;
import dev.ssa.fabric.network.JobPayloads.JobSnapshot;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class BuilderHutScreen extends Screen {
    private static final int REFRESH_INTERVAL_TICKS = 20;

    private final JobClientState jobState;
    private final Runnable refresh;
    private final Runnable beginChestSelection;
    private int refreshTicks;
    private Button pauseButton;
    private Button resumeButton;
    private Button stopButton;
    private Button undoButton;
    private Button linkChestButton;

    public BuilderHutScreen(JobClientState jobState, Runnable refresh, Runnable beginChestSelection) {
        super(Component.literal("Builder Hut"));
        this.jobState = Objects.requireNonNull(jobState, "jobState");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.beginChestSelection = Objects.requireNonNull(beginChestSelection, "beginChestSelection");
    }

    @Override
    protected void init() {
        int left = width / 2 - 155;
        int top = Math.max(34, height / 2 - 100);
        pauseButton = addRenderableWidget(commandButton(
                left, top + 50, "Pause", JobCommand.Action.PAUSE));
        resumeButton = addRenderableWidget(commandButton(
                left + 80, top + 50, "Resume", JobCommand.Action.RESUME));
        stopButton = addRenderableWidget(commandButton(
                left + 160, top + 50, "Stop", JobCommand.Action.STOP));
        undoButton = addRenderableWidget(commandButton(
                left + 240, top + 50, "Safe Undo", JobCommand.Action.UNDO));
        linkChestButton = addRenderableWidget(Button.builder(
                        Component.literal("Link / Relink Builder Chest"),
                        ignored -> beginChestSelection.run())
                .bounds(left, top + 76, 190, 20)
                .build());
        updateActions();
    }

    @Override
    public void tick() {
        if (--refreshTicks <= 0) {
            refresh.run();
            refreshTicks = REFRESH_INTERVAL_TICKS;
        }
        updateActions();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x88000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int left = width / 2 - 155;
        int top = Math.max(34, height / 2 - 100);
        graphics.centeredText(font, title, width / 2, top - 20, 0xffffff);
        var hut = jobState.hutSnapshot().orElse(null);
        if (hut != null) {
            graphics.text(
                    font,
                    "Builder Chest: " + (hut.chestLinked() ? "Linked" : "Not linked"),
                    left,
                    top,
                    hut.chestLinked() ? 0x9fe39f : 0xffc66d);
        }
        JobSnapshot snapshot = jobState.snapshot().orElse(null);
        if (snapshot == null) {
            graphics.text(font, "No owned active job was reported by this Hut.", left, top + 16, 0xffc66d);
            renderChestLinkResult(graphics, left, top + 106);
            return;
        }
        graphics.text(font, "State: " + snapshot.state().name(), left, top + 16, 0xb8d8ff);
        graphics.text(
                font,
                "Progress: " + snapshot.completedTasks() + " / " + snapshot.totalTasks()
                        + " | Revision: " + snapshot.revision(),
                left,
                top + 32,
                0xd0d0d0);

        int lineY = top + 106;
        if (!snapshot.missingMaterials().isEmpty()) {
            graphics.text(font, "Missing materials:", left, lineY, 0xffc66d);
            lineY += 14;
            for (Map.Entry<String, Integer> entry : snapshot.missingMaterials().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .limit(4)
                    .toList()) {
                graphics.text(font, entry.getKey() + " x" + entry.getValue(), left + 8, lineY, 0xffdca0);
                lineY += 14;
            }
        }
        if (!snapshot.conflicts().isEmpty()) {
            graphics.text(font, "Conflicts: " + snapshot.conflicts().size(), left, lineY, 0xff8a80);
            lineY += 14;
        }
        if (!snapshot.diagnostics().isEmpty()) {
            var diagnostic = snapshot.diagnostics().getLast();
            graphics.text(font, diagnostic.code() + ": " + diagnostic.message(), left, lineY, 0xffb0a8);
            lineY += 14;
        }
        String guidance = guidance(snapshot.state(), !snapshot.missingMaterials().isEmpty());
        if (!guidance.isEmpty()) {
            graphics.text(font, guidance, left, lineY, 0xffdca0);
            lineY += 14;
        }
        var commandResult = jobState.lastCommandResult().orElse(null);
        if (commandResult != null && !commandResult.accepted()) {
            graphics.text(
                    font,
                    "Command rejected: " + commandResult.rejection().name()
                            + " (server revision " + commandResult.currentRevision() + ")",
                    left,
                    lineY,
                    0xff7070);
            lineY += 14;
        }
        renderChestLinkResult(graphics, left, lineY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Button commandButton(int x, int y, String label, JobCommand.Action action) {
        return Button.builder(Component.literal(label), ignored -> send(action))
                .bounds(x, y, 70, 20)
                .build();
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

    private void renderChestLinkResult(GuiGraphicsExtractor graphics, int left, int lineY) {
        jobState.lastChestLinkResult().ifPresent(result -> graphics.text(
                font,
                result.accepted()
                        ? "Builder Chest linked."
                        : "Chest link rejected: " + result.failure().name(),
                left,
                lineY,
                result.accepted() ? 0x9fe39f : 0xff7070));
    }

    private static String guidance(BuildJobState state, boolean hasMissingMaterials) {
        return switch (state) {
            case WAIT_MATERIAL -> hasMissingMaterials
                    ? "Add the listed items to the linked Builder Chest."
                    : "The next material batch does not fit or is unavailable.";
            case PAUSED_NO_CHEST -> "Restore or relink the Builder Chest.";
            case PAUSED_CONFLICT -> "Resolve the changed target blocks before continuing.";
            case PAUSED_PROTECTED -> "Restore build permission for the reported position.";
            case PAUSED_BLOCKED -> "Clear the reported path or work-site obstruction.";
            case SUSPENDED_CHUNK_UNLOADED -> "Load the Builder and work-site chunks.";
            case NO_BUILDER -> "Use the Hut replacement flow for the lost Builder.";
            case ORPHANED -> "Restore the Builder Hut association.";
            case QUARANTINED_RECOVERY -> "Inspect recovery diagnostics before replacement.";
            default -> "";
        };
    }
}
