package dev.ssa.fabric.client.preview;

import dev.ssa.architect.ArchitectEngine;
import dev.ssa.architect.ArchitectEngine.GenerationResult;
import dev.ssa.architect.material.BlockCapability;
import dev.ssa.architect.material.BlockCapabilityRegistry;
import dev.ssa.architect.model.EntrancePreference;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.TerrainSnapshot;
import dev.ssa.architect.style.JapaneseStyle;
import dev.ssa.architect.style.MedievalStyle;
import dev.ssa.architect.style.ModernStyle;
import dev.ssa.architect.style.StylePack;
import dev.ssa.fabric.client.preview.PreviewClientState;
import dev.ssa.fabric.client.screen.ArchitectScreen;
import dev.ssa.fabric.client.screen.ArchitectScreenAssertions;
import dev.ssa.fabric.client.screen.TerrainwrightButton;
import dev.ssa.fabric.client.screen.TerrainwrightButtonAccessibilityAssertions;
import dev.ssa.fabric.client.spike.preview.PreviewRenderMetrics;
import dev.ssa.fabric.client.TerrainwrightClient;
import dev.ssa.fabric.network.PreviewPayloads.PreviewFailure;
import dev.ssa.fabric.network.PreviewPayloads.PreviewResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("UnstableApiUsage")
public final class ArchitectPreviewClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("smart_survival_architect_task13");

    @Override
    public void runTest(ClientGameTestContext context) {
        StylePack style = new JapaneseStyle();
        HouseRequirements requirements = requirements(style, 41);
        var blueprint = success(new ArchitectEngine().generate(
                requirements, terrain(17, 21), style, registry(style)));
        PreviewClientState state = TerrainwrightClient.previewState();
        BlockPos origin = new BlockPos(0, 80, 0);

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getServer().runCommand("time set day");
            singleplayer.getServer().runCommand("gamemode spectator @p");
            singleplayer.getServer().runCommand("tp @p 12 92 48 180 18");
            singleplayer.getClientLevel().waitForChunksRender();
            state.clear();
            int startingFrames = dev.ssa.fabric.client.spike.preview.GhostPreviewRenderer.renderedFrameCount();

            try {
                state.receiveSurveyToken("screen-ready-token");
                ArchitectScreen compactScreen = context.computeOnClient(client -> new ArchitectScreen(state));
                context.runOnClient(client -> {
                    client.setScreenAndShow(compactScreen);
                    compactScreen.resize(320, 180);
                });
                context.waitTicks(2);
                context.runOnClient(client -> {
                    assertCompactWidgetGeometry(compactScreen, 320, 180);
                    ArchitectScreenAssertions.assertCompactWorkflowBadgesConveyStateAndFit(compactScreen);
                    assertRecoveryAdjacent(
                            compactScreen,
                            "recovery.generate.compact",
                            "action.generate.compact",
                            4);
                });
                context.getInput().setCursorPos(1000, 1000);
                context.waitTick();
                context.takeScreenshot("terrainwright-v101-architect-compact-screen");
                context.runOnClient(client -> client.setScreenAndShow(null));

                ArchitectScreen emptyScreen = context.computeOnClient(client -> new ArchitectScreen(state));
                context.runOnClient(client -> client.setScreenAndShow(emptyScreen));
                context.waitTicks(2);
                context.runOnClient(client -> {
                    assertWidgetActive(emptyScreen, "action.generate", true);
                    assertWidgetActive(emptyScreen, "action.select_hut", false);
                    assertWidgetActive(emptyScreen, "action.rotate", false);
                    assertWidgetActive(emptyScreen, "action.move", false);
                    assertWidgetActive(emptyScreen, "action.confirm", false);
                    assertRecoveryAdjacent(emptyScreen, "recovery.generate", "action.generate", 16);
                });
                context.runOnClient(client -> client.setScreenAndShow(null));

                long failedNonce = request(state, requirements, 0);
                assertState(
                        state.reject(new PreviewFailure(failedNonce, PreviewFailure.Reason.SERVER_BUSY)),
                        "Preview failure was rejected");
                ArchitectScreen failureScreen = context.computeOnClient(client -> new ArchitectScreen(state));
                context.runOnClient(client -> client.setScreenAndShow(failureScreen));
                context.waitTicks(2);
                context.runOnClient(client -> assertRecoveryAdjacent(
                        failureScreen,
                        "failure.server_busy",
                        "action.select_site",
                        8));
                context.runOnClient(client -> client.setScreenAndShow(null));

                ArchitectScreen compactFailureScreen = context.computeOnClient(client -> new ArchitectScreen(state));
                context.runOnClient(client -> {
                    client.setScreenAndShow(compactFailureScreen);
                    compactFailureScreen.resize(320, 180);
                });
                context.waitTicks(2);
                context.runOnClient(client -> {
                    assertCompactWidgetGeometry(compactFailureScreen, 320, 180);
                    assertRecoveryAdjacent(
                            compactFailureScreen,
                            "failure.server_busy.compact",
                            "action.select_site.compact",
                            4);
                });
                context.runOnClient(client -> client.setScreenAndShow(null));

                long nonce = request(state, requirements, 0);
                assertState(state.accept(result(blueprint, origin, 0, nonce)), "Medium preview was rejected");
                state.selectHut(UUID.fromString("22222222-2222-2222-2222-222222222222"));
                assertState(state.confirmation().isPresent(), "Authoritative preview was not confirmable");
                waitForFrames(context, startingFrames + 5);

                for (int turn = 1; turn <= 4; turn++) {
                    int rotation = turn % 4 * 90;
                    nonce = request(state, requirements, rotation);
                    assertState(
                            state.accept(result(blueprint, origin, rotation, nonce)),
                            "Rotated preview was rejected at turn " + turn);
                    waitForFrames(context, startingFrames + 5 + turn);
                }

                HouseRequirements regeneratedRequirements = requirements(style, 42);
                var regenerated = success(new ArchitectEngine().generate(
                        regeneratedRequirements, terrain(17, 21), style, registry(style)));
                nonce = request(state, regeneratedRequirements, 0);
                assertState(
                        state.accept(result(regenerated, origin, 0, nonce)),
                        "Regenerated Medium preview was rejected");
                state.receiveSurveyToken("screen-successor-token");
                int framesBeforeScreen = dev.ssa.fabric.client.spike.preview.GhostPreviewRenderer.renderedFrameCount();
                ArchitectScreen previewScreen = context.computeOnClient(client -> new ArchitectScreen(state));
                context.runOnClient(client -> client.setScreenAndShow(previewScreen));
                context.waitTicks(2);
                context.runOnClient(client -> {
                    assertWidgetActive(previewScreen, "action.select_hut", true);
                    assertWidgetActive(previewScreen, "action.rotate", true);
                    assertWidgetActive(previewScreen, "action.move", true);
                    assertWidgetActive(previewScreen, "action.confirm", true);
                    assertConfigurationStyleMatchesAuthoritativePreview(previewScreen, style.id());
                    selectNextStyleAndAssertItPersists(previewScreen, style.id(), new ModernStyle().id());
                    TerrainwrightButtonAccessibilityAssertions.assertRealButtonsAccessible(previewScreen, 15);
                });

                HouseRequirements unknownStyleRequirements = requirements(style, 43);
                var unknownStyleBlueprint = withStyle(
                        regenerated,
                        dev.ssa.architect.model.StyleId.parse("smart_survival_architect:unknown"));
                nonce = request(state, unknownStyleRequirements, 0);
                assertState(
                        state.accept(result(unknownStyleBlueprint, origin, 0, nonce)),
                        "Unknown-style authoritative preview was rejected");
                context.waitTicks(2);
                context.runOnClient(client -> assertConfigurationStyleMatchesAuthoritativePreview(
                        previewScreen, new ModernStyle().id()));

                StylePack updatedStyle = new MedievalStyle();
                HouseRequirements updatedRequirements = requirements(updatedStyle, 44);
                var updatedBlueprint = success(new ArchitectEngine().generate(
                        updatedRequirements, terrain(17, 21), updatedStyle, registry(updatedStyle)));
                nonce = request(state, updatedRequirements, 0);
                assertState(
                        state.accept(result(updatedBlueprint, origin, 0, nonce)),
                        "Updated authoritative preview was rejected");
                context.waitTicks(2);
                context.runOnClient(client -> assertConfigurationStyleMatchesAuthoritativePreview(
                        previewScreen, updatedStyle.id()));
                waitForFrames(context, framesBeforeScreen + 2);
                int framesAfterScreen = dev.ssa.fabric.client.spike.preview.GhostPreviewRenderer.renderedFrameCount();
                assertState(
                        framesAfterScreen > framesBeforeScreen,
                        "Architect screen suppressed live ghost rendering");
                context.takeScreenshot("terrainwright-v101-architect-screen");
                context.runOnClient(client -> client.setScreenAndShow(null));

                context.runOnClient(client ->
                        dev.ssa.fabric.client.spike.preview.GhostPreviewRenderer.startMetrics());
                context.waitFor(
                        client -> dev.ssa.fabric.client.spike.preview.GhostPreviewRenderer.metricSampleCount() >= 120,
                        20_000);
                PreviewRenderMetrics.Profile profile = context.computeOnClient(
                        client -> dev.ssa.fabric.client.spike.preview.GhostPreviewRenderer.stopMetrics());
                assertState(profile.p95Micros() < 8_000, "Medium preview p95 exceeded budget: " + profile);
                assertState(
                        profile.p95AllocatedBytes() < 524_288,
                        "Medium preview allocation p95 exceeded budget: " + profile);

                state.movePreview(origin.east());
                assertState(state.confirmation().isEmpty(), "Locally moved preview retained Confirm authority");
                LOGGER.info(
                        "SSA_TASK13_PROFILE blocks={} p50_us={} p95_us={} max_us={} p95_alloc_bytes={}",
                        regenerated.blocks().size(),
                        profile.p50Micros(),
                        profile.p95Micros(),
                        profile.maxMicros(),
                        profile.p95AllocatedBytes());
            } finally {
                context.runOnClient(client -> state.clear());
                assertState(
                        dev.ssa.fabric.client.spike.preview.GhostPreviewRenderer.liveBufferCount() == 0,
                        "Task 13 renderer retained a live buffer after clear");
            }
        }
    }

    private static long request(PreviewClientState state, HouseRequirements requirements, int rotation) {
        state.receiveSurveyToken("task13-token-" + requirements.seed() + '-' + rotation);
        return state.requestPreview(requirements, rotation).requestNonce();
    }

    private static PreviewResult result(
            dev.ssa.architect.blueprint.Blueprint blueprint,
            BlockPos origin,
            int rotation,
            long nonce) {
        return new PreviewResult(
                UUID.randomUUID(),
                blueprint.hash(),
                blueprint,
                origin,
                rotation,
                10_000,
                nonce);
    }

    private static dev.ssa.architect.blueprint.Blueprint success(GenerationResult result) {
        if (result instanceof GenerationResult.Success success) {
            return success.blueprint();
        }
        throw new AssertionError("Medium house generation failed: " + result);
    }

    private static dev.ssa.architect.blueprint.Blueprint withStyle(
            dev.ssa.architect.blueprint.Blueprint source, dev.ssa.architect.model.StyleId style) {
        return new dev.ssa.architect.blueprint.Blueprint(
                source.id(),
                source.seed(),
                style,
                source.localBounds(),
                source.footprint(),
                source.floors(),
                source.rooms(),
                source.blocks(),
                source.buildPhases(),
                source.terrainPlan(),
                source.scoreBreakdown(),
                source.validation(),
                source.formatVersion());
    }

    private static HouseRequirements requirements(StylePack style, long seed) {
        return new HouseRequirements(
                style.id(),
                15,
                19,
                2,
                2,
                true,
                true,
                true,
                false,
                EntrancePreference.AUTO,
                seed);
    }

    private static TerrainSnapshot terrain(int width, int depth) {
        int area = width * depth;
        List<Integer> heights = new ArrayList<>(area);
        List<NamespacedId> materials = new ArrayList<>(area);
        for (int index = 0; index < area; index++) {
            heights.add(10);
            materials.add(NamespacedId.parse("minecraft:grass_block"));
        }
        return new TerrainSnapshot(
                new GridPos(0, 10, 0),
                width,
                depth,
                10,
                10,
                heights,
                materials,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                new TerrainSnapshot.SlopeMetrics(0, 0),
                Map.of(),
                "task13-flat");
    }

    private static BlockCapabilityRegistry registry(StylePack style) {
        Map<NamespacedId, Set<BlockCapability>> entries = new HashMap<>();
        style.fallbackPalette().values().forEach(candidates -> candidates.forEach(candidate -> entries
                .computeIfAbsent(candidate.state().blockId(), ignored -> new HashSet<>())
                .addAll(candidate.requiredCapabilities())));
        return BlockCapabilityRegistry.of(entries);
    }

    private static void waitForFrames(ClientGameTestContext context, int totalFrames) {
        context.waitFor(
                client -> dev.ssa.fabric.client.spike.preview.GhostPreviewRenderer.renderedFrameCount()
                        >= totalFrames,
                10_000);
    }

    private static void assertWidgetActive(Screen screen, String keySuffix, boolean expected) {
        AbstractWidget widget = widget(screen, keySuffix);
        assertState(
                widget.active == expected,
                "Unexpected active state for " + widget.getMessage() + ": " + widget.active);
    }

    private static void assertConfigurationStyleMatchesAuthoritativePreview(Screen screen, dev.ssa.architect.model.StyleId style) {
        Component expected = configurationStyleLabel(style);
        assertState(
                terrainwrightButtons(screen).stream().anyMatch(button -> button.getMessage().equals(expected)),
                "Visible Architect configuration does not match authoritative preview style: expected "
                        + expected.getString());
    }

    private static void selectNextStyleAndAssertItPersists(
            ArchitectScreen screen,
            dev.ssa.architect.model.StyleId currentStyle,
            dev.ssa.architect.model.StyleId expectedStyle) {
        TerrainwrightButton styleButton = terrainwrightButtons(screen).stream()
                .filter(button -> button.getMessage().equals(configurationStyleLabel(currentStyle)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing Architect Style control"));
        styleButton.onClick(null, false);
        assertConfigurationStyleMatchesAuthoritativePreview(screen, expectedStyle);
    }

    private static Component configurationStyleLabel(dev.ssa.architect.model.StyleId style) {
        return Component.translatable(
                "screen.smart_survival_architect.architect.field.style",
                Component.translatable(
                        "screen.smart_survival_architect.architect.option.style." + style.value().path()));
    }

    private static List<TerrainwrightButton> terrainwrightButtons(Screen screen) {
        return screen.children().stream()
                .filter(TerrainwrightButton.class::isInstance)
                .map(TerrainwrightButton.class::cast)
                .toList();
    }

    private static void assertCompactWidgetGeometry(Screen screen, int width, int height) {
        List<AbstractWidget> widgets = screen.children().stream()
                .filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast)
                .toList();
        assertState(widgets.size() >= 15, "Compact Architect screen dropped required widgets: " + widgets.size());
        for (AbstractWidget widget : widgets) {
            assertState(
                    widget.getX() >= 0
                            && widget.getY() >= 0
                            && widget.getX() + widget.getWidth() <= width
                            && widget.getY() + widget.getHeight() <= height,
                    "Compact widget escaped 320x180 viewport: " + widget.getMessage());
        }
        for (int first = 0; first < widgets.size(); first++) {
            for (int second = first + 1; second < widgets.size(); second++) {
                AbstractWidget left = widgets.get(first);
                AbstractWidget right = widgets.get(second);
                assertState(
                        !overlaps(left, right),
                        "Compact widgets overlap: " + left.getMessage() + " / " + right.getMessage());
            }
        }
    }

    private static void assertRecoveryAdjacent(
            Screen screen, String recoveryKeySuffix, String actionKeySuffix, int maximumGap) {
        AbstractWidget recovery = widget(screen, recoveryKeySuffix);
        AbstractWidget action = widget(screen, actionKeySuffix);
        int horizontalGap = Math.max(
                0,
                Math.max(
                        action.getX() - (recovery.getX() + recovery.getWidth()),
                        recovery.getX() - (action.getX() + action.getWidth())));
        int verticalGap = action.getY() - (recovery.getY() + recovery.getHeight());
        assertState(
                horizontalGap <= maximumGap && verticalGap >= 0 && verticalGap <= maximumGap,
                "Recovery text is not adjacent to " + action.getMessage()
                        + ": horizontalGap=" + horizontalGap + ", verticalGap=" + verticalGap
                        + ", recovery=" + bounds(recovery) + ", action=" + bounds(action));
    }

    private static String bounds(AbstractWidget widget) {
        return widget.getX() + "," + widget.getY() + " " + widget.getWidth() + "x" + widget.getHeight();
    }

    private static boolean overlaps(AbstractWidget first, AbstractWidget second) {
        return first.getX() < second.getX() + second.getWidth()
                && first.getX() + first.getWidth() > second.getX()
                && first.getY() < second.getY() + second.getHeight()
                && first.getY() + first.getHeight() > second.getY();
    }

    private static AbstractWidget widget(Screen screen, String keySuffix) {
        Component message = Component.translatable(
                "screen.smart_survival_architect.architect." + keySuffix);
        return screen.children().stream()
                .filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast)
                .filter(candidate -> candidate.getMessage().equals(message))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing Architect widget: " + message));
    }

    private static void assertState(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
