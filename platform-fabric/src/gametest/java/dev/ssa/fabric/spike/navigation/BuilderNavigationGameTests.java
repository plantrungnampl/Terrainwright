package dev.ssa.fabric.spike.navigation;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

public final class BuilderNavigationGameTests {
    private static final BlockPos CHEST_POS = new BlockPos(2, 1, 2);
    private static final BlockPos TARGET_POS = new BlockPos(8, 1, 2);

    @GameTest(maxTicks = 40)
    public void disposableBuilderSpawns(GameTestHelper context) {
        SpikeBuilderEntity builder = context.spawn(SpikeEntityTypes.BUILDER, 0, 1, 0);

        context.assertTrue(builder.isAlive(), "Disposable Builder did not spawn alive");
        context.succeed();
    }

    @GameTest(maxTicks = 400, padding = 20)
    public void flatChestToSitePlacesOneBlock(GameTestHelper context) {
        createFlatFloor(context, -2, 10, -2, 6);
        context.setBlock(CHEST_POS, Blocks.CHEST);
        ChestBlockEntity chest = context.getBlockEntity(CHEST_POS, ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.COBBLESTONE));

        SpikeBuilderEntity builder = context.spawn(SpikeEntityTypes.BUILDER, 0, 1, 2);
        builder.beginScenario(context.absolutePos(CHEST_POS), context.absolutePos(TARGET_POS), false);

        context.succeedWhen(() -> {
            context.assertTrue(
                    builder.spikeState() != SpikeBuilderEntity.State.BLOCKED,
                    "Builder blocked: " + builder.failureReason());
            context.assertValueEqual(builder.spikeState(), SpikeBuilderEntity.State.SUCCESS, "Builder state");
            context.assertBlockPresent(Blocks.COBBLESTONE, TARGET_POS);
            context.assertTrue(chest.getItem(0).isEmpty(), "Chest still contains the test item");
            context.assertValueEqual(builder.carriedCobblestone(), 0, "Carried cobblestone");
            context.assertTrue(builder.pathAttempts() <= 3, "Path attempts exceeded the retry bound");
            context.assertTrue(builder.maxTickDisplacement() <= 1.5, "Builder movement indicates a teleport");
        });
    }

    private static void createFlatFloor(
            GameTestHelper context,
            int minimumX,
            int maximumX,
            int minimumZ,
            int maximumZ) {
        for (int x = minimumX; x <= maximumX; x++) {
            for (int z = minimumZ; z <= maximumZ; z++) {
                context.setBlock(x, 0, z, Blocks.STONE);
                context.setBlock(x, 1, z, Blocks.AIR);
                context.setBlock(x, 2, z, Blocks.AIR);
            }
        }
    }
}
