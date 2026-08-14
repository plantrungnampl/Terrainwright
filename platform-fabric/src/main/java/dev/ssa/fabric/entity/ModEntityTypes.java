package dev.ssa.fabric.entity;

import dev.ssa.fabric.TerrainwrightMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntityTypes {
    private static final ResourceKey<EntityType<?>> BUILDER_KEY = ResourceKey.create(
            Registries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(TerrainwrightMod.MOD_ID, "builder"));

    public static final EntityType<BuilderEntity> BUILDER = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            BUILDER_KEY,
            EntityType.Builder.of(BuilderEntity::new, MobCategory.MISC)
                    .sized(0.6f, 1.8f)
                    .noLootTable()
                    .build(BUILDER_KEY));

    private ModEntityTypes() {
    }

    public static void initialize() {
        FabricDefaultAttributeRegistry.register(BUILDER, BuilderEntity.createAttributes());
    }
}
