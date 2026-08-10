package dev.ssa.construction.spike.persistence;

import java.util.Arrays;
import java.util.Objects;

public final class StackSnapshot implements EvidenceSnapshot {
    private static final int MAX_COMPONENT_BYTES = 1_048_576;
    private static final StackSnapshot EMPTY = new StackSnapshot("", 0, new byte[0]);

    private final String itemId;
    private final int count;
    private final byte[] componentsPayload;

    private StackSnapshot(String itemId, int count, byte[] componentsPayload) {
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        this.componentsPayload = Objects.requireNonNull(componentsPayload, "componentsPayload").clone();
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        if ((count == 0) != itemId.isEmpty()) {
            throw new IllegalArgumentException("only the empty stack may have a zero count or empty item ID");
        }
        if (this.componentsPayload.length > MAX_COMPONENT_BYTES) {
            throw new IllegalArgumentException("component payload exceeds 1 MiB");
        }
        this.count = count;
    }

    public static StackSnapshot of(String itemId, int count, byte[] componentsPayload) {
        return new StackSnapshot(itemId, count, componentsPayload);
    }

    public static StackSnapshot empty() {
        return EMPTY;
    }

    public String itemId() {
        return itemId;
    }

    public int count() {
        return count;
    }

    public byte[] componentsPayload() {
        return componentsPayload.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof StackSnapshot snapshot
                && count == snapshot.count
                && itemId.equals(snapshot.itemId)
                && Arrays.equals(componentsPayload, snapshot.componentsPayload);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(itemId, count) + Arrays.hashCode(componentsPayload);
    }

    @Override
    public String toString() {
        return "StackSnapshot[itemId=" + itemId + ", count=" + count
                + ", componentsBytes=" + componentsPayload.length + "]";
    }
}
