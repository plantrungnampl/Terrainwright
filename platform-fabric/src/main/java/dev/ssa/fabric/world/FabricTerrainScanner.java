package dev.ssa.fabric.world;

import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.TerrainSnapshot;
import dev.ssa.architect.model.TerrainSnapshot.SlopeMetrics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

public final class FabricTerrainScanner {
    public static final int MAX_DIMENSION = 33;
    public static final int MAX_AREA = MAX_DIMENSION * MAX_DIMENSION;

    public Optional<TerrainSnapshot> scan(
            ServerLevel level,
            BlockPos anchor,
            int width,
            int depth) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(anchor, "anchor");
        if (width <= 0 || depth <= 0 || width > MAX_DIMENSION || depth > MAX_DIMENSION) {
            throw new IllegalArgumentException("Terrain scan dimensions must be between 1 and " + MAX_DIMENSION);
        }
        if ((long) width * depth > MAX_AREA) {
            throw new IllegalArgumentException("Terrain scan area exceeds the V1 bound");
        }

        for (int localZ = 0; localZ < depth; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                BlockPos probe = new BlockPos(
                        anchor.getX() + localX,
                        anchor.getY(),
                        anchor.getZ() + localZ);
                if (!level.isLoaded(probe)) {
                    return Optional.empty();
                }
            }
        }

        List<Integer> heights = new ArrayList<>(width * depth);
        List<NamespacedId> materials = new ArrayList<>(width * depth);
        Set<GridPos> obstructions = new HashSet<>();
        Set<Integer> water = new HashSet<>();
        Set<Integer> lava = new HashSet<>();
        Set<Integer> trees = new HashSet<>();
        MessageDigest digest = sha256();
        update(digest, level.dimension().identifier().toString());
        update(digest, anchor.getX() + ":" + anchor.getY() + ":" + anchor.getZ());
        int minimumSurfaceY = Integer.MAX_VALUE;
        int maximumSurfaceY = Integer.MIN_VALUE;

        for (int localZ = 0; localZ < depth; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int worldX = anchor.getX() + localX;
                int worldZ = anchor.getZ() + localZ;
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
                if (surfaceY < level.getMinY()) {
                    return Optional.empty();
                }
                BlockPos surfacePos = new BlockPos(worldX, surfaceY, worldZ);
                if (!level.isLoaded(surfacePos)) {
                    return Optional.empty();
                }
                BlockState state = level.getBlockState(surfacePos);
                if (state.isAir()) {
                    return Optional.empty();
                }
                NamespacedId material = NamespacedId.parse(
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                int index = localZ * width + localX;
                heights.add(surfaceY);
                materials.add(material);
                minimumSurfaceY = Math.min(minimumSurfaceY, surfaceY);
                maximumSurfaceY = Math.max(maximumSurfaceY, surfaceY);
                if (state.getFluidState().is(FluidTags.WATER)) {
                    water.add(index);
                }
                if (state.getFluidState().is(FluidTags.LAVA)) {
                    lava.add(index);
                }
                if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
                    trees.add(index);
                }
                if (level.getBlockEntity(surfacePos) != null) {
                    obstructions.add(new GridPos(localX, surfaceY - anchor.getY(), localZ));
                }
                update(digest, worldX + ":" + surfaceY + ":" + worldZ + ":" + state);
            }
        }

        SlopeMetrics slopes = slopes(heights, width, depth);
        TerrainSnapshot snapshot = new TerrainSnapshot(
                new GridPos(anchor.getX(), anchor.getY(), anchor.getZ()),
                width,
                depth,
                minimumSurfaceY,
                maximumSurfaceY,
                heights,
                materials,
                obstructions,
                water,
                lava,
                trees,
                slopes,
                Map.of(),
                HexFormat.of().formatHex(digest.digest()));
        return Optional.of(snapshot);
    }

    private static SlopeMetrics slopes(List<Integer> heights, int width, int depth) {
        long totalDifference = 0;
        int comparisons = 0;
        int maximumDifference = 0;
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int height = heights.get(z * width + x);
                if (x + 1 < width) {
                    int difference = Math.abs(height - heights.get(z * width + x + 1));
                    totalDifference += difference;
                    maximumDifference = Math.max(maximumDifference, difference);
                    comparisons++;
                }
                if (z + 1 < depth) {
                    int difference = Math.abs(height - heights.get((z + 1) * width + x));
                    totalDifference += difference;
                    maximumDifference = Math.max(maximumDifference, difference);
                    comparisons++;
                }
            }
        }
        double mean = comparisons == 0 ? 0 : (double) totalDifference / comparisons;
        return new SlopeMetrics(mean, maximumDifference);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
