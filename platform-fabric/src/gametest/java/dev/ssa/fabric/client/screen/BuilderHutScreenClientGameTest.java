package dev.ssa.fabric.client.screen;

import dev.ssa.architect.model.GridPos;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.fabric.client.job.JobClientState;
import dev.ssa.fabric.network.JobPayloads.DiagnosticView;
import dev.ssa.fabric.network.JobPayloads.HutSnapshot;
import dev.ssa.fabric.network.JobPayloads.JobSnapshot;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("UnstableApiUsage")
public final class BuilderHutScreenClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("smart_survival_architect_builder_hut_screen");
    private static final UUID HUT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String JOB_ID = "smart_survival_architect:client_gametest";
    private static final int WIDTH = 420;
    private static final int HEIGHT = 240;

    @Override
    public void runTest(ClientGameTestContext context) {
        JobClientState state = pausedWithoutChestState();

        try (TestSingleplayerContext ignored = context.worldBuilder().create()) {
            BuilderHutScreen noChestScreen = openScreen(context, state);
            context.runOnClient(client -> {
                AbstractWidget link = widget(noChestScreen, "action.link_chest");
                assertState(link.active, "Link / Relink Chest was inactive in PAUSED_NO_CHEST");
                assertStyle(link, TerrainwrightButton.Style.PRIMARY);
                assertWidgetActive(noChestScreen, "action.resume", false);
                assertWidgetActive(noChestScreen, "action.safe_undo", false);

                AbstractWidget guidance = widget(noChestScreen, "guidance.paused_no_chest");
                assertState(
                        !guidance.getMessage().getString().isBlank(),
                        "PAUSED_NO_CHEST guidance was empty");
                assertWidgetsInsideBuilderLayout(noChestScreen, WIDTH, HEIGHT);
            });
            context.takeScreenshot("terrainwright-v101-builder-hut-screen");
            context.runOnClient(client -> client.setScreenAndShow(null));

            assertState(
                    state.accept(snapshot(BuildJobState.PAUSED, 8)),
                    "Higher-revision PAUSED snapshot was rejected");
            BuilderHutScreen pausedScreen = openScreen(context, state);
            context.runOnClient(client -> {
                assertWidgetActive(pausedScreen, "action.resume", true);
                assertWidgetsInsideBuilderLayout(pausedScreen, WIDTH, HEIGHT);
            });
            LOGGER.info("SSA_BUILDER_HUT_CLIENT_GAMETEST_EXECUTED");
            context.runOnClient(client -> client.setScreenAndShow(null));
        }
    }

    private static JobClientState pausedWithoutChestState() {
        JobClientState state = new JobClientState();
        assertState(state.accept(new HutSnapshot(HUT_ID, 3, false, true)), "Hut snapshot was rejected");
        assertState(
                state.accept(snapshot(BuildJobState.PAUSED_NO_CHEST, 7)),
                "PAUSED_NO_CHEST snapshot was rejected");
        return state;
    }

    private static JobSnapshot snapshot(BuildJobState state, long revision) {
        return new JobSnapshot(
                JOB_ID,
                HUT_ID,
                OWNER_ID,
                revision,
                state,
                9,
                24,
                Map.of("minecraft:cobblestone", 14, "minecraft:oak_planks", 27),
                List.of(new GridPos(4, 72, -3)),
                List.of(new DiagnosticView(
                        "PATH_BLOCKED",
                        "Builder can recover after the obstruction is cleared.",
                        true,
                        Optional.of(new GridPos(5, 72, -3)))));
    }

    private static BuilderHutScreen openScreen(ClientGameTestContext context, JobClientState state) {
        BuilderHutScreen screen = context.computeOnClient(
                client -> new BuilderHutScreen(state, () -> {}, () -> {}));
        context.runOnClient(client -> {
            client.setScreenAndShow(screen);
            screen.resize(WIDTH, HEIGHT);
        });
        context.waitTicks(2);
        return screen;
    }

    private static void assertWidgetsInsideBuilderLayout(Screen screen, int width, int height) {
        TerrainwrightScreenLayout.BuilderLayout layout = TerrainwrightScreenLayout.builder(width, height);
        List<TerrainwrightScreenLayout.Bounds> regions = List.of(
                layout.header(),
                layout.progress(),
                layout.materials(),
                layout.diagnostics(),
                layout.actionRail());
        for (AbstractWidget widget : screen.children().stream()
                .filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast)
                .toList()) {
            assertState(
                    regions.stream().anyMatch(region -> contains(region, widget)),
                    "Widget escaped Builder layout: " + widget.getMessage() + " at " + bounds(widget));
        }
    }

    private static boolean contains(TerrainwrightScreenLayout.Bounds region, AbstractWidget widget) {
        return widget.getX() >= region.x()
                && widget.getY() >= region.y()
                && widget.getX() + widget.getWidth() <= region.right()
                && widget.getY() + widget.getHeight() <= region.bottom();
    }

    private static void assertWidgetActive(Screen screen, String keySuffix, boolean expected) {
        AbstractWidget widget = widget(screen, keySuffix);
        assertState(
                widget.active == expected,
                "Unexpected active state for " + widget.getMessage() + ": " + widget.active);
    }

    private static AbstractWidget widget(Screen screen, String keySuffix) {
        Component message = Component.translatable(
                "screen.smart_survival_architect.builder_hut." + keySuffix);
        return screen.children().stream()
                .filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast)
                .filter(candidate -> candidate.getMessage().equals(message))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing Builder Hut widget: " + message));
    }

    private static void assertStyle(AbstractWidget widget, TerrainwrightButton.Style expected) {
        assertState(widget instanceof TerrainwrightButton, "Builder action did not use the Terrainwright button");
        try {
            Field style = TerrainwrightButton.class.getDeclaredField("style");
            style.setAccessible(true);
            assertState(style.get(widget) == expected, "Unexpected Builder action style: " + style.get(widget));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to inspect Builder action style", exception);
        }
    }

    private static String bounds(AbstractWidget widget) {
        return widget.getX() + "," + widget.getY() + " " + widget.getWidth() + "x" + widget.getHeight();
    }

    private static void assertState(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
