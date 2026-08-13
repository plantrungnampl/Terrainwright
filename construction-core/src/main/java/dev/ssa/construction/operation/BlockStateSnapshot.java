package dev.ssa.construction.operation;

import java.util.Arrays;
import java.util.Objects;

public final class BlockStateSnapshot implements EvidenceSnapshot {
    private static final int MAX_PROPERTIES_BYTES = 65_536;

    private final String blockId;
    private final byte[] propertiesPayload;

    private BlockStateSnapshot(String blockId, byte[] propertiesPayload) {
        this.blockId = Objects.requireNonNull(blockId, "blockId");
        this.propertiesPayload = Objects.requireNonNull(propertiesPayload, "propertiesPayload").clone();
        if (blockId.isBlank()) {
            throw new IllegalArgumentException("blockId must not be blank");
        }
        if (this.propertiesPayload.length > MAX_PROPERTIES_BYTES) {
            throw new IllegalArgumentException("property payload exceeds 64 KiB");
        }
    }

    public static BlockStateSnapshot of(String blockId, byte[] propertiesPayload) {
        return new BlockStateSnapshot(blockId, propertiesPayload);
    }

    public String blockId() {
        return blockId;
    }

    public byte[] propertiesPayload() {
        return propertiesPayload.clone();
    }

    public int propertiesPayloadSize() {
        return propertiesPayload.length;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof BlockStateSnapshot snapshot
                && blockId.equals(snapshot.blockId)
                && Arrays.equals(propertiesPayload, snapshot.propertiesPayload);
    }

    @Override
    public int hashCode() {
        return 31 * blockId.hashCode() + Arrays.hashCode(propertiesPayload);
    }

    @Override
    public String toString() {
        return "BlockStateSnapshot[blockId=" + blockId
                + ", propertiesBytes=" + propertiesPayload.length + "]";
    }
}
