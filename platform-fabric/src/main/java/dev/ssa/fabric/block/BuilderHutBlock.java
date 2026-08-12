package dev.ssa.fabric.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
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
}
