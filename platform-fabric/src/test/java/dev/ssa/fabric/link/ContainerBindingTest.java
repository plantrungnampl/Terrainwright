package dev.ssa.fabric.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class ContainerBindingTest {
    private static final Identifier DIMENSION = Identifier.parse("minecraft:overworld");

    @Test
    void doubleChestHalvesCanonicalizeToOneIdentityAndRevision() {
        BlockPos first = new BlockPos(5, 64, 8);
        BlockPos second = first.east();

        ContainerBinding fromFirst = ContainerBinding.resolve(
                DIMENSION, first, Optional.of(second), Optional.empty());
        ContainerBinding fromSecond = ContainerBinding.resolve(
                DIMENSION, second, Optional.of(first), Optional.empty());

        assertEquals(fromFirst, fromSecond);
        assertEquals(first, fromFirst.primaryPos());
        assertEquals(Optional.of(second), fromFirst.partnerPos());
        assertEquals(1, fromFirst.revision());
    }

    @Test
    void identicalRelinkIsIdempotentButTopologyChangeGetsNewIdentityAndRevision() {
        BlockPos first = new BlockPos(5, 64, 8);
        BlockPos second = first.east();
        ContainerBinding doubleChest = ContainerBinding.resolve(
                DIMENSION, first, Optional.of(second), Optional.empty());

        ContainerBinding same = ContainerBinding.resolve(
                DIMENSION, second, Optional.of(first), Optional.of(doubleChest));
        ContainerBinding split = ContainerBinding.resolve(
                DIMENSION, first, Optional.empty(), Optional.of(doubleChest));

        assertSame(doubleChest, same);
        assertEquals(2, split.revision());
        assertFalse(doubleChest.inventoryId().equals(split.inventoryId()));
        assertTrue(doubleChest.matchesTopology(DIMENSION, second, Optional.of(first)));
        assertFalse(doubleChest.matchesTopology(DIMENSION, first, Optional.empty()));
    }

    @Test
    void nonAdjacentPartnerCannotEnterDurableBinding() {
        assertThrows(IllegalArgumentException.class, () -> ContainerBinding.resolve(
                DIMENSION,
                new BlockPos(5, 64, 8),
                Optional.of(new BlockPos(7, 64, 8)),
                Optional.empty()));
    }
}
