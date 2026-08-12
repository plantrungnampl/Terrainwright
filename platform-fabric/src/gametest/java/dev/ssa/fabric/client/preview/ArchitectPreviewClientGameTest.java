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
import dev.ssa.architect.style.StylePack;
import dev.ssa.fabric.client.preview.PreviewClientState;
import dev.ssa.fabric.client.screen.ArchitectScreen;
import dev.ssa.fabric.client.spike.preview.PreviewRenderMetrics;
import dev.ssa.fabric.client.SmartSurvivalArchitectClient;
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
import net.minecraft.core.BlockPos;
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
        PreviewClientState state = SmartSurvivalArchitectClient.previewState();
        BlockPos origin = new BlockPos(0, 80, 0);

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getServer().runCommand("time set day");
            singleplayer.getServer().runCommand("gamemode spectator @p");
            singleplayer.getServer().runCommand("tp @p 12 92 48 180 18");
            singleplayer.getClientLevel().waitForChunksRender();
            int startingFrames = dev.ssa.fabric.client.spike.preview.GhostPreviewRenderer.renderedFrameCount();

            try {
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
                context.runOnClient(client -> client.setScreenAndShow(new ArchitectScreen(state)));
                context.waitTicks(2);
                context.takeScreenshot("ssa-task13-architect-screen");
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

    private static void assertState(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
