package dev.ssa.fabric.client.spike.preview;

public final class PreviewFixtures {
    private static final int WIDTH = 25;
    private static final int DEPTH = 20;

    private PreviewFixtures() {}

    public static PreviewRevision create(
            long revision,
            int blockCount,
            int originX,
            int originY,
            int originZ,
            long seed) {
        if (blockCount < 1 || blockCount > PreviewRevision.MAX_BLOCKS) {
            throw new IllegalArgumentException(
                    "Preview block count must be between 1 and " + PreviewRevision.MAX_BLOCKS);
        }

        int[] x = new int[blockCount];
        int[] y = new int[blockCount];
        int[] z = new int[blockCount];
        PreviewLayer[] layers = new PreviewLayer[blockCount];
        PreviewLayer[] layerValues = PreviewLayer.values();
        int layerOffset = Math.floorMod(seed, layerValues.length);

        for (int index = 0; index < blockCount; index++) {
            x[index] = index % WIDTH;
            z[index] = (index / WIDTH) % DEPTH;
            y[index] = index / (WIDTH * DEPTH);
            layers[index] = layerValues[(index + layerOffset) % layerValues.length];
        }

        return new PreviewRevision(
                revision,
                seed,
                originX,
                originY,
                originZ,
                0,
                x,
                y,
                z,
                layers);
    }
}
