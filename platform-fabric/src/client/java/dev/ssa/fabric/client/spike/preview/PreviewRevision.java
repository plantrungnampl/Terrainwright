package dev.ssa.fabric.client.spike.preview;

import java.util.Arrays;
import java.util.Objects;

public final class PreviewRevision {
    public static final int MAX_BLOCKS = 5_000;

    private final long revision;
    private final long contentIdentity;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final int rotationQuarterTurns;
    private final int[] x;
    private final int[] y;
    private final int[] z;
    private final PreviewLayer[] layers;

    PreviewRevision(
            long revision,
            long contentIdentity,
            int originX,
            int originY,
            int originZ,
            int rotationQuarterTurns,
            int[] x,
            int[] y,
            int[] z,
            PreviewLayer[] layers) {
        int blockCount = x.length;
        if (blockCount == 0 || blockCount > MAX_BLOCKS) {
            throw new IllegalArgumentException("Preview block count must be between 1 and " + MAX_BLOCKS);
        }
        if (y.length != blockCount || z.length != blockCount || layers.length != blockCount) {
            throw new IllegalArgumentException("Preview coordinate and layer arrays must have equal lengths");
        }

        this.revision = revision;
        this.contentIdentity = contentIdentity;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.rotationQuarterTurns = Math.floorMod(rotationQuarterTurns, 4);
        this.x = Arrays.copyOf(x, blockCount);
        this.y = Arrays.copyOf(y, blockCount);
        this.z = Arrays.copyOf(z, blockCount);
        this.layers = Arrays.copyOf(layers, blockCount);
        for (PreviewLayer layer : this.layers) {
            Objects.requireNonNull(layer, "Preview layer");
        }
    }

    public static PreviewRevision create(
            long revision,
            long contentIdentity,
            int originX,
            int originY,
            int originZ,
            int rotationQuarterTurns,
            int[] x,
            int[] y,
            int[] z,
            PreviewLayer[] layers) {
        return new PreviewRevision(
                revision,
                contentIdentity,
                originX,
                originY,
                originZ,
                rotationQuarterTurns,
                x,
                y,
                z,
                layers);
    }

    public PreviewRevision rotateClockwise(long nextRevision) {
        int[] rotatedX = new int[blockCount()];
        int[] rotatedZ = new int[blockCount()];
        for (int index = 0; index < blockCount(); index++) {
            rotatedX[index] = -z[index];
            rotatedZ[index] = x[index];
        }
        return new PreviewRevision(
                nextRevision,
                contentIdentity,
                originX,
                originY,
                originZ,
                rotationQuarterTurns + 1,
                rotatedX,
                y,
                rotatedZ,
                layers);
    }

    public long revision() {
        return revision;
    }

    public long contentIdentity() {
        return contentIdentity;
    }

    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    public int rotationQuarterTurns() {
        return rotationQuarterTurns;
    }

    public int blockCount() {
        return x.length;
    }

    public int xAt(int index) {
        return x[index];
    }

    public int yAt(int index) {
        return y[index];
    }

    public int zAt(int index) {
        return z[index];
    }

    public PreviewLayer layerAt(int index) {
        return layers[index];
    }

    public int layerCount(PreviewLayer expected) {
        int count = 0;
        for (PreviewLayer layer : layers) {
            if (layer == expected) {
                count++;
            }
        }
        return count;
    }
}
