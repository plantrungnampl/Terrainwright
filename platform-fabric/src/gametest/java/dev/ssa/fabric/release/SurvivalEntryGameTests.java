package dev.ssa.fabric.release;

import dev.ssa.fabric.TerrainwrightMod;
import dev.ssa.fabric.block.BuilderHutBlockEntity;
import dev.ssa.fabric.block.BuilderHutService;
import dev.ssa.fabric.block.ModBlocks;
import dev.ssa.fabric.item.ModItems;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

public final class SurvivalEntryGameTests {
    @GameTest(maxTicks = 20)
    public void tableAndHutHaveSurvivalItemsAndRecipes(GameTestHelper context) {
        context.assertTrue(ModItems.ARCHITECT_TABLE instanceof BlockItem, "Architect Table item is not placeable");
        context.assertTrue(ModItems.BUILDER_HUT instanceof BlockItem, "Builder Hut item is not placeable");
        context.assertValueEqual(
                ((BlockItem) ModItems.ARCHITECT_TABLE).getBlock(),
                ModBlocks.ARCHITECT_TABLE,
                "Architect Table item block");
        context.assertValueEqual(
                ((BlockItem) ModItems.BUILDER_HUT).getBlock(),
                ModBlocks.BUILDER_HUT,
                "Builder Hut item block");
        context.assertTrue(hasRecipe(context, "architect_table"), "Architect Table recipe is missing");
        context.assertTrue(hasRecipe(context, "builder_hut"), "Builder Hut recipe is missing");
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void placedHutBecomesOwnedAndExplicitlyLinksVanillaChest(GameTestHelper context) {
        var player = context.makeMockServerPlayer(GameType.SURVIVAL);
        BlockPos relativeHut = new BlockPos(1, 1, 1);
        BlockPos hutPos = context.absolutePos(relativeHut);
        context.setBlock(relativeHut, ModBlocks.BUILDER_HUT);
        ModBlocks.BUILDER_HUT.setPlacedBy(
                context.getLevel(),
                hutPos,
                ModBlocks.BUILDER_HUT.defaultBlockState(),
                player,
                new ItemStack(ModItems.BUILDER_HUT));
        BuilderHutBlockEntity hut = context.getBlockEntity(relativeHut, BuilderHutBlockEntity.class);
        BlockPos chestPos = hutPos.offset(3, 0, 0);
        context.getLevel().setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        player.setPos(chestPos.getX() + 0.5, chestPos.getY(), chestPos.getZ() + 0.5);

        context.assertValueEqual(hut.references().ownerId(), Optional.of(player.getUUID()), "placed Hut owner");
        var durable = ServerBuildJobRepository.get(context.getLevel())
                .findHut(hut.references().hutId())
                .orElseThrow();
        context.assertValueEqual(durable.ownerId(), player.getUUID(), "durable Hut owner");
        var link = BuilderHutService.linkChest(
                context.getLevel(),
                hutPos,
                hut.references().hutId(),
                player.getUUID(),
                chestPos,
                (owner, position) -> true);
        context.assertTrue(link.linked(), "owned Hut rejected its explicit vanilla chest: " + link.failure());
        context.assertTrue(
                ServerBuildJobRepository.get(context.getLevel())
                        .findHut(hut.references().hutId())
                        .orElseThrow()
                        .containerBinding()
                        .isPresent(),
                "linked chest was not persisted");
        context.assertValueEqual(hut.references().bindingRevision(), Optional.of(1L), "Hut binding revision");
        ModBlocks.BUILDER_HUT.playerWillDestroy(
                context.getLevel(),
                hutPos,
                ModBlocks.BUILDER_HUT.defaultBlockState(),
                player);
        context.getLevel().setBlock(hutPos, Blocks.AIR.defaultBlockState(), 3);
        context.assertTrue(
                ServerBuildJobRepository.get(context.getLevel())
                        .findHut(hut.references().hutId())
                        .isEmpty(),
                "removed Hut remained durable and owned");
        context.succeed();
    }

    private static boolean hasRecipe(GameTestHelper context, String path) {
        ResourceKey<Recipe<?>> key = ResourceKey.create(
                Registries.RECIPE,
                Identifier.fromNamespaceAndPath(TerrainwrightMod.MOD_ID, path));
        return context.getLevel().getServer().getRecipeManager().byKey(key).isPresent();
    }
}
