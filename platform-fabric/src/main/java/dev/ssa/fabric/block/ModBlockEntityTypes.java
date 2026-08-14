package dev.ssa.fabric.block;

import dev.ssa.fabric.TerrainwrightMod;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.core.Registry;

public final class ModBlockEntityTypes {
    public static final ResourceKey<BlockEntityType<?>> BUILDER_HUT_KEY = ResourceKey.create(
            Registries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(TerrainwrightMod.MOD_ID, "builder_hut"));
    public static final BlockEntityType<BuilderHutBlockEntity> BUILDER_HUT = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            BUILDER_HUT_KEY,
            new BlockEntityType<>(BuilderHutBlockEntity::new, Set.of(ModBlocks.BUILDER_HUT)));

    private ModBlockEntityTypes() {}

    public static void initialize() {
        // Loading this class performs the registration above.
    }
}
