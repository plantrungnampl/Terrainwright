package dev.ssa.fabric.builder;

import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.construction.operation.DropPolicy;
import dev.ssa.construction.operation.InventoryDelta;
import dev.ssa.construction.operation.OperationIntent;
import dev.ssa.construction.operation.OperationKind;
import dev.ssa.construction.operation.WorldDelta;
import dev.ssa.construction.plan.TaskGraph;
import dev.ssa.construction.schedule.WorkZone;
import dev.ssa.construction.task.BuildTask;
import dev.ssa.construction.task.TaskOperation;
import dev.ssa.fabric.construction.FabricMutationExecutor;
import dev.ssa.fabric.construction.MaterialTransferService;
import dev.ssa.fabric.construction.MinecraftSnapshotAdapter;
import dev.ssa.fabric.construction.OperationBoundaryListener;
import dev.ssa.fabric.entity.BuilderEntity;
import dev.ssa.fabric.entity.ModEntityTypes;
import dev.ssa.fabric.link.BuilderChestLinkService;
import dev.ssa.fabric.link.ContainerBinding;
import dev.ssa.fabric.persistence.OperationIntentStore;
import dev.ssa.fabric.persistence.PersistenceExecutor;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

public final class BuilderMaterialLoopGameTest {
    @GameTest(maxTicks = 800, padding = 16)
    public void builderFetchesExactBatchAndBuildsOneWall(GameTestHelper context) {
        Fixture fixture = start(context, layout(context), 3, "complete-wall");

        context.runAtTickTime(400, () -> failWithProgress(context, fixture));

        finishWhenComplete(context, fixture, () -> {
            assertCompletedWall(context, fixture);
            context.assertTrue(
                    !fixture.controller().stateHistory().contains(BuilderStateMachine.State.WAIT_MATERIAL),
                    "Complete material bundle entered WAIT_MATERIAL");
        });
    }

    @GameTest(maxTicks = 40, padding = 20)
    public void navigationSuspendsBeforeEnteringANonTickingChunk(GameTestHelper context) {
        ServerLevel level = context.getLevel();
        BlockPos[] boundary = findEntityTickingBoundary(context);
        BlockPos start = boundary[0];
        BlockPos target = boundary[1];
        for (int x = Math.min(start.getX(), target.getX()) - 2;
                x <= Math.max(start.getX(), target.getX()) + 2;
                x++) {
            context.setBlock(x, 0, start.getZ(), Blocks.STONE);
            context.setBlock(x, 1, start.getZ(), Blocks.AIR);
            context.setBlock(x, 2, start.getZ(), Blocks.AIR);
        }
        BuilderEntity builder = context.spawn(ModEntityTypes.BUILDER, start);
        FabricNavigationAdapter navigation = new FabricNavigationAdapter(
                builder,
                new InteractionPositionResolver());
        navigation.begin(level, context.absolutePos(target));

        context.runAtTickTime(1, () -> {
            context.assertValueEqual(
                    navigation.tick(level),
                    FabricNavigationAdapter.Status.SUSPENDED_CHUNK_UNLOADED,
                    "navigation state at an entity-ticking boundary");
            context.succeed();
        });
    }

