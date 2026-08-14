package dev.ssa.fabric.block;

import com.mojang.serialization.MapCodec;
import dev.ssa.fabric.builder.BuilderRuntimeService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class BuilderHutBlock extends BaseEntityBlock {
    public static final MapCodec<BuilderHutBlock> CODEC = simpleCodec(BuilderHutBlock::new);

    public BuilderHutBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return new BuilderHutBlockEntity(position, state);
    }

    @Override
    public void setPlacedBy(
            Level level,
            BlockPos position,
            BlockState state,
            LivingEntity placer,
            ItemStack stack) {
        super.setPlacedBy(level, position, state, placer, stack);
        if (level instanceof ServerLevel serverLevel && placer instanceof Player player) {
            BuilderHutService.claimPlacedHut(serverLevel, position, player.getUUID());
        }
    }

    @Override
    public BlockState playerWillDestroy(
            Level level,
            BlockPos position,
            BlockState state,
            Player player) {
        if (level instanceof ServerLevel serverLevel
                && level.getBlockEntity(position) instanceof BuilderHutBlockEntity hut
                && hut.references().ownerId().isPresent()) {
            BuilderRuntimeService.observeHutLoss(serverLevel, hut.references().hutId());
        }
        return super.playerWillDestroy(level, position, state, player);
    }
}
