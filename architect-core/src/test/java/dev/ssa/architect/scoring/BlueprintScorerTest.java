package dev.ssa.architect.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class BlueprintScorerTest {
    private final BlueprintScorer scorer = new BlueprintScorer();

    @Test
    void appliesTheExactR2Weights() {
        assertEquals(0.28, score(1, 0, 0, 0, 0, 0).total());
        assertEquals(0.24, score(0, 1, 0, 0, 0, 0).total());
        assertEquals(0.20, score(0, 0, 1, 0, 0, 0).total());
        assertEquals(0.12, score(0, 0, 0, 1, 0, 0).total());
        assertEquals(0.10, score(0, 0, 0, 0, 1, 0).total());
        assertEquals(0.06, score(0, 0, 0, 0, 0, 1).total());
        assertEquals(1.0, score(1, 1, 1, 1, 1, 1).total());
    }

    @Test
    void preservesEveryNormalizedComponent() {
        ScoreBreakdown score = score(0.9, 0.8, 0.7, 0.6, 0.5, 0.4);

        assertEquals(0.9, score.layoutQuality());
        assertEquals(0.8, score.terrainFit());
        assertEquals(0.7, score.styleConsistency());
        assertEquals(0.6, score.accessibility());
        assertEquals(0.5, score.materialEfficiency());
        assertEquals(0.4, score.scenicOrientation());
        assertEquals(0.730, score.total(), 1.0e-12);
    }

    @Test
    void rejectsNonFiniteAndOutOfRangeMetrics() {
        assertThrows(IllegalArgumentException.class, () -> score(-0.01, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> score(0, 1.01, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> score(0, 0, Double.NaN, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> score(0, 0, 0, Double.POSITIVE_INFINITY, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ScoreBreakdown(1, 1, 1, 1, 1, 1, 0.99));
    }

    private ScoreBreakdown score(
            double layout,
            double terrain,
            double style,
            double accessibility,
            double materialEfficiency,
            double scenicOrientation) {
        return scorer.score(new BlueprintScorer.Metrics(
                layout,
                terrain,
                style,
                accessibility,
                materialEfficiency,
                scenicOrientation));
    }
}