    @GameTest(maxTicks = 200, padding = 20)
    public void controllerStaysSuspendedWhileTheTaskChunkDoesNotTickEntities(GameTestHelper context) {
        BlockPos[] boundary = findEntityTickingBoundary(context);
        BlockPos builderStart = boundary[0];
        BlockPos wallOrigin = boundary[1];
        BlockPos chestPosition = findTickingNeighbor(context, builderStart, wallOrigin);
        for (BlockPos position : List.of(builderStart, wallOrigin, chestPosition)) {
            context.setBlock(position.below(), Blocks.STONE);
            context.setBlock(position, Blocks.AIR);
            context.setBlock(position.above(), Blocks.AIR);
        }
        Layout layout = new Layout(chestPosition, builderStart, wallOrigin, 0, 0);
        Fixture fixture = start(context, layout, 1, "suspended-task", oneBlockGraph(), true, false);
        AtomicReference<Long> suspendedRevision = new AtomicReference<>();
        AtomicReference<Long> suspendedAt = new AtomicReference<>();

        context.onEachTick(() -> {
            if (fixture.controller().state() == BuilderStateMachine.State.SUSPENDED_CHUNK_UNLOADED) {
                if (suspendedRevision.compareAndSet(
                        null,
                        fixture.repository().findJob(fixture.jobId()).orElseThrow().revision())) {
                    suspendedAt.set(context.getTick());
                }
                if (context.getTick() - suspendedAt.get() >= 40) {
                    BuildJob job = fixture.repository().findJob(fixture.jobId()).orElseThrow();
                    context.assertValueEqual(job.revision(), suspendedRevision.get(),
                            "suspended BuildJob revision");
                    fixture.builder().discard();
                    fixture.persistence().close();
                    context.succeed();
                }
            } else if (suspendedRevision.get() != null) {
                fixture.builder().discard();
                fixture.persistence().close();
                context.fail("Controller left suspension while task chunk remained non-ticking: "
                        + fixture.controller().state());
            }
        });
    }

    @GameTest(maxTicks = 400, padding = 16)
    public void allAfterPlacementRecoveryDoesNotRequireTheLinkedChest(GameTestHelper context) {
        Layout layout = layout(context);
        TaskGraph graph = oneBlockGraph();
        Fixture fixture = start(context, layout, 0, "recover-idle", graph, false);
        context.setBlock(layout.chest(), Blocks.AIR);
        BlockPos absoluteTarget = context.absolutePos(layout.wallOrigin());
        context.getLevel().setBlockAndUpdate(absoluteTarget, Blocks.OAK_PLANKS.defaultBlockState());
        MinecraftSnapshotAdapter snapshots = new MinecraftSnapshotAdapter(context.getLevel().registryAccess());
        ItemStack carriedBefore = new ItemStack(Items.OAK_PLANKS);
        OperationIntent intent = OperationIntent.prepared(
                "recover-idle-operation",
                fixture.jobId(),
                Optional.of("wall-0"),
                Optional.empty(),
                3,
                OperationKind.WORLD_MUTATION,
                List.of(
                        new InventoryDelta(
                                "builder:" + fixture.builder().getUUID(),
                                1,
                                0,
                                snapshots.snapshot(carriedBefore),
                                snapshots.snapshot(ItemStack.EMPTY)),
                        new WorldDelta(
                                context.getLevel().dimension().identifier().toString(),
                                absoluteTarget.getX(),
                                absoluteTarget.getY(),
                                absoluteTarget.getZ(),
                                snapshots.snapshot(Blocks.AIR.defaultBlockState()),
                                snapshots.snapshot(Blocks.OAK_PLANKS.defaultBlockState()),
                                DropPolicy.NOT_APPLICABLE)));

        fixture.store().prepare(intent).thenRunAsync(
                () -> fixture.controller().assign(fixture.workOrder()),
                context.getLevel().getServer()::execute);
        finishWhenComplete(context, fixture, () -> {
            BuildJob recovered = fixture.repository().findJob(fixture.jobId()).orElseThrow();
            context.assertValueEqual(recovered.completedTaskIds(), java.util.Set.of("wall-0"),
                    "recovered completed tasks");
            context.assertValueEqual(recovered.blockJournal().size(), 1, "recovered journal size");
            context.assertValueEqual(
                    recovered.blockJournal().getFirst().operationId(),
                    intent.operationId(),
                    "recovered journal operation");
            context.assertValueEqual(recovered.revision(), 5L, "recovered BuildJob revision");
            context.assertBlockPresent(Blocks.OAK_PLANKS, layout.wallOrigin());
            context.assertValueEqual(fixture.builder().carriedItemCount(Items.OAK_PLANKS), 0,
                    "recovered carried remainder");
            context.assertValueEqual(fixture.boundaries().durablePrepareCount(), 0,
                    "recovery scheduled a second mutation");
        });
    }

