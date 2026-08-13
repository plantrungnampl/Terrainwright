package dev.ssa.construction.operation;

import java.util.Objects;

public record WorldDelta(
        String worldId,
        int x,
        int y,
        int z,
        BlockStateSnapshot before,
        BlockStateSnapshot after,
        DropPolicy dropPolicy) implements OperationDelta {
    public WorldDelta {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(dropPolicy, "dropPolicy");
        if (worldId.isBlank() || worldId.length() > 160) {
            throw new IllegalArgumentException("worldId must contain 1 to 160 characters");
        }
        if (before.equals(after)) {
            throw new IllegalArgumentException("world delta must change its cell");
        }
        if (!before.blockId().equals("minecraft:air") && dropPolicy != DropPolicy.SUPPRESS) {
            throw new IllegalArgumentException("non-air world replacement must suppress drops");
        }
    }

    @Override
    public String evidenceKey() {
        return "world:" + worldId + "@" + x + "," + y + "," + z;
    }
}
