package dev.ssa.fabric.block;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class ModBlocks {
    public static final Block SPIKE_MARKER = Registry.register(
            BuiltInRegistries.BLOCK,
            ModBlockIds.SPIKE_MARKER,
            new Block(BlockBehaviour.Properties.of().setId(ModBlockIds.SPIKE_MARKER)));

    private ModBlocks() {}

    public static void initialize() {
        // Loading this class performs the registrations above.
    }
}
