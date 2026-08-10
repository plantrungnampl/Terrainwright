package dev.ssa.fabric.client.spike.preview;

import com.mojang.blaze3d.systems.RenderSystem;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("UnstableApiUsage")
public final class GhostPreviewClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("smart_survival_architect_s3");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getServer().runCommand("time set day");
            singleplayer.getServer().runCommand("weather clear");
            singleplayer.getServer().runCommand("gamemode spectator @p");
            singleplayer.getServer().runCommand("tp @p 12 92 48 180 18");
            singleplayer.getClientLevel().waitForChunksRender();
            context.runOnClient(client -> {
                if (client.player == null) {
                    throw new AssertionError("Client player is unavailable");
                }
                client.player.setYRot(180.0f);
                client.player.setYHeadRot(180.0f);
                client.player.setXRot(18.0f);
            });
            context.waitTicks(3);
            context.getInput().pressKey(options -> options.keyToggleGui);
            context.waitTicks(1);
            String cameraState = context.computeOnClient(client -> {
                var camera = client.gameRenderer.mainCamera();
                return "position=" + camera.position() + ",yaw=" + camera.yRot() + ",pitch=" + camera.xRot();
            });
            LOGGER.info("SSA_S3_CAMERA {}", cameraState);

            try {
                PreviewRevision small = PreviewFixtures.create(1, 1_000, 0, 80, 0, 17);
                context.runOnClient(client -> GhostPreviewRenderer.replace(small));
                waitForFrames(context, 5);
                assertOwnership(1, 1, 0, 1);
                Path smallScreenshot = context.takeScreenshot("ssa-s3-preview-1000");
                assertPreviewVisible(smallScreenshot, 2_000);
                logScreenshot(small, smallScreenshot);

                PreviewRevision large = PreviewFixtures.create(2, 5_000, 0, 80, 0, 17);
                context.runOnClient(client -> GhostPreviewRenderer.replace(large));
                waitForFrames(context, 10);
                assertOwnership(2, 2, 1, 1);
                Path largeScreenshot = context.takeScreenshot("ssa-s3-preview-5000");
                assertPreviewVisible(largeScreenshot, 5_000);
                logScreenshot(large, largeScreenshot);

                PreviewRevision rotated = large.rotateClockwise(3);
                context.runOnClient(client -> GhostPreviewRenderer.replace(rotated));
                waitForFrames(context, 15);
                assertOwnership(3, 3, 2, 1);
                assertState(large.rotationQuarterTurns() == 0, "Rotation mutated the source revision");

                PreviewRevision regenerated = PreviewFixtures.create(4, 5_000, 0, 80, 0, 18);
                context.runOnClient(client -> GhostPreviewRenderer.replace(regenerated));
                waitForFrames(context, 45);
                context.runOnClient(client -> GhostPreviewRenderer.startMetrics());
                context.waitFor(client -> GhostPreviewRenderer.metricSampleCount() >= 120, 20_000);
                PreviewRenderMetrics.Profile profile = context.computeOnClient(
                        client -> GhostPreviewRenderer.stopMetrics());
                assertOwnership(4, 4, 3, 1);
                assertState(profile.allocationSupported(), "JDK thread allocation counter is unavailable");
                assertState(profile.p95Micros() < 8_000, "Renderer p95 exceeded 8,000 us: " + profile);
                assertState(profile.maxMicros() < 16_667, "Renderer max exceeded 16,667 us: " + profile);
                assertState(
                        profile.p95AllocatedBytes() < 524_288,
                        "Renderer allocation p95 exceeded 524,288 bytes: " + profile);
                LOGGER.info(
                        "SSA_S3_PROFILE blocks={} frames={} p50_us={} p95_us={} max_us={} p95_alloc_bytes={} max_alloc_bytes={}",
                        regenerated.blockCount(),
                        profile.sampleCount(),
                        profile.p50Micros(),
                        profile.p95Micros(),
                        profile.maxMicros(),
                        profile.p95AllocatedBytes(),
                        profile.maxAllocatedBytes());
            } finally {
                context.runOnClient(client -> GhostPreviewRenderer.dispose());
                assertOwnership(-1, 4, 4, 0);
                LOGGER.info(
                        "SSA_S3_LIFECYCLE active=none created={} closed={} live={}",
                        GhostPreviewRenderer.createdBufferCount(),
                        GhostPreviewRenderer.closedBufferCount(),
                        GhostPreviewRenderer.liveBufferCount());
            }
        }
    }

    private static void waitForFrames(ClientGameTestContext context, int totalFrames) {
        context.waitFor(client -> GhostPreviewRenderer.renderedFrameCount() >= totalFrames, 10_000);
    }

    private static void assertOwnership(long revision, int created, int closed, int live) {
        assertState(GhostPreviewRenderer.activeRevision() == revision, "Active revision");
        assertState(GhostPreviewRenderer.createdBufferCount() == created, "Created buffer count");
        assertState(GhostPreviewRenderer.closedBufferCount() == closed, "Closed buffer count");
        assertState(GhostPreviewRenderer.liveBufferCount() == live, "Live buffer count");
    }

    private static void logScreenshot(PreviewRevision revision, Path screenshot) {
        StringBuilder layers = new StringBuilder();
        for (PreviewLayer layer : PreviewLayer.values()) {
            if (!layers.isEmpty()) {
                layers.append(',');
            }
            layers.append(layer.name()).append(':').append(revision.layerCount(layer));
        }
        LOGGER.info(
                "SSA_S3_SCREENSHOT blocks={} revision={} rotation={} backend={} layers={} path={}",
                revision.blockCount(),
                revision.revision(),
                revision.rotationQuarterTurns(),
                backendIdentity(),
                layers,
                screenshot.toAbsolutePath());
    }

    private static void assertPreviewVisible(Path screenshot, int minimumColoredPixels) {
        try {
            BufferedImage image = ImageIO.read(screenshot.toFile());
            int coloredPixels = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y);
                    int red = (rgb >> 16) & 0xff;
                    int green = (rgb >> 8) & 0xff;
                    int blue = rgb & 0xff;
                    int maximum = Math.max(red, Math.max(green, blue));
                    int minimum = Math.min(red, Math.min(green, blue));
                    if (maximum > 180 && maximum - minimum > 110) {
                        coloredPixels++;
                    }
                }
            }
            assertState(
                    coloredPixels >= minimumColoredPixels,
                    "Screenshot did not contain enough visible ghost pixels: " + coloredPixels);
        } catch (IOException error) {
            throw new AssertionError("Could not inspect screenshot " + screenshot, error);
        }
    }

    private static String backendIdentity() {
        var device = RenderSystem.getDevice().getDeviceInfo();
        return device.backendName() + "/" + device.name() + "/" + device.driverInfo();
    }

    private static void assertState(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
