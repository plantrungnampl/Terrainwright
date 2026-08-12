package dev.ssa.fabric.block;

import dev.ssa.fabric.SmartSurvivalArchitectMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;

public final class ModBlockIds {
    public static final ResourceKey<Block> ARCHITECT_TABLE = key("architect_table");
    public static final ResourceKey<Block> BUILDER_HUT = key("builder_hut");
    public static final ResourceKey<Block> SPIKE_MARKER = ResourceKey.create(
            Registries.BLOCK,
            Identifier.fromNamespaceAndPath(SmartSurvivalArchitectMod.MOD_ID, "spike_marker"));

    private ModBlockIds() {}

    private static ResourceKey<Block> key(String path) {
        return ResourceKey.create(
                Registries.BLOCK,
                Identifier.fromNamespaceAndPath(SmartSurvivalArchitectMod.MOD_ID, path));
    }
}
