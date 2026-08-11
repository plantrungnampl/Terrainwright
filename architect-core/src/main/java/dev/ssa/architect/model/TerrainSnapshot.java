package dev.ssa.architect.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record TerrainSnapshot(
        GridPos origin,
        int width,
        int depth,
        int minY,
        int maxY,
        List<Integer> surfaceY,
        List<NamespacedId> surfaceMaterials,
        Set<GridPos> obstructionFlags,
        Set<Integer> waterMask,
        Set<Integer> lavaMask,
        Set<Integer> treeMask,
        SlopeMetrics slopeMetrics,
        Map<NamespacedId, List<GridPos>> nearbyFeatureVectors,
        String revisionFingerprint) {
    public TerrainSnapshot {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(slopeMetrics, "slopeMetrics");
        if (width <= 0 || depth <= 0) {
            throw new IllegalArgumentException("Terrain dimensions must be positive");
        }
        if (minY > maxY) {
            throw new IllegalArgumentException("minY must not exceed maxY");
        }

        int area;
        try {
            area = Math.multiplyExact(width, depth);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Terrain dimensions are too large", exception);
        }

        surfaceY = List.copyOf(Objects.requireNonNull(surfaceY, "surfaceY"));
        surfaceMaterials = List.copyOf(Objects.requireNonNull(surfaceMaterials, "surfaceMaterials"));
        if (surfaceY.size() != area || surfaceMaterials.size() != area) {
            throw new IllegalArgumentException("Surface arrays must contain width * depth entries");
        }
        for (int height : surfaceY) {
            if (height < minY || height > maxY) {
                throw new IllegalArgumentException("Surface height is outside the snapshot bounds: " + height);
            }
        }

        obstructionFlags = Set.copyOf(Objects.requireNonNull(obstructionFlags, "obstructionFlags"));
        waterMask = copyMask(waterMask, area, "waterMask");
        lavaMask = copyMask(lavaMask, area, "lavaMask");
        treeMask = copyMask(treeMask, area, "treeMask");
        nearbyFeatureVectors = copyFeatureVectors(nearbyFeatureVectors);
        if (revisionFingerprint == null || revisionFingerprint.isBlank()) {
            throw new IllegalArgumentException("Revision fingerprint must not be blank");
        }
    }

    public int surfaceYAt(int localX, int localZ) {
        return surfaceY.get(index(localX, localZ));
    }

    public NamespacedId surfaceMaterialAt(int localX, int localZ) {
        return surfaceMaterials.get(index(localX, localZ));
    }

    public boolean isWaterAt(int localX, int localZ) {
        return waterMask.contains(index(localX, localZ));
    }

    public boolean isLavaAt(int localX, int localZ) {
        return lavaMask.contains(index(localX, localZ));
    }

    public boolean isTreeAt(int localX, int localZ) {
        return treeMask.contains(index(localX, localZ));
    }

    private int index(int localX, int localZ) {
        if (localX < 0 || localX >= width || localZ < 0 || localZ >= depth) {
            throw new IndexOutOfBoundsException(
                    "Local position is outside the terrain snapshot: " + localX + "," + localZ);
        }
        return localZ * width + localX;
    }

    private static Set<Integer> copyMask(Set<Integer> mask, int area, String name) {
        Set<Integer> copy = Set.copyOf(Objects.requireNonNull(mask, name));
        for (int index : copy) {
            if (index < 0 || index >= area) {
                throw new IllegalArgumentException(name + " index is outside the terrain snapshot: " + index);
            }
        }
        return copy;
    }

    private static Map<NamespacedId, List<GridPos>> copyFeatureVectors(
            Map<NamespacedId, List<GridPos>> vectors) {
        Objects.requireNonNull(vectors, "nearbyFeatureVectors");
        Map<NamespacedId, List<GridPos>> copy = new LinkedHashMap<>();
        vectors.forEach((feature, positions) -> copy.put(
                Objects.requireNonNull(feature, "nearby feature"),
                List.copyOf(Objects.requireNonNull(positions, "nearby feature positions"))));
        return Collections.unmodifiableMap(copy);
    }

    public record SlopeMetrics(double meanSlope, double maxSlope) {
        public SlopeMetrics {
            if (!Double.isFinite(meanSlope) || !Double.isFinite(maxSlope)
                    || meanSlope < 0 || maxSlope < 0 || meanSlope > maxSlope) {
                throw new IllegalArgumentException("Slope metrics must be finite and satisfy 0 <= mean <= max");
            }
        }
    }
}
