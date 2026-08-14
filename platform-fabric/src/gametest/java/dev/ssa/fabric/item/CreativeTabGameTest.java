package dev.ssa.fabric.item;

import dev.ssa.fabric.TerrainwrightMod;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class CreativeTabGameTest {
    @GameTest(maxTicks = 20)
    public void registeredTerrainwrightTabExposesItemsInDisplayAndSearch(GameTestHelper context) {
        CreativeModeTab tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValueOrThrow(ModCreativeTabs.TERRAINWRIGHT);
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(
                FeatureFlags.DEFAULT_FLAGS,
                false,
                context.getLevel().registryAccess()));

        List<Item> expected = List.of(ModItems.ARCHITECT_TABLE, ModItems.BUILDER_HUT);
        List<Item> display = tab.getDisplayItems().stream().map(ItemStack::getItem).toList();
        List<Item> search = tab.getSearchTabDisplayItems().stream().map(ItemStack::getItem).toList();

        context.assertValueEqual(tab, ModCreativeTabs.TAB, "Terrainwright Creative tab registration");
        context.assertValueEqual(
                BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab).toString(),
                "smart_survival_architect:terrainwright",
                "Terrainwright Creative tab registry key");
        context.assertValueEqual(tab.getIconItem().getItem(), ModItems.ARCHITECT_TABLE, "Terrainwright Creative tab icon");
        context.assertValueEqual(
                tab.getDisplayName(),
                Component.translatable("creativeTab.smart_survival_architect.terrainwright"),
                "Terrainwright Creative tab title");
        context.assertValueEqual(display, expected, "Terrainwright Creative tab item order");
        context.assertValueEqual(search, expected, "Terrainwright Creative search items");
        context.succeed();
    }
}
