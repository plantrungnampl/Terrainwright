package dev.ssa.common.permission;

import dev.ssa.architect.model.GridPos;
import java.util.UUID;

@FunctionalInterface
public interface PermissionPort {
    boolean canModify(UUID owner, GridPos position);
}
