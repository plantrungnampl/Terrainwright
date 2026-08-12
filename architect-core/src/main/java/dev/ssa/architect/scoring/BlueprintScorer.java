package dev.ssa.architect.scoring;

import java.util.Objects;

public final class BlueprintScorer {
    private static final double LAYOUT_WEIGHT = 0.28;
    private static final double TERRAIN_WEIGHT = 0.24;
    private static final double STYLE_WEIGHT = 0.20;
    private static final double ACCESSIBILITY_WEIGHT = 0.12;
    private static final double MATERIAL_EFFICIENCY_WEIGHT = 0.10;
    private static final double SCENIC_ORIENTATION_WEIGHT = 0.06;

    public ScoreBreakdown score(Metrics metrics) {
        Objects.requireNonNull(metrics, "metrics");
        double total = weightedTotal(
                metrics.layoutQuality(),
                metrics.terrainFit(),
                metrics.styleConsistency(),
                metrics.accessibility(),
                metrics.materialEfficiency(),
                metrics.scenicOrientation());
        return new ScoreBreakdown(
                metrics.layoutQuality(),
                metrics.terrainFit(),
                metrics.styleConsistency(),
                metrics.accessibility(),
                metrics.materialEfficiency(),
                metrics.scenicOrientation(),
                total);
    }

    static double weightedTotal(
            double layoutQuality,
            double terrainFit,
            double styleConsistency,
            double accessibility,
            double materialEfficiency,
            double scenicOrientation) {
        return layoutQuality * LAYOUT_WEIGHT
                + terrainFit * TERRAIN_WEIGHT
                + styleConsistency * STYLE_WEIGHT
                + accessibility * ACCESSIBILITY_WEIGHT
                + materialEfficiency * MATERIAL_EFFICIENCY_WEIGHT
                + scenicOrientation * SCENIC_ORIENTATION_WEIGHT;
    }

    public record Metrics(
            double layoutQuality,
            double terrainFit,
            double styleConsistency,
            double accessibility,
            double materialEfficiency,
            double scenicOrientation) {
        public Metrics {
            requireUnit(layoutQuality, "layoutQuality");
            requireUnit(terrainFit, "terrainFit");
            requireUnit(styleConsistency, "styleConsistency");
            requireUnit(accessibility, "accessibility");
            requireUnit(materialEfficiency, "materialEfficiency");
            requireUnit(scenicOrientation, "scenicOrientation");
        }

        private static void requireUnit(double value, String label) {
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException(label + " must be finite and between 0 and 1");
            }
        }
    }
}
