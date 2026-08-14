package dev.ssa.fabric.client.screen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public final class TerrainwrightButtonAccessibilityAssertions {
    private static final KeyEvent CONFIRM = new KeyEvent(257, 0, 0);

    private TerrainwrightButtonAccessibilityAssertions() {}

    public static void assertRealButtonsAccessible(Screen screen, int expectedCount) {
        List<TerrainwrightButton> buttons = terrainwrightButtons(screen);
        assertState(buttons.size() == expectedCount,
                "Missing required Terrainwright buttons: expected " + expectedCount + ", got " + buttons.size());
        for (TerrainwrightButton button : buttons) {
            assertState(button instanceof NarratableEntry,
                    "Terrainwright button is not narratable: " + button.getMessage());
        }

        assertRealScreenTabTraversal(screen, buttons);
        assertInertProbeScreenDispatch(buttons);
    }

    private static void assertRealScreenTabTraversal(Screen screen, List<TerrainwrightButton> buttons) {
        Set<TerrainwrightButton> expectedFocusable = new HashSet<>(buttons.stream()
                .filter(button -> button.active)
                .toList());
        assertState(!expectedFocusable.isEmpty(), "No active Terrainwright buttons were available for tab traversal");
        Set<TerrainwrightButton> traversed = new HashSet<>();
        screen.clearFocus();
        for (int index = 0; index < expectedFocusable.size(); index++) {
            ComponentPath path = screen.nextFocusPath(new FocusNavigationEvent.TabNavigation(true));
            assertState(path != null, "Tab traversal ended before reaching every active Terrainwright button");
            path.applyFocus(true);
            assertState(screen.getFocused() instanceof TerrainwrightButton,
                    "Tab traversal focused a non-Terrainwright widget");
            TerrainwrightButton focused = (TerrainwrightButton) screen.getFocused();
            assertState(focused.active, "Tab traversal focused a disabled Terrainwright button");
            traversed.add(focused);
        }
        screen.clearFocus();
        assertState(traversed.equals(expectedFocusable),
                "Normal tab traversal did not reach every active Terrainwright button");
    }

    private static void assertInertProbeScreenDispatch(List<TerrainwrightButton> realButtons) {
        List<Component> semanticLabels = realButtons.stream()
                .map(TerrainwrightButton::getMessage)
                .toList();
        InertProbeScreen probeScreen = new InertProbeScreen(semanticLabels);
        probeScreen.resize(320, 180);

        List<TerrainwrightButton> probes = probeScreen.probes();
        assertState(probes.size() == semanticLabels.size(),
                "Probe screen did not create a button for every semantic action");
        for (int index = 0; index < probes.size(); index++) {
            TerrainwrightButton probe = probes.get(index);
            assertState(probeScreen.children().contains(probe),
                    "Accessibility probe was not registered as a Screen child: " + probe.getMessage());
            assertState(probe.getMessage().equals(semanticLabels.get(index)),
                    "Accessibility probe did not preserve semantic action: " + probe.getMessage());
        }

        Set<TerrainwrightButton> remaining = new HashSet<>(probes);
        probeScreen.clearFocus();
        for (int index = 0; index < probes.size(); index++) {
            ComponentPath path = probeScreen.nextFocusPath(new FocusNavigationEvent.TabNavigation(true));
            assertState(path != null, "Probe Screen tab traversal ended before every semantic action");
            path.applyFocus(true);
            assertState(probeScreen.getFocused() instanceof TerrainwrightButton,
                    "Probe Screen tab traversal focused a non-Terrainwright button");
            TerrainwrightButton focused = (TerrainwrightButton) probeScreen.getFocused();
            assertState(remaining.remove(focused),
                    "Probe Screen tab traversal revisited or skipped a semantic action: " + focused.getMessage());

            int[] beforeConfirmation = probeScreen.activationCounts();
            assertState(probeScreen.keyPressed(CONFIRM),
                    "Focused active probe did not consume confirmation through Screen dispatch");
            assertOnlyFocusedProbeActivated(beforeConfirmation, probeScreen.activationCounts(), probes.indexOf(focused));

            focused.active = false;
            int[] beforeDisabledConfirmation = probeScreen.activationCounts();
            assertState(!probeScreen.keyPressed(CONFIRM),
                    "Disabled focused probe consumed confirmation through Screen dispatch");
            assertActivationCountsEqual(beforeDisabledConfirmation, probeScreen.activationCounts(),
                    "Disabled focused probe invoked a callback through Screen dispatch");
            focused.active = true;
        }
        probeScreen.clearFocus();
        assertState(remaining.isEmpty(),
                "Probe Screen tab traversal did not visit every semantic action");
    }

    private static void assertOnlyFocusedProbeActivated(int[] before, int[] after, int focusedIndex) {
        for (int index = 0; index < after.length; index++) {
            int expected = before[index] + (index == focusedIndex ? 1 : 0);
            assertState(after[index] == expected,
                    "Confirmation through Screen dispatch activated the wrong probe at index " + index);
        }
    }

    private static void assertActivationCountsEqual(int[] expected, int[] actual, String message) {
        for (int index = 0; index < actual.length; index++) {
            assertState(actual[index] == expected[index], message + " at index " + index);
        }
    }

    private static List<TerrainwrightButton> terrainwrightButtons(Screen screen) {
        return screen.children().stream()
                .filter(TerrainwrightButton.class::isInstance)
                .map(TerrainwrightButton.class::cast)
                .toList();
    }

    private static void assertState(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class InertProbeScreen extends Screen {
        private final List<Component> semanticLabels;
        private final int[] activationCounts;
        private final List<TerrainwrightButton> probes = new ArrayList<>();

        private InertProbeScreen(List<Component> semanticLabels) {
            super(Component.literal("Inert accessibility probe"));
            this.semanticLabels = List.copyOf(semanticLabels);
            this.activationCounts = new int[semanticLabels.size()];
        }

        @Override
        protected void init() {
            probes.clear();
            for (int index = 0; index < semanticLabels.size(); index++) {
                int probeIndex = index;
                TerrainwrightButton probe = addRenderableWidget(new TerrainwrightButton(
                        4,
                        4 + index * 10,
                        1,
                        1,
                        semanticLabels.get(index),
                        TerrainwrightButton.Style.NORMAL,
                        () -> activationCounts[probeIndex]++));
                probes.add(probe);
            }
        }

        private List<TerrainwrightButton> probes() {
            return List.copyOf(probes);
        }

        private int[] activationCounts() {
            return activationCounts.clone();
        }
    }
}
