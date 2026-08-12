package dev.ssa.fabric.client.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ssa.architect.model.GridPos;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class PreviewTransformTest {
    @Test
    void rotatingPreviewFourTimesReturnsOriginalCoordinates() {
        GridPos original = new GridPos(3, 2, 7);
        GridPos rotated = original;

        for (int turn = 0; turn < 4; turn++) {
            rotated = PreviewTransform.rotate90(rotated);
        }

        assertEquals(original, rotated);
    }

    @Test
    void rotationUsesQuarterTurnsAndPreservesHeight() {
        GridPos position = new GridPos(3, 2, 7);

        assertEquals(new GridPos(-7, 2, 3), PreviewTransform.rotate(position, 90));
        assertEquals(new GridPos(-3, 2, -7), PreviewTransform.rotate(position, 180));
        assertEquals(new GridPos(7, 2, -3), PreviewTransform.rotate(position, 270));
    }

    @Test
    void worldPositionAddsTrustedServerOriginAfterRotation() {
        BlockPos origin = new BlockPos(100, 64, -40);

        assertEquals(
                new BlockPos(93, 66, -37),
                PreviewTransform.toWorld(origin, new GridPos(3, 2, 7), 90));
    }
}
