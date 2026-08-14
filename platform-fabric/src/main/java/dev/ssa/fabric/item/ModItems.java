package dev.ssa.fabric.item;

import dev.ssa.fabric.block.ModBlocks;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

public final class ModItems {
    public static final Item ARCHITECT_TABLE = registerBlockItem(
            ModItemIds.ARCHITECT_TABLE, ModBlocks.ARCHITECT_TABLE);
    public static final Item BUILDER_HUT = registerBlockItem(
            ModItemIds.BUILDER_HUT, ModBlocks.BUILDER_HUT);

    private ModItems() {}

    public static void initialize() {
        // Loading this class performs the registrations above.
    }

    private static Item registerBlockItem(ResourceKey<Item> key, Block block) {
        return Registry.register(
                BuiltInRegistries.ITEM,
                key,
                new BlockItem(block, new Item.Properties().setId(key).useBlockDescriptionPrefix()));
    }
}
