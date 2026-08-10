package dev.ssa.fabric.spike.navigation;

import java.util.Arrays;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.pathfinder.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BuilderNavigationGameTests {
    private static final Logger LOGGER = LoggerFactory.getLogger("smart_survival_architect_s2");
    private static final BlockPos CHEST_POS = new BlockPos(2, 1, 2);
    private static final BlockPos TARGET_POS = new BlockPos(8, 1, 2);
    private static final BlockPos UPPER_TARGET_POS = new BlockPos(8, 3, 2);
    private static final BlockPos SCAFFOLD_TARGET_POS = new BlockPos(8, 4, 2);
    private static final BlockPos STUCK_TARGET_POS = new BlockPos(14, 1, 2);

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
            logFixture("flat", "SUCCESS", builder);
        });
    }

    @GameTest(maxTicks = 400, padding = 20)
    public void oneBlockStepSucceeds(GameTestHelper context) {
        createFlatFloor(context, -2, 10, -2, 6);
        context.setBlock(CHEST_POS, Blocks.CHEST);
        context.getBlockEntity(CHEST_POS, ChestBlockEntity.class)
                .setItem(0, new ItemStack(Items.COBBLESTONE));
        for (int z = 0; z <= 4; z++) {
            context.setBlock(5, 1, z, Blocks.STONE);
        }

        SpikeBuilderEntity builder = context.spawn(SpikeEntityTypes.BUILDER, 0, 1, 2);
        builder.beginScenario(context.absolutePos(CHEST_POS), context.absolutePos(TARGET_POS), false);

        context.succeedWhen(() -> {
            assertSuccessfulPlacement(context, builder, TARGET_POS);
            logFixture("step", "SUCCESS", builder);
        });
    }

    @GameTest(maxTicks = 400, padding = 20)
    public void doorwaySucceeds(GameTestHelper context) {
        createFlatFloor(context, -2, 10, -2, 6);
        context.setBlock(CHEST_POS, Blocks.CHEST);
        context.getBlockEntity(CHEST_POS, ChestBlockEntity.class)
                .setItem(0, new ItemStack(Items.COBBLESTONE));
        for (int z = 0; z <= 4; z++) {
            if (z != 2) {
                context.setBlock(5, 1, z, Blocks.STONE);
                context.setBlock(5, 2, z, Blocks.STONE);
            }
        }

        SpikeBuilderEntity builder = context.spawn(SpikeEntityTypes.BUILDER, 0, 1, 2);
        builder.beginScenario(context.absolutePos(CHEST_POS), context.absolutePos(TARGET_POS), false);

        context.succeedWhen(() -> {
            assertSuccessfulPlacement(context, builder, TARGET_POS);
            logFixture("doorway", "SUCCESS", builder);
        });
    }

    @GameTest(maxTicks = 400, padding = 20)
    public void fencedObstructionBlocksWithoutMutation(GameTestHelper context) {
        createFlatFloor(context, -2, 10, -2, 6);
        context.setBlock(CHEST_POS, Blocks.CHEST);
        context.getBlockEntity(CHEST_POS, ChestBlockEntity.class)
                .setItem(0, new ItemStack(Items.COBBLESTONE));
        context.setBlock(TARGET_POS.north(), Blocks.OAK_FENCE);
        context.setBlock(TARGET_POS.south(), Blocks.OAK_FENCE);
        context.setBlock(TARGET_POS.east(), Blocks.OAK_FENCE);
        context.setBlock(TARGET_POS.west(), Blocks.OAK_FENCE);

        SpikeBuilderEntity builder = context.spawn(SpikeEntityTypes.BUILDER, 0, 1, 2);
        builder.beginScenario(context.absolutePos(CHEST_POS), context.absolutePos(TARGET_POS), false);

        context.succeedWhen(() -> {
            context.assertValueEqual(builder.spikeState(), SpikeBuilderEntity.State.BLOCKED, "Builder state");
            context.assertBlockPresent(Blocks.AIR, TARGET_POS);
            context.assertValueEqual(builder.carriedCobblestone(), 1, "Carried cobblestone");
            context.assertTrue(builder.pathAttempts() <= 3, "Path attempts exceeded the retry bound");
            logFixture("fence_obstruction", "BLOCKED", builder);
        });
    }

    @GameTest(maxTicks = 400, padding = 20)
    public void upperFloorSucceeds(GameTestHelper context) {
        createFlatFloor(context, -2, 10, -2, 6);
        context.setBlock(CHEST_POS, Blocks.CHEST);
        context.getBlockEntity(CHEST_POS, ChestBlockEntity.class)
                .setItem(0, new ItemStack(Items.COBBLESTONE));
        for (int z = 1; z <= 3; z++) {
            context.setBlock(4, 1, z, Blocks.STONE);
            context.setBlock(5, 2, z, Blocks.STONE);
        }
        for (int x = 6; x <= 9; x++) {
            for (int z = 1; z <= 3; z++) {
                context.setBlock(x, 2, z, Blocks.STONE);
                context.setBlock(x, 3, z, Blocks.AIR);
                context.setBlock(x, 4, z, Blocks.AIR);
            }
        }

        SpikeBuilderEntity builder = context.spawn(SpikeEntityTypes.BUILDER, 0, 1, 2);
        builder.beginScenario(context.absolutePos(CHEST_POS), context.absolutePos(UPPER_TARGET_POS), false);

        context.succeedWhen(() -> {
            assertSuccessfulPlacement(context, builder, UPPER_TARGET_POS);
            logFixture("upper_floor", "SUCCESS", builder);
        });
    }

    @GameTest(maxTicks = 400, padding = 20)
    public void shortScaffoldRampSucceeds(GameTestHelper context) {
        createFlatFloor(context, -2, 10, -2, 6);
        context.setBlock(CHEST_POS, Blocks.CHEST);
        context.getBlockEntity(CHEST_POS, ChestBlockEntity.class)
                .setItem(0, new ItemStack(Items.COBBLESTONE));
        for (int x = 6; x <= 9; x++) {
            for (int z = 1; z <= 3; z++) {
                context.setBlock(x, 3, z, Blocks.STONE);
                context.setBlock(x, 4, z, Blocks.AIR);
                context.setBlock(x, 5, z, Blocks.AIR);
            }
        }

        SpikeBuilderEntity builder = context.spawn(SpikeEntityTypes.BUILDER, 0, 1, 2);
        builder.beginScenario(context.absolutePos(CHEST_POS), context.absolutePos(SCAFFOLD_TARGET_POS), true);

        context.succeedWhen(() -> {
            assertSuccessfulPlacement(context, builder, SCAFFOLD_TARGET_POS);
            context.assertValueEqual(builder.scaffoldBlocks(), 3, "Temporary scaffold block count");
            logFixture("scaffold_required", "SUCCESS", builder);
        });
    }

    @GameTest(maxTicks = 400, padding = 20)
    public void impossibleTargetStaysBlocked(GameTestHelper context) {
        createFlatFloor(context, -2, 10, -2, 6);
        context.setBlock(CHEST_POS, Blocks.CHEST);
        context.getBlockEntity(CHEST_POS, ChestBlockEntity.class)
                .setItem(0, new ItemStack(Items.COBBLESTONE));
        context.setBlock(TARGET_POS.north(), Blocks.BEDROCK);
        context.setBlock(TARGET_POS.south(), Blocks.BEDROCK);
        context.setBlock(TARGET_POS.east(), Blocks.BEDROCK);
        context.setBlock(TARGET_POS.west(), Blocks.BEDROCK);
        context.setBlock(TARGET_POS.above(), Blocks.BEDROCK);

        SpikeBuilderEntity builder = context.spawn(SpikeEntityTypes.BUILDER, 0, 1, 2);
        builder.beginScenario(context.absolutePos(CHEST_POS), context.absolutePos(TARGET_POS), false);
        int[] attemptsAtBlocked = new int[1];

        context.startSequence()
                .thenWaitUntil(() -> {
                    context.assertValueEqual(
                            builder.spikeState(), SpikeBuilderEntity.State.BLOCKED, "Builder state");
                    attemptsAtBlocked[0] = builder.pathAttempts();
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    context.assertValueEqual(
                            builder.spikeState(), SpikeBuilderEntity.State.BLOCKED, "Stable blocked state");
                    context.assertValueEqual(builder.pathAttempts(), attemptsAtBlocked[0], "Stable path attempts");
                    context.assertBlockPresent(Blocks.AIR, TARGET_POS);
                    context.assertValueEqual(builder.carriedCobblestone(), 1, "Carried cobblestone");
                    logFixture("impossible_target", "BLOCKED_STABLE", builder);
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 100, padding = 1)
    public void unloadedDestinationSuspendsWithoutPathSpam(GameTestHelper context) {
        createFlatFloor(context, -2, 4, -2, 4);
        context.setBlock(CHEST_POS, Blocks.CHEST);
        ChestBlockEntity chest = context.getBlockEntity(CHEST_POS, ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.COBBLESTONE));
        BlockPos unloadedTarget = context.absolutePos(new BlockPos(512, 1, 2));

        SpikeBuilderEntity builder = context.spawn(SpikeEntityTypes.BUILDER, 0, 1, 2);
        builder.beginScenario(context.absolutePos(CHEST_POS), unloadedTarget, false);

        context.succeedWhen(() -> {
            context.assertValueEqual(
                    builder.spikeState(),
                    SpikeBuilderEntity.State.SUSPENDED_CHUNK_UNLOADED,
                    "Builder state");
            context.assertValueEqual(builder.pathAttempts(), 0, "Path attempts while chunk was unloaded");
            context.assertValueEqual(chest.getItem(0).getCount(), 1, "Chest item count");
            logFixture("chunk_unload", "SUSPENDED", builder);
        });
    }

    @GameTest(maxTicks = 40, padding = 1)
    public void placementExecutorRejectsUnloadedTarget(GameTestHelper context) {
        BlockPos unloadedTarget = context.absolutePos(new BlockPos(512, 1, 2));
        SpikeBuilderEntity builder = context.spawn(SpikeEntityTypes.BUILDER, 0, 1, 2);
        builder.setPos(unloadedTarget.getX() + 1.5, unloadedTarget.getY(), unloadedTarget.getZ() + 0.5);
        SimpleContainer carriedItems = new SimpleContainer(new ItemStack(Items.COBBLESTONE));

        context.assertTrue(!context.getLevel().isLoaded(unloadedTarget), "Target chunk unexpectedly loaded");
        boolean placed = SpikePlacementExecutor.placeCobblestone(
                context.getLevel(), builder, unloadedTarget, carriedItems);

        context.assertTrue(!placed, "Executor accepted an unloaded target");
        context.assertTrue(!context.getLevel().isLoaded(unloadedTarget), "Executor loaded the target chunk");
        context.assertValueEqual(carriedItems.getItem(0).getCount(), 1, "Carried item count");
        context.succeed();
    }

    @GameTest(maxTicks = 400, padding = 20)
    public void stuckTimeoutRetriesThenBlocks(GameTestHelper context) {
        createFlatFloor(context, -2, 16, -2, 6);
        context.setBlock(CHEST_POS, Blocks.CHEST);
        context.getBlockEntity(CHEST_POS, ChestBlockEntity.class)
                .setItem(0, new ItemStack(Items.COBBLESTONE));

        SpikeBuilderEntity builder = context.spawn(SpikeEntityTypes.BUILDER, 0, 1, 2);
        builder.beginScenario(context.absolutePos(CHEST_POS), context.absolutePos(STUCK_TARGET_POS), false);
        context.startSequence()
                .thenWaitUntil(() -> {
                    context.assertValueEqual(
                            builder.spikeState(), SpikeBuilderEntity.State.NAVIGATE_SITE, "Builder state");
                    context.assertTrue(builder.getNavigation().isInProgress(), "Navigation did not start");
                })
                .thenExecute(() -> builder.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0))
                .thenWaitUntil(() -> context.assertValueEqual(
                        builder.stuckTimeouts(), 1, "Observed stuck timeout count"))
                .thenExecute(() -> {
                    builder.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.30);
                    for (int z = -2; z <= 6; z++) {
                        context.setBlock(7, 1, z, Blocks.BEDROCK);
                        context.setBlock(7, 2, z, Blocks.BEDROCK);
                    }
                })
                .thenWaitUntil(() -> context.assertValueEqual(
                        builder.spikeState(), SpikeBuilderEntity.State.BLOCKED, "Builder state"))
                .thenExecute(() -> {
                    context.assertValueEqual(builder.stuckTimeouts(), 1, "Stuck timeout count");
                    context.assertTrue(builder.pathAttempts() >= 2, "Stuck recovery did not retry navigation");
                    context.assertTrue(builder.pathAttempts() <= 4, "Stuck recovery exceeded its retry bound");
                    context.assertBlockPresent(Blocks.AIR, STUCK_TARGET_POS);
                    logFixture("stuck_timeout", "BLOCKED", builder);
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 100, padding = 20)
    public void pathAttemptProfile(GameTestHelper context) {
        createFlatFloor(context, -2, 14, -2, 6);
        SpikeBuilderEntity builder = context.spawn(SpikeEntityTypes.BUILDER, 0, 1, 2);
        BlockPos target = context.absolutePos(new BlockPos(12, 1, 2));

        context.runAfterDelay(10, () -> {
            for (int warmup = 0; warmup < 10; warmup++) {
                builder.getNavigation().createPath(target, 0);
            }

            long[] samplesMicros = new long[100];
            for (int sample = 0; sample < samplesMicros.length; sample++) {
                long started = System.nanoTime();
                Path path = builder.getNavigation().createPath(target, 0);
                samplesMicros[sample] = (System.nanoTime() - started) / 1_000;
                context.assertTrue(path != null && path.canReach(), "Profile route was not reachable");
            }
            Arrays.sort(samplesMicros);
            long p50 = samplesMicros[49];
            long p95 = samplesMicros[94];
            long maximum = samplesMicros[99];
            LOGGER.info(
                    "SSA_S2_PROFILE count=100 p50_us={} p95_us={} max_us={}",
                    p50,
                    p95,
                    maximum);
            context.assertTrue(p95 < 10_000, "Path-attempt p95 exceeded 10,000 us: " + p95);
            context.assertTrue(maximum < 50_000, "Path-attempt max exceeded 50,000 us: " + maximum);
            context.succeed();
        });
    }

    private static void assertSuccessfulPlacement(
            GameTestHelper context,
            SpikeBuilderEntity builder,
            BlockPos targetPos) {
        context.assertTrue(
                builder.spikeState() != SpikeBuilderEntity.State.BLOCKED,
                "Builder blocked: " + builder.failureReason());
        context.assertValueEqual(builder.spikeState(), SpikeBuilderEntity.State.SUCCESS, "Builder state");
        context.assertBlockPresent(Blocks.COBBLESTONE, targetPos);
        context.assertTrue(builder.maxTickDisplacement() <= 1.5, "Builder movement indicates a teleport");
    }

    private static void logFixture(String name, String outcome, SpikeBuilderEntity builder) {
        LOGGER.info(
                "SSA_S2_FIXTURE name={} outcome={} state={} attempts={} scaffold={} stuck_timeouts={} max_delta={}",
                name,
                outcome,
                builder.spikeState(),
                builder.pathAttempts(),
                builder.scaffoldBlocks(),
                builder.stuckTimeouts(),
                builder.maxTickDisplacement());
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
