package dev.ssa.architect.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class TerrainSnapshotTest {
    @Test
    void snapshotIsDetachedAndUsesRowMajorBoundedQueries() {
        List<Integer> surfaceY = new ArrayList<>(List.of(64, 65, 63, 64));
        List<NamespacedId> materials = new ArrayList<>(List.of(
                id("minecraft:grass_block"),
                id("minecraft:stone"),
                id("minecraft:dirt"),
                id("minecraft:grass_block")));
        Set<GridPos> obstructions = new HashSet<>(Set.of(new GridPos(11, 66, 21)));
        Set<Integer> water = new HashSet<>(Set.of(1));
        List<GridPos> village = new ArrayList<>(List.of(new GridPos(30, 64, 40)));
        Map<NamespacedId, List<GridPos>> nearby = new HashMap<>();
        nearby.put(id("minecraft:village"), village);

        TerrainSnapshot snapshot = new TerrainSnapshot(
                new GridPos(10, 64, 20),
                2,
                2,
                60,
                70,
                surfaceY,
                materials,
                obstructions,
                water,
                Set.of(),
                Set.of(2),
                new TerrainSnapshot.SlopeMetrics(0.75, 2.0),
                nearby,
                "sha256:terrain-revision-1");

        surfaceY.set(0, 70);
        materials.clear();
        obstructions.clear();
        water.clear();
        village.clear();
        nearby.clear();

        assertEquals(64, snapshot.surfaceYAt(0, 0));
        assertEquals(65, snapshot.surfaceYAt(1, 0));
        assertEquals(id("minecraft:dirt"), snapshot.surfaceMaterialAt(0, 1));
        assertTrue(snapshot.isWaterAt(1, 0));
        assertTrue(snapshot.isTreeAt(0, 1));
        assertFalse(snapshot.isLavaAt(0, 0));
        assertEquals(Set.of(new GridPos(11, 66, 21)), snapshot.obstructionFlags());
        assertEquals(
                List.of(new GridPos(30, 64, 40)),
                snapshot.nearbyFeatureVectors().get(id("minecraft:village")));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.surfaceY().add(66));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.nearbyFeatureVectors().get(id("minecraft:village")).clear());
        assertThrows(IndexOutOfBoundsException.class, () -> snapshot.surfaceYAt(2, 0));
    }

    @Test
    void rejectsMalformedOrUnboundedSnapshotData() {
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(0, 2, List.of(), List.of(), Set.of(), new TerrainSnapshot.SlopeMetrics(0, 0), "rev"));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(2, 2, List.of(64), List.of(id("minecraft:stone")), Set.of(),
                        new TerrainSnapshot.SlopeMetrics(0, 0), "rev"));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(1, 1, List.of(80), List.of(id("minecraft:stone")), Set.of(),
                        new TerrainSnapshot.SlopeMetrics(0, 0), "rev"));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(1, 1, List.of(64), List.of(id("minecraft:stone")), Set.of(1),
                        new TerrainSnapshot.SlopeMetrics(0, 0), "rev"));
        assertThrows(IllegalArgumentException.class, () -> new TerrainSnapshot.SlopeMetrics(Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> new TerrainSnapshot.SlopeMetrics(2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(1, 1, List.of(64), List.of(id("minecraft:stone")), Set.of(),
                        new TerrainSnapshot.SlopeMetrics(0, 0), " "));
    }

    private static TerrainSnapshot snapshot(
            int width,
            int depth,
            List<Integer> heights,
            List<NamespacedId> materials,
            Set<Integer> water,
            TerrainSnapshot.SlopeMetrics slope,
            String revision) {
        return new TerrainSnapshot(
                new GridPos(0, 64, 0),
                width,
                depth,
                60,
                70,
                heights,
                materials,
                Set.of(),
                water,
                Set.of(),
                Set.of(),
                slope,
                Map.of(),
                revision);
    }

    private static NamespacedId id(String value) {
        return NamespacedId.parse(value);
    }
}
