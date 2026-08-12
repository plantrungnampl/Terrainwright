package dev.ssa.fabric.client.preview;

import dev.ssa.fabric.client.spike.preview.PreviewRenderMetrics;
import dev.ssa.fabric.client.spike.preview.PreviewRevision;

public final class GhostPreviewRenderer {
    private GhostPreviewRenderer() {}

    public static void initialize() {
        dev.ssa.fabric.client.spike.preview.GhostPreviewRenderer.initialize();
    }

    public static void replace(PreviewRevision revision) {
        dev.ssa.fabric.client.spike.preview.GhostPreviewRenderer.replace(revision);
    }

    public static void clear() {
        dev.ssa.fabric.client.spike.preview.GhostPreviewRenderer.dispose();
    }

    public static void startMetrics() {
        dev.ssa.fabric.client.spike.preview.GhostPreviewRenderer.startMetrics();
    }

    public static PreviewRenderMetrics.Profile stopMetrics() {
        return dev.ssa.fabric.client.spike.preview.GhostPreviewRenderer.stopMetrics();
    }

    public static long activeRevision() {
        return dev.ssa.fabric.client.spike.preview.GhostPreviewRenderer.activeRevision();
    }

    public static int liveBufferCount() {
        return dev.ssa.fabric.client.spike.preview.GhostPreviewRenderer.liveBufferCount();
    }
}
