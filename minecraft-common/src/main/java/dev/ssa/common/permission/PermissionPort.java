package dev.ssa.common.permission;

import dev.ssa.architect.model.GridPos;
import java.util.UUID;

@FunctionalInterface
public interface PermissionPort {
    boolean canModify(UUID owner, GridPos position);

    /**
     * Dimension-aware permission check for platform mutation paths.
     *
     * <p>The default preserves compatibility with lightweight/test adapters. Platform adapters that can resolve
     * dimensions should override this method and fail closed when the owner is not present in the target world.
     */
    default boolean canModify(UUID owner, String worldId, GridPos position) {
        return canModify(owner, position);
    }
}
