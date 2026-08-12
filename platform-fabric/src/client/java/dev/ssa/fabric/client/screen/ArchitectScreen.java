package dev.ssa.fabric.client.screen;

import dev.ssa.architect.model.EntrancePreference;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.StyleId;
import dev.ssa.fabric.client.preview.PreviewClientState;
import dev.ssa.fabric.network.PreviewPayloads.ConfirmPreview;
import dev.ssa.fabric.network.PreviewPayloads.RequestPreview;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ArchitectScreen extends Screen {
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
    private Button generateButton;
    private Button selectHutButton;
    private Button rotateButton;
    private Button moveButton;
    private Button confirmButton;

    public ArchitectScreen(PreviewClientState previewState) {
        this(previewState, () -> {}, () -> {});
    }

    public ArchitectScreen(PreviewClientState previewState, Runnable selectSite, Runnable selectHut) {
        super(Component.literal("Smart Survival Architect"));
        this.previewState = java.util.Objects.requireNonNull(previewState, "previewState");
        this.selectSite = java.util.Objects.requireNonNull(selectSite, "selectSite");
        this.selectHut = java.util.Objects.requireNonNull(selectHut, "selectHut");
    }

    public void selectHut(UUID hutId) {
        previewState.selectHut(hutId);
    }

    @Override
    protected void init() {
        int left = width / 2 - 155;
        int top = Math.max(28, height / 2 - 105);
        addRenderableWidget(button(left, top, 150, this::styleLabel, ignored -> {
            styleIndex = (styleIndex + 1) % STYLES.size();
            rebuildWidgets();
        }));
        addRenderableWidget(button(left + 160, top, 150, this::sizeLabel, ignored -> {
            sizeIndex = (sizeIndex + 1) % SIZES.length;
            rebuildWidgets();
        }));
        addRenderableWidget(button(left, top + 26, 72, this::floorsLabel, ignored -> {
            floors = floors % HouseRequirements.MAX_FLOORS + 1;
            rebuildWidgets();
        }));
        addRenderableWidget(button(left + 78, top + 26, 72, this::bedroomsLabel, ignored -> {
            bedrooms = (bedrooms + 1) % (HouseRequirements.MAX_BEDROOMS + 1);
            rebuildWidgets();
        }));
        addRenderableWidget(toggle(left + 160, top + 26, "Kitchen", () -> kitchen, value -> kitchen = value));
        addRenderableWidget(toggle(left + 238, top + 26, "Storage", () -> storage, value -> storage = value));
        addRenderableWidget(toggle(left, top + 52, "Balcony", () -> balcony, value -> balcony = value));
        addRenderableWidget(toggle(left + 78, top + 52, "Chimney", () -> chimney, value -> chimney = value));
        addRenderableWidget(button(left + 160, top + 52, 150, this::entranceLabel, ignored -> {
            entranceIndex = (entranceIndex + 1) % EntrancePreference.values().length;
            rebuildWidgets();
        }));

        addRenderableWidget(Button.builder(Component.literal("Select Site"), ignored -> selectSite.run())
                .bounds(left, top + 82, 150, 20)
                .build());
        selectHutButton = addRenderableWidget(Button.builder(
                        Component.literal("Select Builder Hut"), ignored -> selectHut.run())
                .bounds(left + 160, top + 82, 150, 20)
                .build());
        selectHutButton.active = previewState.preview().isPresent();

        generateButton = addRenderableWidget(Button.builder(
                        Component.literal(previewState.preview().isPresent() ? "Regenerate" : "Generate Preview"),
                        ignored -> requestPreview(false))
                .bounds(left, top + 108, 150, 20)
                .build());
        rotateButton = addRenderableWidget(Button.builder(
                        Component.literal("Rotate 90 degrees"),
                        ignored -> requestPreview(true))
                .bounds(left + 160, top + 108, 150, 20)
                .build());
        moveButton = addRenderableWidget(Button.builder(
                        Component.literal("Move ghost +1 X"),
                        ignored -> previewState.preview().ifPresent(result ->
                                previewState.movePreview(result.origin().east())))
                .bounds(left, top + 134, 150, 20)
                .build());
        confirmButton = addRenderableWidget(Button.builder(
                        Component.literal("Confirm"),
                        ignored -> previewState.confirmation().ifPresent(ClientPlayNetworking::send))
                .bounds(left + 160, top + 134, 150, 20)
                .build());
        updateActions();
    }

    @Override
    public void tick() {
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
        int top = Math.max(28, height / 2 - 105);
        graphics.centeredText(font, title, width / 2, top - 20, 0xffffff);
        Optional<dev.ssa.fabric.network.PreviewPayloads.PreviewResult> preview = previewState.preview();
        if (preview.isEmpty()) {
            graphics.text(font, "Select a site, then generate a server preview.", left, top + 162, 0xb8d8ff);
            previewState.lastFailure().ifPresent(reason -> graphics.text(
                    font,
                    "Preview failed: " + reason.name() + ". Select Site to retry.",
                    left,
                    top + 176,
                    0xff7070));
            if (!previewState.canRequestPreview()) {
                graphics.text(font, "Waiting for a fresh Survey token.", left, top + 190, 0xffc66d);
            }
            return;
        }

        var result = preview.orElseThrow();
        var blueprint = result.blueprint();
        graphics.text(
                font,
                blueprint.styleId() + " - " + blueprint.floors() + " floor(s)",
                left,
                top + 162,
                0xb8d8ff);
        graphics.text(
                font,
                "Blocks " + blueprint.blocks().size()
                        + " | Terrain " + blueprint.terrainPlan().removedCount()
                        + " removed / " + blueprint.terrainPlan().filledCount() + " filled",
                left,
                top + 176,
                0xd0d0d0);
        graphics.text(
                font,
                "Origin " + result.origin().toShortString() + " | Rotation " + result.rotation(),
                left,
                top + 190,
                0xd0d0d0);
        if (previewState.confirmation().isEmpty()) {
            graphics.text(font, "Confirm requires an unchanged server preview and selected Hut.", left, top + 204, 0xffc66d);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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

    private void updateActions() {
        if (generateButton == null) {
            return;
        }
        boolean canRequest = previewState.canRequestPreview();
        selectHutButton.active = previewState.preview().isPresent();
        generateButton.active = canRequest;
        rotateButton.active = canRequest && previewState.preview().isPresent();
        moveButton.active = previewState.preview().isPresent();
        Optional<ConfirmPreview> confirmation = previewState.confirmation();
        confirmButton.active = confirmation.isPresent();
    }

    private Button button(
            int x,
            int y,
            int buttonWidth,
            java.util.function.Supplier<String> label,
            java.util.function.Consumer<Button> action) {
        return Button.builder(Component.literal(label.get()), action::accept)
                .bounds(x, y, buttonWidth, 20)
                .build();
    }

    private Button toggle(
            int x,
            int y,
            String label,
            java.util.function.BooleanSupplier value,
            java.util.function.Consumer<Boolean> update) {
        return Button.builder(
                        Component.literal(label + ": " + (value.getAsBoolean() ? "On" : "Off")),
                        ignored -> {
                            update.accept(!value.getAsBoolean());
                            rebuildWidgets();
                        })
                .bounds(x, y, 72, 20)
                .build();
    }

    private String styleLabel() {
        return "Style: " + STYLES.get(styleIndex).value();
    }

    private String sizeLabel() {
        return "Size: " + SIZES[sizeIndex][0] + " x " + SIZES[sizeIndex][1];
    }

    private String floorsLabel() {
        return "Floors: " + floors;
    }

    private String bedroomsLabel() {
        return "Beds: " + bedrooms;
    }

    private String entranceLabel() {
        return "Entrance: " + EntrancePreference.values()[entranceIndex].name();
    }
}
