package dev.ssa.fabric.spike.navigation;

import dev.ssa.fabric.SmartSurvivalArchitectMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class SpikeEntityTypes {
    private static final ResourceKey<EntityType<?>> BUILDER_KEY = ResourceKey.create(
            Registries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(SmartSurvivalArchitectMod.MOD_ID, "spike_builder"));

    public static final EntityType<SpikeBuilderEntity> BUILDER = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            BUILDER_KEY,
            EntityType.Builder.of(SpikeBuilderEntity::new, MobCategory.MISC)
                    .sized(0.6f, 1.8f)
                    .noSave()
                    .noLootTable()
                    .build(BUILDER_KEY));

    private SpikeEntityTypes() {}

    public static void initialize() {
        FabricDefaultAttributeRegistry.register(BUILDER, SpikeBuilderEntity.createAttributes());
    }
}
