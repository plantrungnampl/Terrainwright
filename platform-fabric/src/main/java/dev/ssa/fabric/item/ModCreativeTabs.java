package dev.ssa.fabric.item;

import dev.ssa.fabric.TerrainwrightMod;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public final class ModCreativeTabs {
    public static final ResourceKey<CreativeModeTab> TERRAINWRIGHT = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB,
            Identifier.fromNamespaceAndPath(TerrainwrightMod.MOD_ID, "terrainwright"));
    public static final String TITLE_KEY = "creativeTab.smart_survival_architect.terrainwright";
    public static final CreativeModeTab TAB = Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB,
            TERRAINWRIGHT,
            FabricCreativeModeTab.builder()
                    .title(Component.translatable(TITLE_KEY))
                    .icon(() -> new ItemStack(ModItems.ARCHITECT_TABLE))
                    .displayItems((context, output) -> {
                        output.accept(ModItems.ARCHITECT_TABLE);
                        output.accept(ModItems.BUILDER_HUT);
                    })
                    .build());

    private ModCreativeTabs() {}

    public static void initialize() {
        // Loading this class performs the registration above.
    }
}
