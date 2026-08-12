package dev.ssa.architect.scoring;

public record ScoreBreakdown(
        double layoutQuality,
        double terrainFit,
        double styleConsistency,
        double accessibility,
        double materialEfficiency,
        double scenicOrientation,
        double total) {
    public ScoreBreakdown {
        requireUnit(layoutQuality, "layoutQuality");
        requireUnit(terrainFit, "terrainFit");
        requireUnit(styleConsistency, "styleConsistency");
        requireUnit(accessibility, "accessibility");
        requireUnit(materialEfficiency, "materialEfficiency");
        requireUnit(scenicOrientation, "scenicOrientation");
        requireUnit(total, "total");
        double expected = BlueprintScorer.weightedTotal(
                layoutQuality,
                terrainFit,
                styleConsistency,
                accessibility,
                materialEfficiency,
                scenicOrientation);
        if (Math.abs(total - expected) > 1.0e-12) {
            throw new IllegalArgumentException("total must match the exact R2 weighted score");
        }
    }

    public static ScoreBreakdown unscored() {
        return new ScoreBreakdown(0, 0, 0, 0, 0, 0, 0);
    }

    private static void requireUnit(double value, String label) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(label + " must be finite and between 0 and 1");
        }
    }
}
