package dev.ssa.construction.spike.persistence;

import java.util.Objects;

public record InventoryDelta(
        String inventoryId,
        int bindingRevision,
        int slot,
        StackSnapshot before,
        StackSnapshot after) implements OperationDelta {
    public InventoryDelta {
        Objects.requireNonNull(inventoryId, "inventoryId");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (inventoryId.isBlank() || inventoryId.length() > 160) {
            throw new IllegalArgumentException("inventoryId must contain 1 to 160 characters");
        }
        if (bindingRevision < 0) {
            throw new IllegalArgumentException("bindingRevision must not be negative");
        }
        if (slot < 0 || slot > 4095) {
            throw new IllegalArgumentException("slot must be between 0 and 4095");
        }
        if (before.equals(after)) {
            throw new IllegalArgumentException("inventory delta must change its slot");
        }
    }

    @Override
    public String evidenceKey() {
        return "inventory:" + inventoryId + "@revision=" + bindingRevision + "/slot=" + slot;
    }
}
