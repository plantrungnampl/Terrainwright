package dev.ssa.construction.spike.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class OperationIntentValidationTest {
    @Test
    void stackAndBlockPayloadsAreDefensivelyCopied() {
        byte[] stackPayload = bytes("custom-name=original");
        byte[] blockPayload = bytes("facing=north");
        StackSnapshot stack = StackSnapshot.of("minecraft:oak_planks", 1, stackPayload);
        BlockStateSnapshot block = BlockStateSnapshot.of("minecraft:oak_stairs", blockPayload);

        stackPayload[0] = 'X';
        blockPayload[0] = 'X';
        byte[] returnedStack = stack.componentsPayload();
        byte[] returnedBlock = block.propertiesPayload();
        returnedStack[0] = 'Y';
        returnedBlock[0] = 'Y';

        assertArrayEquals(bytes("custom-name=original"), stack.componentsPayload());
        assertArrayEquals(bytes("facing=north"), block.propertiesPayload());
    }

    @Test
    void emptyStackCannotCarryComponents() {
        assertThrows(IllegalArgumentException.class, () ->
                StackSnapshot.of("", 0, bytes("noncanonical-components")));
    }

    @Test
    void intentCopiesItsDeltasAndStartsPrepared() {
        List<OperationDelta> deltas = new ArrayList<>(validTransferDeltas());
        OperationIntent intent = OperationIntent.prepared("op", "job", 9, OperationKind.MATERIAL_TRANSFER, deltas);

        deltas.clear();

        assertEquals(2, intent.deltas().size());
        assertEquals(OperationStatus.PREPARED, intent.status());
        assertThrows(UnsupportedOperationException.class, () -> intent.deltas().clear());
    }

    @Test
    void materialTransferRequiresAtLeastTwoInventoryDeltasAndNoWorldDelta() {
        assertThrows(IllegalArgumentException.class, () -> OperationIntent.prepared(
                "op", "job", 0, OperationKind.MATERIAL_TRANSFER, List.of(validTransferDeltas().getFirst())));

        List<OperationDelta> mixed = new ArrayList<>(validTransferDeltas());
        mixed.add(worldDelta(0));
        assertThrows(IllegalArgumentException.class, () -> OperationIntent.prepared(
                "op", "job", 0, OperationKind.MATERIAL_TRANSFER, mixed));
    }

    @Test
    void worldMutationRequiresAtLeastOneWorldDelta() {
        assertThrows(IllegalArgumentException.class, () -> OperationIntent.prepared(
                "op", "job", 0, OperationKind.WORLD_MUTATION, validTransferDeltas()));
    }

    @Test
    void rejectsDuplicateEvidenceKeysAndNoOpDeltas() {
        InventoryDelta first = (InventoryDelta) validTransferDeltas().getFirst();
        assertThrows(IllegalArgumentException.class, () -> OperationIntent.prepared(
                "op", "job", 0, OperationKind.MATERIAL_TRANSFER, List.of(first, first)));

        StackSnapshot unchanged = StackSnapshot.empty();
        assertThrows(IllegalArgumentException.class, () -> new InventoryDelta(
                "builder", 1, 0, unchanged, unchanged));
    }

    @Test
    void enforcesSchemaDeltaBounds() {
        List<OperationDelta> inventoryDeltas = new ArrayList<>();
        for (int slot = 0; slot < 257; slot++) {
            inventoryDeltas.add(new InventoryDelta("inventory", 1, slot,
                    StackSnapshot.empty(), stack("minecraft:stone", 1, "{}")));
        }
        assertThrows(IllegalArgumentException.class, () -> OperationIntent.prepared(
                "op", "job", 0, OperationKind.MATERIAL_TRANSFER, inventoryDeltas));

        List<OperationDelta> worldDeltas = new ArrayList<>();
        for (int x = 0; x < 65; x++) {
            worldDeltas.add(worldDelta(x));
        }
        assertThrows(IllegalArgumentException.class, () -> OperationIntent.prepared(
                "op", "job", 0, OperationKind.WORLD_MUTATION, worldDeltas));
    }

    private static List<OperationDelta> validTransferDeltas() {
        return List.of(
                new InventoryDelta("chest", 1, 0,
                        stack("minecraft:stone", 2, "{}"), stack("minecraft:stone", 1, "{}")),
                new InventoryDelta("builder", 1, 0,
                        StackSnapshot.empty(), stack("minecraft:stone", 1, "{}")));
    }

    private static WorldDelta worldDelta(int x) {
        return new WorldDelta("minecraft:overworld", x, 64, 0,
                BlockStateSnapshot.of("minecraft:air", bytes("")),
                BlockStateSnapshot.of("minecraft:stone", bytes("")),
                DropPolicy.NOT_APPLICABLE);
    }

    private static StackSnapshot stack(String itemId, int count, String payload) {
        return StackSnapshot.of(itemId, count, bytes(payload));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
