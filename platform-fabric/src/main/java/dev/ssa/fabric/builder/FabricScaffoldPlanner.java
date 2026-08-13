package dev.ssa.fabric.builder;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.construction.scaffold.ScaffoldPlan;
import dev.ssa.fabric.entity.BuilderEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** Finds a small temporary scaffold column; execution remains owned by OperationIntent services. */
public final class FabricScaffoldPlanner {
    private static final BlockStateSpec SCAFFOLD = new BlockStateSpec(
            NamespacedId.parse("minecraft:scaffolding"),
            Map.of("bottom", "false", "distance", "0", "waterlogged", "false"));

    public Optional<ScaffoldPlan> plan(ServerLevel level, BuilderEntity builder, BlockPos target) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(target, "target");
        Direction towardBuilder = horizontalDirection(target, builder.blockPosition());
        BlockPos top = target.relative(towardBuilder).below();
        if (!level.isLoaded(top) || !level.getBlockState(top).isAir()) {
            return Optional.empty();
        }

        BlockPos bottom = top;
        for (int depth = 0; depth <= ScaffoldPlan.MAX_HEIGHT; depth++) {
            if (!level.isLoaded(bottom)
                    || !level.getBlockState(bottom).isAir()
                    || level.getBlockEntity(bottom) != null) {
                return Optional.empty();
            }
            if (!level.getBlockState(bottom.below()).getCollisionShape(level, bottom.below()).isEmpty()) {
                return column(grid(bottom), grid(top));
            }
            bottom = bottom.below();
        }
        return Optional.empty();
    }

    static Optional<ScaffoldPlan> column(GridPos bottom, GridPos top) {
        Objects.requireNonNull(bottom, "bottom");
        Objects.requireNonNull(top, "top");
        if (bottom.x() != top.x() || bottom.z() != top.z() || top.y() < bottom.y()) {
            return Optional.empty();
        }
        int height = top.y() - bottom.y();
        int count = height + 1;
        if (height > ScaffoldPlan.MAX_HEIGHT || count > ScaffoldPlan.MAX_PLACEMENTS) {
            return Optional.empty();
        }
        List<ScaffoldPlan.Placement> placements = new ArrayList<>(count);
        for (int y = bottom.y(); y <= top.y(); y++) {
            placements.add(new ScaffoldPlan.Placement(new GridPos(bottom.x(), y, bottom.z()), SCAFFOLD));
        }
        return Optional.of(new ScaffoldPlan(placements));
    }

    private static Direction horizontalDirection(BlockPos target, BlockPos builder) {
        int dx = builder.getX() - target.getX();
        int dz = builder.getZ() - target.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx < 0 ? Direction.WEST : Direction.EAST;
        }
        return dz < 0 ? Direction.NORTH : Direction.SOUTH;
    }

    private static GridPos grid(BlockPos position) {
        return new GridPos(position.getX(), position.getY(), position.getZ());
    }
}