    @GameTest(maxTicks = 900, padding = 16)
    public void missingMaterialWaitsAndAutomaticallyResumes(GameTestHelper context) {
        Fixture fixture = start(context, layout(context), 2, "wait-wall");
        AtomicBoolean observedWait = new AtomicBoolean();
        AtomicBoolean supplied = new AtomicBoolean();

        context.runAtTickTime(450, () -> failWithProgress(context, fixture));

        context.onEachTick(() -> {
            if (fixture.controller().state() == BuilderStateMachine.State.WAIT_MATERIAL) {
                observedWait.set(true);
                if (supplied.compareAndSet(false, true)) {
                    fixture.chest().setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
                    fixture.chest().setChanged();
                }
            }
        });
        finishWhenComplete(context, fixture, () -> {
            context.assertTrue(observedWait.get(), "Missing bundle never entered WAIT_MATERIAL");
            assertCompletedWall(context, fixture);
        });
    }

    @GameTest(maxTicks = 200, padding = 16)
    public void componentBearingBlockItemIsNotConsumedAsCanonicalMaterial(GameTestHelper context) {
        Fixture fixture = start(context, layout(context), 0, "component-material");
        ItemStack namedPlanks = new ItemStack(Items.OAK_PLANKS, 3);
        namedPlanks.set(DataComponents.CUSTOM_NAME, Component.literal("Reserved planks"));
        fixture.chest().setItem(0, namedPlanks);
        fixture.chest().setChanged();

        context.onEachTick(() -> {
            if (fixture.controller().state() != BuilderStateMachine.State.WAIT_MATERIAL) {
                return;
            }
            context.assertValueEqual(fixture.chest().getItem(0).getCount(), 3,
                    "component-bearing chest stack");
            context.assertValueEqual(fixture.builder().carriedItemCount(Items.OAK_PLANKS), 0,
                    "component-bearing carried count");
            context.assertBlockPresent(Blocks.AIR, fixture.layout().wallOrigin());
            fixture.builder().discard();
            fixture.persistence().close();
            context.succeed();
        });
    }

