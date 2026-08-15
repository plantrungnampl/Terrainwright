package dev.ssa.fabric.job;

import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.construction.plan.TaskGraph;
import dev.ssa.construction.schedule.WorkZone;
import dev.ssa.construction.task.BuildTask;
import dev.ssa.construction.task.TaskOperation;
import dev.ssa.fabric.builder.BuilderRuntimeService;
import dev.ssa.fabric.entity.BuilderEntity;
import dev.ssa.fabric.entity.ModEntityTypes;
import dev.ssa.fabric.lifecycle.BuilderLifecycleTombstone;
import dev.ssa.fabric.link.ContainerBinding;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class BuilderLossRecoveryGameTest {
    @GameTest(maxTicks = 120, padding = 12)
    public void initialSpawnWaitsForDurableLifecycleCheckpoint(GameTestHelper context) {
        ServerLevel level = context.getLevel();
        BlockPos chest = context.absolutePos(new BlockPos(2, 1, 2));
        BlockPos builderStart = context.absolutePos(new BlockPos(2, 1, 5));
        BlockPos origin = context.absolutePos(new BlockPos(8, 1, 5));
        context.setBlock(new BlockPos(2, 1, 2), Blocks.CHEST);
        UUID ownerId = UUID.randomUUID();
        UUID hutId = UUID.randomUUID();
        String jobId = UUID.randomUUID().toString();
        BuildJob job = BuildJob.create(
                jobId,
                ownerId.toString(),
                hutId.toString(),
                "blueprint-durable-spawn",
                "aaaaaaaaaaaaaaaa",
                NamespacedId.parse(level.dimension().identifier().toString()),
                new GridPos(origin.getX(), origin.getY(), origin.getZ()),
                0);
        ContainerBinding binding = ContainerBinding.resolve(
                level.dimension().identifier(), chest, Optional.empty(), Optional.empty());
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(level);
        repository.saveJob(job);
        repository.saveHutState(new ServerBuildJobRepository.HutState(
                hutId,
                ownerId,
                Optional.of(jobId),
                Optional.of(binding),
                Optional.empty(),
                1));

        CompletableFuture<Optional<BuilderEntity>> spawned = BuilderRuntimeService.start(
                level, job, plan(), binding, builderStart);
        UUID durableBuilderId = repository.findHut(hutId)
                .orElseThrow()
                .builderLifecycle()
                .orElseThrow()
                .builderId();

        context.onEachTick(() -> {
            if (!spawned.isDone()) {
                return;
            }
            BuilderEntity builder = spawned.join().orElseThrow();
            context.assertValueEqual(builder.getUUID(), durableBuilderId, "durable spawned identity");
            builder.discard();
            context.succeed();
        });
    }

    @GameTest(maxTicks = 40, padding = 12)
    public void missingActiveBuilderBecomesReplaceableNoBuilderState(GameTestHelper context) {
        ServerLevel level = context.getLevel();
        BlockPos chest = context.absolutePos(new BlockPos(2, 1, 2));
        BlockPos builderStart = context.absolutePos(new BlockPos(2, 1, 5));
        BlockPos origin = context.absolutePos(new BlockPos(8, 1, 5));
        context.setBlock(new BlockPos(2, 1, 2), Blocks.CHEST);
        UUID ownerId = UUID.randomUUID();
        UUID hutId = UUID.randomUUID();
        UUID missingBuilderId = UUID.randomUUID();
        String jobId = UUID.randomUUID().toString();
        BuildJob job = BuildJob.create(
                jobId,
                ownerId.toString(),
                hutId.toString(),
                "blueprint-missing-active-builder",
                "aaaaaaaaaaaaaaaa",
                NamespacedId.parse(level.dimension().identifier().toString()),
                new GridPos(origin.getX(), origin.getY(), origin.getZ()),
                0);
        ContainerBinding binding = ContainerBinding.resolve(
                level.dimension().identifier(), chest, Optional.empty(), Optional.empty());
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(level);
        repository.saveJob(job);
        repository.savePlan(jobId, plan());
        repository.saveHutState(new ServerBuildJobRepository.HutState(
                hutId,
                ownerId,
                Optional.of(jobId),
                Optional.of(binding),
                Optional.of(BuilderLifecycleTombstone.active(missingBuilderId)),
                1));

        Optional<BuilderEntity> result = BuilderRuntimeService.start(
                level, job, plan(), binding, builderStart).join();
        context.assertTrue(result.isEmpty(), "runtime unexpectedly recreated an ACTIVE Builder identity");
        BuildJob recovered = repository.findJob(jobId).orElseThrow();
        BuilderLifecycleTombstone lifecycle = repository.findHut(hutId)
                .orElseThrow()
                .builderLifecycle()
                .orElseThrow();
        context.assertValueEqual(recovered.state(), BuildJobState.NO_BUILDER,
                "missing ACTIVE Builder job state");
        context.assertTrue(lifecycle.canReplace(),
                "missing ACTIVE Builder did not produce replaceable lifecycle evidence");
        context.assertValueEqual(lifecycle.builderId(), missingBuilderId,
                "missing Builder identity changed during recovery");
        context.succeed();
    }

    @GameTest(maxTicks = 300, padding = 12)
    public void deathTombstonesThenExplicitReplacementStartsEmpty(GameTestHelper context) {
        Fixture fixture = start(context, "death");
        fixture.builder().carriedItems().setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
        AtomicReference<CompletableFuture<Optional<BuilderEntity>>> replacement = new AtomicReference<>();

        context.runAtTickTime(5, () -> fixture.builder().hurtServer(
                fixture.level(),
                fixture.level().damageSources().genericKill(),
                Float.MAX_VALUE));
        context.onEachTick(() -> {
            BuildJob job = fixture.repository().findJob(fixture.jobId()).orElseThrow();
            BuilderLifecycleTombstone lifecycle = fixture.repository()
                    .findHut(fixture.hutId())
                    .orElseThrow()
                    .builderLifecycle()
                    .orElseThrow();
            if (replacement.get() == null) {
                if (job.state() != BuildJobState.NO_BUILDER || !lifecycle.canReplace()) {
                    return;
                }
                context.assertValueEqual(fixture.builder().carriedItemCount(Items.OAK_PLANKS), 0,
                        "dead Builder carried inventory after drops");
                replacement.set(BuilderRuntimeService.replace(
                        fixture.level(),
                        fixture.hutId(),
                        fixture.ownerId(),
                        fixture.builderStart()));
                return;
            }
            if (!replacement.get().isDone()) {
                return;
            }
            BuilderEntity newBuilder = replacement.get().join().orElseThrow();
            BuilderLifecycleTombstone active = fixture.repository()
                    .findHut(fixture.hutId())
                    .orElseThrow()
                    .builderLifecycle()
                    .orElseThrow();
            context.assertTrue(!newBuilder.getUUID().equals(fixture.builder().getUUID()),
                    "Replacement reused the tombstoned identity");
            context.assertValueEqual(newBuilder.carriedItems().countItem(Items.OAK_PLANKS), 0,
                    "replacement carried inventory");
            context.assertValueEqual(active.status(), BuilderLifecycleTombstone.Status.ACTIVE,
                    "replacement lifecycle");
            context.assertValueEqual(active.builderId(), newBuilder.getUUID(),
                    "replacement durable identity");
            newBuilder.discard();
            context.succeed();
        });
    }

    @GameTest(maxTicks = 160, padding = 12)
    public void chunkUnloadSuspendsAndReloadsTheSameIdentity(GameTestHelper context) {
        Fixture fixture = start(context, "unload");
        UUID builderId = fixture.builder().getUUID();
        AtomicBoolean reloaded = new AtomicBoolean();
        AtomicReference<BuilderEntity> loadedBuilder = new AtomicReference<>();

        context.runAtTickTime(5, () -> fixture.builder().remove(Entity.RemovalReason.UNLOADED_TO_CHUNK));
        context.onEachTick(() -> {
            ServerBuildJobRepository.HutState hut = fixture.repository().findHut(fixture.hutId()).orElseThrow();
            BuilderLifecycleTombstone lifecycle = hut.builderLifecycle().orElseThrow();
            BuildJob job = fixture.repository().findJob(fixture.jobId()).orElseThrow();
            if (!reloaded.get()) {
                if (lifecycle.status() != BuilderLifecycleTombstone.Status.SUSPENDED) {
                    return;
                }
                context.assertValueEqual(job.state(), BuildJobState.SUSPENDED_CHUNK_UNLOADED,
                        "job state while Builder chunk is unloaded");
                context.assertTrue(!lifecycle.canReplace(), "chunk unload authorized replacement");
                context.assertTrue(BuilderRuntimeService.start(
                        fixture.level(),
                        job,
                        fixture.plan(),
                        fixture.binding(),
                        fixture.builderStart()).join().isEmpty(), "runtime auto-replaced an unloaded Builder");

                BuilderEntity entity = new BuilderEntity(ModEntityTypes.BUILDER, fixture.level());
                entity.setUUID(builderId);
                entity.setPos(
                        fixture.builderStart().getX() + 0.5,
                        fixture.builderStart().getY(),
                        fixture.builderStart().getZ() + 0.5);
                context.assertTrue(fixture.level().addFreshEntity(entity), "Reloaded Builder was rejected");
                context.assertValueEqual(
                        fixture.repository().findHut(fixture.hutId())
                                .orElseThrow()
                                .builderLifecycle()
                                .orElseThrow()
                                .status(),
                        BuilderLifecycleTombstone.Status.SUSPENDED,
                        "lifecycle resumed before OperationIntent recovery");
                context.assertValueEqual(
                        fixture.repository().findJob(fixture.jobId()).orElseThrow().state(),
                        BuildJobState.SUSPENDED_CHUNK_UNLOADED,
                        "job resumed before OperationIntent recovery");
                loadedBuilder.set(entity);
                reloaded.set(true);
                return;
            }
            BuilderEntity entity = loadedBuilder.get();
            if (!entity.hasController()
                    || lifecycle.status() != BuilderLifecycleTombstone.Status.ACTIVE
                    || job.state() == BuildJobState.SUSPENDED_CHUNK_UNLOADED) {
                return;
            }
            context.assertValueEqual(lifecycle.builderId(), builderId, "reloaded Builder identity");
            entity.discard();
            context.succeed();
        });
    }

    @GameTest(maxTicks = 100, padding = 12)
    public void hutLossRetainsAnOrphanedJobAndStopsScheduling(GameTestHelper context) {
        Fixture fixture = start(context, "hut-loss");
        AtomicReference<Long> orphanedRevision = new AtomicReference<>();

        context.runAtTickTime(5, () -> BuilderRuntimeService.observeHutLoss(
                fixture.level(), fixture.hutId()));
        context.onEachTick(() -> {
            BuildJob job = fixture.repository().findJob(fixture.jobId()).orElseThrow();
            if (job.state() != BuildJobState.ORPHANED) {
                return;
            }
            if (orphanedRevision.compareAndSet(null, job.revision())) {
                context.runAfterDelay(20, () -> {
                    BuildJob stable = fixture.repository().findJob(fixture.jobId()).orElseThrow();
                    context.assertValueEqual(
                            fixture.repository().findHut(fixture.hutId())
                                    .orElseThrow()
                                    .builderLifecycle()
                                    .orElseThrow()
                                    .builderId(),
                            fixture.builder().getUUID(),
                            "orphan recovery association");
                    context.assertValueEqual(stable.state(), BuildJobState.ORPHANED,
                            "orphaned job state");
                    context.assertValueEqual(stable.revision(), orphanedRevision.get(),
                            "orphaned job scheduled new work");
                    fixture.builder().discard();
                    context.succeed();
                });
            }
        });
    }

    private static Fixture start(GameTestHelper context, String name) {
        ServerLevel level = context.getLevel();
        BlockPos chest = context.absolutePos(new BlockPos(2, 1, 2));
        BlockPos builderStart = context.absolutePos(new BlockPos(2, 1, 5));
        BlockPos origin = context.absolutePos(new BlockPos(8, 1, 5));
        for (int x = 0; x <= 10; x++) {
            for (int z = 0; z <= 8; z++) {
                context.setBlock(x, 0, z, Blocks.STONE);
                context.setBlock(x, 1, z, Blocks.AIR);
                context.setBlock(x, 2, z, Blocks.AIR);
            }
        }
        context.setBlock(new BlockPos(2, 1, 2), Blocks.CHEST);
        UUID ownerId = UUID.randomUUID();
        UUID hutId = UUID.randomUUID();
        String jobId = UUID.randomUUID().toString();
        BuildJob job = BuildJob.create(
                jobId,
                ownerId.toString(),
                hutId.toString(),
                "blueprint-lifecycle-" + name,
                "aaaaaaaaaaaaaaaa",
                NamespacedId.parse(level.dimension().identifier().toString()),
                new GridPos(origin.getX(), origin.getY(), origin.getZ()),
                0);
        ContainerBinding binding = ContainerBinding.resolve(
                level.dimension().identifier(), chest, Optional.empty(), Optional.empty());
        TaskGraph plan = plan();
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(level);
        BuilderEntity builder = new BuilderEntity(ModEntityTypes.BUILDER, level);
        builder.setPos(
                builderStart.getX() + 0.5,
                builderStart.getY(),
                builderStart.getZ() + 0.5);
        context.assertTrue(level.addFreshEntity(builder), "Lifecycle Builder was rejected");
        repository.saveJob(job);
        repository.saveHutState(new ServerBuildJobRepository.HutState(
                hutId,
                ownerId,
                Optional.of(jobId),
                Optional.of(binding),
                Optional.of(BuilderLifecycleTombstone.active(builder.getUUID())),
                1));
        builder = BuilderRuntimeService.start(
                level, job, plan, binding, builderStart).join().orElseThrow();
        return new Fixture(
                level,
                repository,
                builder,
                ownerId,
                hutId,
                jobId,
                plan,
                binding,
                builderStart);
    }

    private static TaskGraph plan() {
        GridPos position = new GridPos(0, 0, 0);
        return new TaskGraph(List.of(new BuildTask(
                "wall-0",
                position,
                TaskOperation.PLACE,
                Optional.of(new BuildTask.MaterialRequirement(
                        MaterialRole.WALL_PRIMARY,
                        BlockStateSpec.of(NamespacedId.parse("minecraft:oak_planks"), Map.of()))),
                Set.of(),
                BuildPhase.WALLS,
                WorkZone.containing(position),
                false,
                Optional.empty())));
    }

    private record Fixture(
            ServerLevel level,
            ServerBuildJobRepository repository,
            BuilderEntity builder,
            UUID ownerId,
            UUID hutId,
            String jobId,
            TaskGraph plan,
            ContainerBinding binding,
            BlockPos builderStart) {
    }
}
