package dev.ssa.fabric.builder;

import dev.ssa.fabric.entity.BuilderEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class InteractionPositionResolver {
    private static final double REACH_DISTANCE_SQUARED = 4.5 * 4.5;

    public List<BlockPos> resolve(ServerLevel level, BuilderEntity builder, BlockPos target) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(target, "target");
        if (!level.isLoaded(target)) {
            return List.of();
        }

        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = target.relative(direction);
            if (isWalkable(level, candidate) && hasInteractionLine(level, builder, candidate, target)) {
                candidates.add(candidate.immutable());
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate ->
                builder.distanceToSqr(Vec3.atCenterOf(candidate))));
        return List.copyOf(candidates);
    }

    public boolean canInteractFromCurrentPosition(
            ServerLevel level,
            BuilderEntity builder,
            BlockPos target) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(target, "target");
        if (!level.isLoaded(target)) {
            return false;
        }
        Vec3 eye = builder.getEyePosition();
        Vec3 targetCenter = Vec3.atCenterOf(target);
        if (eye.distanceToSqr(targetCenter) > REACH_DISTANCE_SQUARED) {
            return false;
        }
        BlockHitResult hit = level.clip(new ClipContext(
                eye,
                targetCenter,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                builder));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    public String currentPositionDiagnostic(
            ServerLevel level,
            BuilderEntity builder,
            BlockPos target) {
        Vec3 eye = builder.getEyePosition();
        Vec3 targetCenter = Vec3.atCenterOf(target);
        BlockHitResult hit = level.clip(new ClipContext(
                eye,
                targetCenter,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                builder));
        return "eye=" + eye
                + ", distanceSquared=" + eye.distanceToSqr(targetCenter)
                + ", hit=" + hit.getType() + "@" + hit.getBlockPos();
    }

    private static boolean isWalkable(ServerLevel level, BlockPos feetPosition) {
        return level.isLoaded(feetPosition)
                && level.getBlockState(feetPosition).getCollisionShape(level, feetPosition).isEmpty()
                && level.getBlockState(feetPosition.above()).getCollisionShape(level, feetPosition.above()).isEmpty()
                && !level.getBlockState(feetPosition.below()).getCollisionShape(level, feetPosition.below()).isEmpty();
    }

    private static boolean hasInteractionLine(
            ServerLevel level,
            BuilderEntity builder,
            BlockPos feetPosition,
            BlockPos target) {
        Vec3 hypotheticalEye = Vec3.atCenterOf(feetPosition).add(0.0, 0.9, 0.0);
        Vec3 targetCenter = Vec3.atCenterOf(target);
        if (hypotheticalEye.distanceToSqr(targetCenter) > REACH_DISTANCE_SQUARED) {
            return false;
        }
        BlockHitResult hit = level.clip(new ClipContext(
                hypotheticalEye,
                targetCenter,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                builder));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }
}