    @GameTest(maxTicks = 100, padding = 16)
    public void productionRuntimeReattachesAReloadedBuilderBeforeScheduling(GameTestHelper context) {
        Layout layout = layout(context);
        createFloor(context, layout);
        context.setBlock(layout.chest(), Blocks.CHEST);
        ServerLevel level = context.getLevel();
        UUID owner = UUID.randomUUID();
        UUID hutId = UUID.randomUUID();
        String jobId = UUID.randomUUID().toString();
        ContainerBinding binding = ContainerBinding.resolve(
                level.dimension().identifier(),
                context.absolutePos(layout.chest()),
                Optional.empty(),
                Optional.empty());
        BlockPos origin = context.absolutePos(layout.wallOrigin());
        BuildJob job = BuildJob.create(
                jobId,
                owner.toString(),
                hutId.toString(),
                "blueprint-runtime-reload",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                NamespacedId.parse(level.dimension().identifier().toString()),
                new GridPos(origin.getX(), origin.getY(), origin.getZ()),
                0);
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(level);
        repository.saveJob(job);
        repository.saveHutState(new ServerBuildJobRepository.HutState(
                hutId,
                owner,
                Optional.of(jobId),
                Optional.of(binding),
                Optional.empty(),
                1));
        BuilderEntity original = BuilderRuntimeService.start(
                level,
                job,
                oneBlockGraph(),
                binding,
                context.absolutePos(layout.builderStart())).orElseThrow();
        UUID builderId = original.getUUID();
        original.discard();

        BuilderEntity reloaded = new BuilderEntity(ModEntityTypes.BUILDER, level);
        reloaded.setUUID(builderId);
        BlockPos start = context.absolutePos(layout.builderStart());
        reloaded.setPos(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        context.assertTrue(level.addFreshEntity(reloaded), "Reloaded Builder was rejected");

        context.onEachTick(() -> {
            if (!reloaded.hasController()) {
                return;
            }
            context.assertValueEqual(
                    repository.findPlan(jobId).orElseThrow().tasks().keySet(),
                    oneBlockGraph().tasks().keySet(),
                    "durable Builder task graph");
            context.assertValueEqual(
                    repository.findHut(hutId).orElseThrow().builderLifecycle().orElseThrow().builderId(),
                    builderId,
                    "durable Builder identity");
            reloaded.discard();
            context.succeed();
        });
    }

    private static Fixture start(GameTestHelper context, Layout layout, int materialCount, String name) {
        return start(context, layout, materialCount, name, wallGraph(), true);
    }

    private static Fixture start(
            GameTestHelper context,
            Layout layout,
            int materialCount,
            String name,
            TaskGraph graph,
            boolean assign) {
        return start(context, layout, materialCount, name, graph, assign, true);
    }

    private static Fixture start(
            GameTestHelper context,
            Layout layout,
            int materialCount,
            String name,
            TaskGraph graph,
            boolean assign,
            boolean createFloor) {
        if (createFloor) {
            createFloor(context, layout);
        }
        context.setBlock(layout.chest(), Blocks.CHEST);
        ChestBlockEntity chest = context.getBlockEntity(layout.chest(), ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.OAK_PLANKS, materialCount));
        BuilderEntity builder = context.spawn(ModEntityTypes.BUILDER, layout.builderStart());
        ServerLevel level = context.getLevel();
        UUID owner = UUID.randomUUID();
        UUID hutId = UUID.randomUUID();
        String jobId = UUID.randomUUID().toString();
        ContainerBinding binding = ContainerBinding.resolve(
                level.dimension().identifier(),
                context.absolutePos(layout.chest()),
                Optional.empty(),
                Optional.empty());
        BlockPos absoluteOrigin = context.absolutePos(layout.wallOrigin());
        BuildJob job = BuildJob.create(
                jobId,
                owner.toString(),
                hutId.toString(),
                "blueprint-" + name,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                NamespacedId.parse(level.dimension().identifier().toString()),
                new GridPos(absoluteOrigin.getX(), absoluteOrigin.getY(), absoluteOrigin.getZ()),
                0);
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(level);
        repository.saveJob(job);
        repository.saveHutState(new ServerBuildJobRepository.HutState(
                hutId,
                owner,
                Optional.of(jobId),
                Optional.of(binding),
                Optional.empty(),
                1));

        PersistenceExecutor persistence = new PersistenceExecutor("ssa-builder-gametest");
        OperationIntentStore store = new OperationIntentStore(walPath(name), persistence);
        BoundaryRecorder boundaries = new BoundaryRecorder();
        FabricMutationExecutor mutations = new FabricMutationExecutor(
                store,
                level.getServer()::execute,
                boundaries);
        BuilderChestLinkService links = new BuilderChestLinkService((ignoredOwner, ignoredPosition) -> true);
        BuilderController controller = new BuilderController(
                builder,
                mutations,
                new MaterialTransferService(mutations),
                repository,
                links,
                (ignoredOwner, ignoredPosition) -> true);
        builder.attachController(controller);
        BuilderController.WorkOrder workOrder = new BuilderController.WorkOrder(jobId, graph, binding);
        if (assign) {
            controller.assign(workOrder);
        }
        return new Fixture(
                builder,
                controller,
                chest,
                store,
                persistence,
                repository,
                jobId,
                boundaries,
                layout,
                workOrder);
    }

