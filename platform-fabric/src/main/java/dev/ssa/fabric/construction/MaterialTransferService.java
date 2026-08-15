package dev.ssa.fabric.construction;

import dev.ssa.construction.operation.InventoryDelta;
import dev.ssa.construction.operation.OperationIntent;
import dev.ssa.construction.operation.OperationKind;
import dev.ssa.common.permission.PermissionPort;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

public final class MaterialTransferService {
    private final FabricMutationExecutor executor;

    public MaterialTransferService(FabricMutationExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public CompletableFuture<CoordinatorResult> transfer(
            String operationId,
            String jobId,
            Optional<String> taskId,
            long jobRevision,
            ServerLevel level,
            UUID owner,
            PermissionPort permissions,
            FabricMutationExecutor.BoundInventory source,
            int sourceSlot,
            FabricMutationExecutor.BoundInventory destination,
            int destinationSlot,
            int count,
            FabricMutationExecutor.CommitLog commitLog) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(level, "level");
        validateSlot(source, sourceSlot, "sourceSlot");
        validateSlot(destination, destinationSlot, "destinationSlot");
        if (count < 1) {
            throw new IllegalArgumentException("transfer count must be positive");
        }
        if (source.inventoryId().equals(destination.inventoryId())) {
            throw new IllegalArgumentException("source and destination inventories must be distinct");
        }

        ItemStack sourceBefore = source.container().getItem(sourceSlot).copy();
        ItemStack destinationBefore = destination.container().getItem(destinationSlot).copy();
        if (sourceBefore.isEmpty() || sourceBefore.getCount() < count) {
            throw new IllegalStateException("source slot does not contain the requested material count");
        }
        if (!source.container().canTakeItem(destination.container(), sourceSlot, sourceBefore)) {
            throw new IllegalStateException("source slot rejects transfer to the destination inventory");
        }
        if (!destinationBefore.isEmpty()
                && !ItemStack.isSameItemSameComponents(sourceBefore, destinationBefore)) {
            throw new IllegalStateException("destination slot contains a different item or component payload");
        }
        if (!destination.container().canPlaceItem(destinationSlot, sourceBefore)) {
            throw new IllegalStateException("destination slot rejects the requested material");
        }
        int destinationCount = destinationBefore.getCount() + count;
        if (destinationCount > destination.container().getMaxStackSize(sourceBefore)) {
            throw new IllegalStateException("destination slot cannot hold the requested material count");
        }

        ItemStack sourceAfter = sourceBefore.getCount() == count
                ? ItemStack.EMPTY
                : sourceBefore.copyWithCount(sourceBefore.getCount() - count);
        ItemStack destinationAfter = destinationBefore.isEmpty()
                ? sourceBefore.copyWithCount(count)
                : destinationBefore.copyWithCount(destinationCount);
        MinecraftSnapshotAdapter snapshots = new MinecraftSnapshotAdapter(level.registryAccess());
        OperationIntent intent = OperationIntent.prepared(
                operationId,
                jobId,
                taskId,
                Optional.empty(),
                jobRevision,
                OperationKind.MATERIAL_TRANSFER,
                List.of(
                        new InventoryDelta(
                                source.inventoryId(),
                                source.bindingRevision(),
                                sourceSlot,
                                snapshots.snapshot(sourceBefore),
                                snapshots.snapshot(sourceAfter)),
                        new InventoryDelta(
                                destination.inventoryId(),
                                destination.bindingRevision(),
                                destinationSlot,
                                snapshots.snapshot(destinationBefore),
                                snapshots.snapshot(destinationAfter))));
        return executor.execute(
                intent,
                level,
                owner,
                permissions,
                List.of(source, destination),
                commitLog);
    }

    private static void validateSlot(
            FabricMutationExecutor.BoundInventory inventory,
            int slot,
            String name) {
        Objects.requireNonNull(inventory, name + "Inventory");
        if (slot < 0 || slot >= inventory.container().getContainerSize()) {
            throw new IllegalArgumentException(name + " is outside the bound inventory");
        }
    }
}
