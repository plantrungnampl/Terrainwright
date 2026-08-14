package dev.ssa.fabric.undo;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.construction.journal.JournalEntry;
import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.fabric.construction.FabricMutationExecutor;
import dev.ssa.fabric.construction.OperationBoundaryListener;
import dev.ssa.fabric.persistence.OperationIntentStore;
import dev.ssa.fabric.persistence.PersistenceExecutor;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

public final class SafeUndoGameTest {
    @GameTest(maxTicks = 300)
    public void undoPreservesExternalEditsAndNeverRefundsMaterialsOrExperience(GameTestHelper context) {
        PersistenceExecutor persistence = new PersistenceExecutor("ssa-undo-gametest");
        OperationIntentStore store = new OperationIntentStore(
                Path.of("build", "undo-gametest", UUID.randomUUID() + ".wal"),
                persistence);
        FabricMutationExecutor mutations = new FabricMutationExecutor(
                store,
                context.getLevel().getServer()::execute,
                OperationBoundaryListener.NONE);
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(context.getLevel());
        UUID owner = UUID.randomUUID();
        BlockPos origin = context.absolutePos(BlockPos.ZERO);
        BuildJob job = completedJob(context, owner, origin);
        repository.saveJob(job);

        BlockPos externallyEdited = origin.offset(1, 1, 1);
        BlockPos constructed = origin.offset(2, 1, 1);
        BlockPos terrainPrepared = origin.offset(3, 1, 1);
        context.getLevel().setBlockAndUpdate(externallyEdited, Blocks.DIAMOND_BLOCK.defaultBlockState());
        context.getLevel().setBlockAndUpdate(constructed, Blocks.OAK_PLANKS.defaultBlockState());
        context.getLevel().setBlockAndUpdate(terrainPrepared, Blocks.DIRT.defaultBlockState());
        AABB evidenceBounds = new AABB(
                externallyEdited.getX(),
                externallyEdited.getY(),
                externallyEdited.getZ(),
                terrainPrepared.getX() + 1,
                terrainPrepared.getY() + 2,
                terrainPrepared.getZ() + 2);

        FabricUndoExecutor executor = new FabricUndoExecutor(
                context.getLevel(),
                repository,
                (ignoredOwner, ignoredPosition) -> true,
                mutations);
        CompletableFuture<FabricUndoExecutor.UndoResult> result = executor.undo(job.jobId(), owner);

        await(context, result, outcome -> {
            context.assertTrue(outcome.restoredCells() == 2, "Undo did not restore exactly the safe cells");
            context.assertTrue(outcome.conflicts().size() == 1, "External edit was not reported as one conflict");
            context.assertTrue(
                    context.getLevel().getBlockState(externallyEdited).is(Blocks.DIAMOND_BLOCK),
                    "Undo overwrote an external edit");
            context.assertTrue(
                    context.getLevel().getBlockState(constructed).isAir(),
                    "Construction Undo did not restore air");
            context.assertTrue(
                    context.getLevel().getBlockState(terrainPrepared).is(Blocks.DIAMOND_ORE),
                    "Terrain-preparation Undo did not restore its prior block");
            context.assertTrue(
                    context.getLevel().getEntitiesOfClass(ItemEntity.class, evidenceBounds).isEmpty(),
                    "Undo refunded consumed construction material");
            context.assertTrue(
                    context.getLevel().getEntitiesOfClass(ExperienceOrb.class, evidenceBounds).isEmpty(),
                    "Undo spawned experience refunds");
            context.assertTrue(
                    repository.findJob(job.jobId()).orElseThrow().state() == BuildJobState.UNDO_COMPLETED,
                    "Undo did not leave a durable completed job record");
        }, persistence);
    }

    private static BuildJob completedJob(GameTestHelper context, UUID owner, BlockPos origin) {
        BuildJob job = BuildJob.create(
                        "undo-job-" + UUID.randomUUID(),
                        owner.toString(),
                        UUID.randomUUID().toString(),
                        "ssa:undo-blueprint",
                        "0123456789abcdef",
                        NamespacedId.parse(context.getLevel().dimension().identifier().toString()),
                        new GridPos(origin.getX(), origin.getY(), origin.getZ()),
                        0)
                .transitionTo(BuildJobState.PREPARING)
                .transitionTo(BuildJobState.NAVIGATING)
                .transitionTo(BuildJobState.BUILDING);
        job = record(job, 0, new GridPos(1, 1, 1), state("minecraft:stone"), state("minecraft:oak_planks"));
        job = record(job, 1, new GridPos(2, 1, 1), state("minecraft:air"), state("minecraft:oak_planks"));
        job = record(job, 2, new GridPos(3, 1, 1), state("minecraft:diamond_ore"), state("minecraft:dirt"));
        return job.transitionTo(BuildJobState.COMPLETED);
    }

    private static BuildJob record(
            BuildJob job,
            long sequence,
            GridPos position,
            BlockStateSpec previous,
            BlockStateSpec written) {
        String suffix = Long.toString(sequence);
        JournalEntry entry = new JournalEntry(
                sequence,
                "undo-entry-" + suffix,
                "build-operation-" + suffix,
                "build-task-" + suffix,
                position,
                previous,
                written,
                job.revision() + 1);
        return job.recordCompletion(entry.taskId(), entry);
    }

    private static BlockStateSpec state(String id) {
        return new BlockStateSpec(NamespacedId.parse(id), Map.of());
    }

    private static <T> void await(
            GameTestHelper context,
            CompletableFuture<T> future,
            Consumer<T> assertions,
            PersistenceExecutor persistence) {
        context.onEachTick(() -> {
            if (!future.isDone()) {
                return;
            }
            try {
                assertions.accept(future.join());
                persistence.close();
                context.succeed();
            } catch (Throwable failure) {
                persistence.close();
                context.fail("Safe Undo test failed: " + rootMessage(failure));
            }
        });
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
