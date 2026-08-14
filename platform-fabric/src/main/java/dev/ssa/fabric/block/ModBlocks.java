package dev.ssa.fabric.block;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class ModBlocks {
    public static final Block ARCHITECT_TABLE = Registry.register(
            BuiltInRegistries.BLOCK,
            ModBlockIds.ARCHITECT_TABLE,
            new ArchitectTableBlock(BlockBehaviour.Properties.of().setId(ModBlockIds.ARCHITECT_TABLE)));
    public static final Block BUILDER_HUT = Registry.register(
            BuiltInRegistries.BLOCK,
            ModBlockIds.BUILDER_HUT,
            new BuilderHutBlock(BlockBehaviour.Properties.of().setId(ModBlockIds.BUILDER_HUT)));

    private ModBlocks() {}

    public static void initialize() {
        // Loading this class performs the registrations above.
    }
}
