package dev.ssa.fabric.client.spike.preview;

public enum PreviewLayer {
    REQUIRED(0.20f, 0.85f, 1.00f, 0.38f),
    OPTIONAL(1.00f, 0.78f, 0.24f, 0.24f),
    TERRAIN_FILL(0.30f, 0.90f, 0.38f, 0.32f),
    TERRAIN_REMOVAL(1.00f, 0.25f, 0.20f, 0.32f),
    CONFLICT(1.00f, 0.10f, 0.75f, 0.58f);

    private final float red;
    private final float green;
    private final float blue;
    private final float alpha;

    PreviewLayer(float red, float green, float blue, float alpha) {
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
    }

    float red() {
        return red;
    }

    float green() {
        return green;
    }

    float blue() {
        return blue;
    }

    float alpha() {
        return alpha;
    }
}