    private static TaskGraph wallGraph() {
        BlockStateSpec planks = BlockStateSpec.of(NamespacedId.parse("minecraft:oak_planks"), Map.of());
        List<BuildTask> tasks = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            GridPos position = new GridPos(x, 0, 0);
            tasks.add(new BuildTask(
                    "wall-" + x,
                    position,
                    TaskOperation.PLACE,
                    Optional.of(new BuildTask.MaterialRequirement(MaterialRole.WALL_PRIMARY, planks)),
                    java.util.Set.of(),
                    BuildPhase.WALLS,
                    WorkZone.containing(position),
                    false,
                    Optional.empty()));
        }
        return new TaskGraph(tasks);
    }

    private static TaskGraph oneBlockGraph() {
        return new TaskGraph(List.of(wallGraph().task("wall-0")));
    }

    private static BlockPos[] findEntityTickingBoundary(GameTestHelper context) {
        ServerLevel level = context.getLevel();
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                BlockPos local = new BlockPos(x, 1, z);
                BlockPos absolute = context.absolutePos(local);
                if (!level.isPositionEntityTicking(absolute)) {
                    continue;
                }
                for (int dx : new int[] {-1, 1}) {
                    BlockPos outsideLocal = local.offset(dx, 0, 0);
                    BlockPos outsideAbsolute = context.absolutePos(outsideLocal);
                    if (level.isLoaded(outsideAbsolute)
                            && !level.isPositionEntityTicking(outsideAbsolute)
                            && !new ChunkPos(absolute.getX() >> 4, absolute.getZ() >> 4)
                                    .equals(new ChunkPos(
                                            outsideAbsolute.getX() >> 4,
                                            outsideAbsolute.getZ() >> 4))) {
                        return new BlockPos[] {local, outsideLocal};
                    }
                }
            }
        }
        throw new IllegalStateException("GameTest fixture has no loaded entity-ticking chunk boundary");
    }

    private static BlockPos findTickingNeighbor(
            GameTestHelper context,
            BlockPos position,
            BlockPos excluded) {
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos candidate = position.relative(direction);
            if (!candidate.equals(excluded)
                    && context.getLevel().isPositionEntityTicking(context.absolutePos(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Entity-ticking boundary has no chest neighbor");
    }

    private static void finishWhenComplete(
            GameTestHelper context,
            Fixture fixture,
            Runnable assertions) {
        AtomicReference<CompletableFuture<?>> walCheck = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        context.onEachTick(() -> {
            if (finished.get()) {
                return;
            }
            if (fixture.controller().state() == BuilderStateMachine.State.BLOCKED) {
                finished.set(true);
                fixture.persistence().close();
                context.fail("Builder blocked: " + fixture.controller().failureReason());
                return;
            }
            BuildJob job = fixture.repository().findJob(fixture.jobId()).orElseThrow();
            if (job.state() != BuildJobState.COMPLETED) {
                return;
            }
            CompletableFuture<?> activeCheck = walCheck.updateAndGet(current ->
                    current == null ? fixture.store().loadActive() : current);
            if (!activeCheck.isDone() || !finished.compareAndSet(false, true)) {
                return;
            }
            try {
                context.assertTrue(((Optional<?>) activeCheck.join()).isEmpty(),
                        "Completed Builder left an active OperationIntent");
                assertions.run();
                fixture.persistence().close();
                context.succeed();
            } catch (Throwable failure) {
                fixture.persistence().close();
                context.fail("Builder material loop failed: " + rootMessage(failure));
            }
        });
    }

    private static void assertCompletedWall(GameTestHelper context, Fixture fixture) {
        for (int x = 0; x < 3; x++) {
            context.assertBlockPresent(Blocks.OAK_PLANKS, fixture.layout().wallOrigin().offset(x, 0, 0));
        }
        BuildJob job = fixture.repository().findJob(fixture.jobId()).orElseThrow();
        context.assertValueEqual(job.completedTaskIds().size(), 3, "completed wall task count");
        context.assertValueEqual(job.blockJournal().size(), 3, "wall journal size");
        context.assertValueEqual(fixture.chest().getItem(0).getCount(), 0, "linked chest remainder");
        context.assertValueEqual(fixture.builder().carriedItemCount(Items.OAK_PLANKS), 0, "carried remainder");
        context.assertTrue(fixture.builder().maxTickDisplacement() > 0.05,
                "Builder did not physically move");
        context.assertValueEqual(fixture.boundaries().durablePrepareCount(), 4,
                "durable transfer plus placement prepare count");
        context.assertTrue(fixture.boundaries().allAcknowledgedOffServerThread(),
                "OperationIntent acknowledgement ran on the server thread");
        context.assertValueEqual(fixture.controller().state(), BuilderStateMachine.State.IDLE,
                "controller state after completion");
    }

    private static void failWithProgress(GameTestHelper context, Fixture fixture) {
        BuildJob job = fixture.repository().findJob(fixture.jobId()).orElseThrow();
        fixture.persistence().close();
        context.fail("Builder progress at tick " + context.getTick()
                + ": controller=" + fixture.controller().state()
                + ", job=" + job.state()
                + ", completed=" + job.completedTaskIds().size()
                + ", journal=" + job.blockJournal().size()
                + ", position=" + fixture.builder().position()
                + ", target=" + fixture.builder().getNavigation().getTargetPos()
                + ", history=" + fixture.controller().stateHistory());
    }

    private static Layout layout(GameTestHelper context) {
        BlockPos absoluteOrigin = context.absolutePos(BlockPos.ZERO);
        int chunkMinX = -Math.floorMod(absoluteOrigin.getX(), 16);
        int chunkMinZ = -Math.floorMod(absoluteOrigin.getZ(), 16);
        return new Layout(
                new BlockPos(chunkMinX + 2, 1, chunkMinZ + 2),
                new BlockPos(chunkMinX + 2, 1, chunkMinZ + 5),
                new BlockPos(chunkMinX + 9, 1, chunkMinZ + 5),
                chunkMinX,
                chunkMinZ);
    }

    private static void createFloor(GameTestHelper context, Layout layout) {
        for (int x = layout.chunkMinX(); x <= layout.chunkMinX() + 15; x++) {
            for (int z = layout.chunkMinZ(); z <= layout.chunkMinZ() + 15; z++) {
                context.setBlock(x, 0, z, Blocks.STONE);
                context.setBlock(x, 1, z, Blocks.AIR);
                context.setBlock(x, 2, z, Blocks.AIR);
                context.setBlock(x, 3, z, Blocks.AIR);
            }
        }
    }

    private static Path walPath(String name) {
        return Path.of("build", "builder-gametest", name + "-" + UUID.randomUUID() + ".wal");
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static final class BoundaryRecorder implements OperationBoundaryListener {
        private int durablePrepareCount;
        private boolean allAcknowledgedOffServerThread = true;

        @Override
        public void afterPrepared(dev.ssa.fabric.persistence.DurableAcknowledgement acknowledgement) {
            durablePrepareCount++;
            allAcknowledgedOffServerThread &= acknowledgement.ioThread().startsWith("ssa-builder-gametest-");
        }

        int durablePrepareCount() {
            return durablePrepareCount;
        }

        boolean allAcknowledgedOffServerThread() {
            return allAcknowledgedOffServerThread;
        }

    }

    private record Fixture(
            BuilderEntity builder,
            BuilderController controller,
            ChestBlockEntity chest,
            OperationIntentStore store,
            PersistenceExecutor persistence,
            ServerBuildJobRepository repository,
            String jobId,
            BoundaryRecorder boundaries,
            Layout layout,
            BuilderController.WorkOrder workOrder) {
    }

    private record Layout(
            BlockPos chest,
            BlockPos builderStart,
            BlockPos wallOrigin,
            int chunkMinX,
            int chunkMinZ) {
    }
}
