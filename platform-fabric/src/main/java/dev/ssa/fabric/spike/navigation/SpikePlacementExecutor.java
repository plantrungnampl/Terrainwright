package dev.ssa.fabric.spike.navigation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;

final class SpikePlacementExecutor {
    private SpikePlacementExecutor() {}

    static boolean placeCobblestone(
            ServerLevel level,
            SpikeBuilderEntity builder,
            BlockPos targetPos,
            SimpleContainer carriedItems) {
        ItemStack stack = carriedItems.getItem(0);
        if (!stack.is(Items.COBBLESTONE) || stack.isEmpty() || !level.getBlockState(targetPos).isAir()) {
            return false;
        }

        Vec3 targetCenter = Vec3.atCenterOf(targetPos);
        if (builder.getEyePosition().distanceToSqr(targetCenter) > 4.5 * 4.5) {
            return false;
        }

        BlockHitResult hit = level.clip(new ClipContext(
                builder.getEyePosition(),
                targetCenter,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                builder));
        if (hit.getType() != HitResult.Type.MISS && !hit.getBlockPos().equals(targetPos)) {
            return false;
        }

        if (!level.setBlock(targetPos, Blocks.COBBLESTONE.defaultBlockState(), 3)) {
            return false;
        }

        stack.shrink(1);
        carriedItems.setChanged();
        return true;
    }
}
