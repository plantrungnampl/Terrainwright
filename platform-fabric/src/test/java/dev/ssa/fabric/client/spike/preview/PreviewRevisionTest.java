package dev.ssa.fabric.client.spike.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class PreviewRevisionTest {
    @Test
    void fixturesAreBoundedImmutableAndLayered() {
        PreviewRevision small = PreviewFixtures.create(1, 1_000, 10, 80, 20, 7);
        PreviewRevision large = PreviewFixtures.create(2, 5_000, 10, 80, 20, 7);

        assertEquals(1_000, small.blockCount());
        assertEquals(5_000, large.blockCount());
        assertEquals(1_000, totalLayerCount(small));
        assertEquals(5_000, totalLayerCount(large));
        assertThrows(
                IllegalArgumentException.class,
                () -> PreviewFixtures.create(3, 5_001, 10, 80, 20, 7));
    }

    private static int totalLayerCount(PreviewRevision revision) {
        int total = 0;
        for (PreviewLayer layer : PreviewLayer.values()) {
            total += revision.layerCount(layer);
        }
        return total;
    }

    @Test
    void constructorCopiesMutableInputs() {
        int[] x = {1};
        int[] y = {2};
        int[] z = {3};
        PreviewLayer[] layers = {PreviewLayer.REQUIRED};
        PreviewRevision revision = new PreviewRevision(1, 11, 0, 0, 0, 0, x, y, z, layers);

        x[0] = 99;
        y[0] = 99;
        z[0] = 99;
        layers[0] = PreviewLayer.CONFLICT;

        assertEquals(1, revision.xAt(0));
        assertEquals(2, revision.yAt(0));
        assertEquals(3, revision.zAt(0));
        assertEquals(PreviewLayer.REQUIRED, revision.layerAt(0));
    }

    @Test
    void rotationCreatesANewRevisionWithoutMutatingSource() {
        PreviewRevision source = new PreviewRevision(
                4,
                41,
                10,
                80,
                20,
                0,
                new int[] {2},
                new int[] {3},
                new int[] {5},
                new PreviewLayer[] {PreviewLayer.OPTIONAL});

        PreviewRevision rotated = source.rotateClockwise(5);

        assertEquals(2, source.xAt(0));
        assertEquals(5, source.zAt(0));
        assertEquals(-5, rotated.xAt(0));
        assertEquals(2, rotated.zAt(0));
        assertEquals(0, source.rotationQuarterTurns());
        assertEquals(1, rotated.rotationQuarterTurns());
        assertEquals(5, rotated.revision());
    }

    @Test
    void regenerationChangesIdentityWithoutMutatingSource() {
        PreviewRevision source = PreviewFixtures.create(8, 1_000, 10, 80, 20, 17);
        PreviewRevision regenerated = PreviewFixtures.create(9, 1_000, 10, 80, 20, 18);

        assertEquals(8, source.revision());
        assertEquals(9, regenerated.revision());
        assertNotEquals(source.contentIdentity(), regenerated.contentIdentity());
        assertNotEquals(source.layerAt(0), regenerated.layerAt(0));
    }
}
