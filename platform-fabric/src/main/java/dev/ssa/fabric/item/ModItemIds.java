package dev.ssa.fabric.item;

import dev.ssa.fabric.SmartSurvivalArchitectMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

public final class ModItemIds {
    public static final ResourceKey<Item> ARCHITECT_TABLE = key("architect_table");
    public static final ResourceKey<Item> BUILDER_HUT = key("builder_hut");

    private ModItemIds() {}

    private static ResourceKey<Item> key(String path) {
        return ResourceKey.create(
                Registries.ITEM,
                Identifier.fromNamespaceAndPath(SmartSurvivalArchitectMod.MOD_ID, path));
    }
}
