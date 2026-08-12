package dev.ssa.fabric.client.preview;

import dev.ssa.architect.model.GridPos;
import java.util.Objects;
import net.minecraft.core.BlockPos;

public final class PreviewTransform {
    private PreviewTransform() {}

    public static GridPos rotate90(GridPos position) {
        Objects.requireNonNull(position, "position");
        return new GridPos(-position.z(), position.y(), position.x());
    }

    public static GridPos rotate(GridPos position, int rotationDegrees) {
        Objects.requireNonNull(position, "position");
        requireRotation(rotationDegrees);
        GridPos rotated = position;
        for (int turn = 0; turn < rotationDegrees / 90; turn++) {
            rotated = rotate90(rotated);
        }
        return rotated;
    }

    public static BlockPos toWorld(BlockPos origin, GridPos localPosition, int rotationDegrees) {
        Objects.requireNonNull(origin, "origin");
        GridPos rotated = rotate(localPosition, rotationDegrees);
        return origin.offset(rotated.x(), rotated.y(), rotated.z());
    }

    private static void requireRotation(int rotationDegrees) {
        if (rotationDegrees != 0
                && rotationDegrees != 90
                && rotationDegrees != 180
                && rotationDegrees != 270) {
            throw new IllegalArgumentException("Rotation must be 0, 90, 180, or 270 degrees");
        }
    }
}
