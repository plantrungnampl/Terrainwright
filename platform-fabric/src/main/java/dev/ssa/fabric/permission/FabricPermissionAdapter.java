package dev.ssa.fabric.permission;

import dev.ssa.architect.model.GridPos;
import dev.ssa.common.permission.PermissionPort;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class FabricPermissionAdapter implements PermissionPort {
    private static final WeakHashMap<MinecraftServer, FabricPermissionAdapter> INSTANCES = new WeakHashMap<>();

    private final MinecraftServer server;

    public FabricPermissionAdapter(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    /** Returns the shared platform permission boundary used by every V1 mutation path on this server. */
    public static synchronized FabricPermissionAdapter forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return INSTANCES.computeIfAbsent(server, FabricPermissionAdapter::new);
    }

    public static synchronized void releaseServer(MinecraftServer server) {
        INSTANCES.remove(Objects.requireNonNull(server, "server"));
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
