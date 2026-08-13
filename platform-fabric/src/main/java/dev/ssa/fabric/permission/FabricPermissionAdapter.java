package dev.ssa.fabric.permission;

import dev.ssa.architect.model.GridPos;
import dev.ssa.common.permission.PermissionPort;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class FabricPermissionAdapter implements PermissionPort {
    private final MinecraftServer server;

    public FabricPermissionAdapter(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public boolean canModify(UUID owner, GridPos position) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(position, "position");
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player == null) {
            return false;
        }
        BlockPos blockPosition = new BlockPos(position.x(), position.y(), position.z());
        return player.level().isLoaded(blockPosition)
                && player.level().mayInteract(player, blockPosition);
    }
}
