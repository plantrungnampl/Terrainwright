package dev.ssa.fabric.permission;

import dev.ssa.architect.model.GridPos;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class PermissionDimensionGameTest {
    @GameTest(maxTicks = 20, padding = 4)
    public void mutationPermissionFailsClosedWhenOwnerLeavesTargetDimension(GameTestHelper context) {
        ServerLevel targetLevel = context.getLevel();
        BlockPos target = context.absolutePos(new BlockPos(1, 1, 1));
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        player.setPos(Vec3.atCenterOf(target.above()));
        FabricPermissionAdapter permissions = FabricPermissionAdapter.forServer(targetLevel.getServer());
        GridPos targetPosition = new GridPos(target.getX(), target.getY(), target.getZ());
        String targetWorld = targetLevel.dimension().identifier().toString();

        context.assertTrue(
                permissions.canModify(player.getUUID(), targetWorld, targetPosition),
                "permission adapter rejected the owner while present in the target dimension");

        ServerLevel nether = targetLevel.getServer().getLevel(Level.NETHER);
        context.assertTrue(nether != null, "Nether level was unavailable");
        context.assertTrue(player.teleportTo(
                nether,
                player.getX(),
                player.getY(),
                player.getZ(),
                Set.<Relative>of(),
                player.getYRot(),
                player.getXRot(),
                false), "mock player did not enter the Nether");

        context.assertTrue(
                !permissions.canModify(player.getUUID(), targetWorld, targetPosition),
                "permission adapter evaluated target coordinates in the player's current dimension");
        context.succeed();
    }
}
