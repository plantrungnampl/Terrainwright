package dev.ssa.fabric.construction;

import dev.ssa.construction.operation.DropPolicy;
import dev.ssa.construction.operation.OperationIntent;
import dev.ssa.construction.operation.OperationKind;
import dev.ssa.construction.operation.WorldDelta;
import dev.ssa.fabric.persistence.OperationIntentStore;
import dev.ssa.fabric.persistence.PersistenceExecutor;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class OperationIntentProductionGameTest {
    @GameTest(maxTicks = 200)
    public void materialTransferIsDurableBeforeExactSlotMutation(GameTestHelper context) {
        PersistenceExecutor persistence = new PersistenceExecutor("ssa-operation-gametest");
        OperationIntentStore store = new OperationIntentStore(walPath("material"), persistence);
        FabricMutationExecutor mutations = new FabricMutationExecutor(
                store,
                context.getLevel().getServer()::execute,
                OperationBoundaryListener.NONE);
        MaterialTransferService transfers = new MaterialTransferService(mutations);
        SimpleContainer source = new SimpleContainer(2);
        SimpleContainer destination = new SimpleContainer(2);
        source.setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
        RecordingCommitLog journal = new RecordingCommitLog();
        UUID owner = UUID.randomUUID();

        CompletableFuture<TransferEvidence> result = transfers.transfer(
                        "material-operation",
                        "job-material",
                        Optional.of("task-wall"),
                        7,
                        context.getLevel(),
                        owner,
                        (ignoredOwner, ignoredPosition) -> true,
                        new FabricMutationExecutor.BoundInventory("linked-chest", 3, source),
                        0,
                        new FabricMutationExecutor.BoundInventory("builder-carried", 5, destination),
                        0,
                        2,
                        journal)
                .thenCompose(committed -> store.loadActive().thenApply(active -> new TransferEvidence(
                        committed,
                        active.isPresent(),
                        source.getItem(0).getCount(),
                        destination.getItem(0).getCount(),
                        journal.isCommitted("material-operation"))));

        await(context, result, evidence -> {
            context.assertTrue(evidence.result().outcome() == CoordinatorOutcome.COMMITTED,
                    "Material transfer did not commit");
            context.assertTrue(!evidence.activeIntent(), "Committed material intent remained active");
            context.assertTrue(evidence.sourceCount() == 2, "Source slot was not debited exactly once");
            context.assertTrue(evidence.destinationCount() == 2, "Destination slot was not credited exactly once");
            context.assertTrue(evidence.journalCommitted(), "Material operation journal was not committed");
        }, persistence);
    }

    @GameTest(maxTicks = 200)
    public void permissionFailureCreatesNoPreparedWorldIntent(GameTestHelper context) {
        PersistenceExecutor persistence = new PersistenceExecutor("ssa-operation-gametest");
        OperationIntentStore store = new OperationIntentStore(walPath("permission"), persistence);
        FabricMutationExecutor mutations = new FabricMutationExecutor(
                store,
                context.getLevel().getServer()::execute,
                OperationBoundaryListener.NONE);
        BlockPos position = context.absolutePos(new BlockPos(1, 1, 1));
        context.getLevel().setBlockAndUpdate(position, Blocks.STONE.defaultBlockState());
        OperationIntent intent = worldIntent(context, position, "denied-operation");

        CompletableFuture<DeniedEvidence> result = mutations.execute(
                        intent,
                        context.getLevel(),
                        UUID.randomUUID(),
                        (ignoredOwner, ignoredPosition) -> false,
                        List.of(),
                        new RecordingCommitLog())
                .handle((ignored, failure) -> {
                    if (failure == null || !hasSecurityCause(failure)) {
                        throw new AssertionError("Permission rejection did not fail before mutation", failure);
                    }
                    return failure;
                })
                .thenCompose(ignored -> store.loadActive())
                .thenApply(active -> new DeniedEvidence(
                        active.isPresent(),
                        context.getLevel().getBlockState(position).is(Blocks.STONE)));

        await(context, result, evidence -> {
            context.assertTrue(!evidence.activeIntent(), "Permission failure wrote a PREPARED intent");
            context.assertTrue(evidence.worldUnchanged(), "Permission failure changed the world");
        }, persistence);
    }

    @GameTest(maxTicks = 200)
    public void worldMutationCommitsExactStateWithoutDrops(GameTestHelper context) {
        PersistenceExecutor persistence = new PersistenceExecutor("ssa-operation-gametest");
        OperationIntentStore store = new OperationIntentStore(walPath("world"), persistence);
        FabricMutationExecutor mutations = new FabricMutationExecutor(
                store,
                context.getLevel().getServer()::execute,
                OperationBoundaryListener.NONE);
        BlockPos position = context.absolutePos(new BlockPos(1, 1, 1));
        context.getLevel().setBlockAndUpdate(position, Blocks.STONE.defaultBlockState());
        RecordingCommitLog journal = new RecordingCommitLog();

        CompletableFuture<WorldEvidence> result = mutations.execute(
                        worldIntent(context, position, "world-operation"),
                        context.getLevel(),
                        UUID.randomUUID(),
                        (ignoredOwner, ignoredPosition) -> true,
                        List.of(),
                        journal)
                .thenCompose(committed -> store.loadActive().thenApply(active -> new WorldEvidence(
                        committed,
                        active.isPresent(),
                        context.getLevel().getBlockState(position).is(Blocks.OAK_PLANKS),
                        journal.isCommitted("world-operation"))));

        await(context, result, evidence -> {
            context.assertTrue(evidence.result().outcome() == CoordinatorOutcome.COMMITTED,
                    "World mutation did not commit");
            context.assertTrue(!evidence.activeIntent(), "Committed world intent remained active");
            context.assertTrue(evidence.worldChanged(), "Exact world after-state was not written");
            context.assertTrue(evidence.journalCommitted(), "World operation journal was not committed");
        }, persistence);
    }

    @GameTest(maxTicks = 200)
    public void recoveryCompletesPreparedPrefixWithoutRecheckingPermission(GameTestHelper context) {
        PersistenceExecutor persistence = new PersistenceExecutor("ssa-operation-gametest");
        OperationIntentStore store = new OperationIntentStore(walPath("recovery-prefix"), persistence);
        FabricMutationExecutor mutations = new FabricMutationExecutor(
                store,
                context.getLevel().getServer()::execute,
                OperationBoundaryListener.NONE);
        BlockPos first = context.absolutePos(new BlockPos(1, 1, 1));
        BlockPos second = context.absolutePos(new BlockPos(2, 1, 1));
        context.getLevel().setBlockAndUpdate(first, Blocks.STONE.defaultBlockState());
        context.getLevel().setBlockAndUpdate(second, Blocks.STONE.defaultBlockState());
        RecordingCommitLog journal = new RecordingCommitLog();
        OperationIntent intent = recoveryIntent(context, first, second);

        CompletableFuture<RecoveryEvidence> result = store.prepare(intent)
                .thenRunAsync(
                        () -> context.getLevel().setBlockAndUpdate(first, Blocks.OAK_PLANKS.defaultBlockState()),
                        context.getLevel().getServer()::execute)
                .thenCompose(ignored -> mutations.recover(
                        context.getLevel(),
                        UUID.randomUUID(),
                        (ignoredOwner, ignoredPosition) -> false,
                        List.of(),
                        journal))
                .thenCompose(recovered -> store.loadActive().thenApply(active -> new RecoveryEvidence(
                        recovered,
                        active.isPresent(),
                        context.getLevel().getBlockState(first).is(Blocks.OAK_PLANKS),
                        context.getLevel().getBlockState(second).is(Blocks.OAK_PLANKS),
                        journal.isCommitted("recovery-prefix-operation"))));

        await(context, result, evidence -> {
            context.assertTrue(evidence.result().outcome() == CoordinatorOutcome.COMMITTED,
                    "Prepared prefix recovery did not commit");
            context.assertTrue(!evidence.activeIntent(), "Recovered intent remained active");
            context.assertTrue(evidence.firstChanged(), "Recovered prefix was not preserved");
            context.assertTrue(evidence.secondChanged(), "Recovery did not complete the suffix");
            context.assertTrue(evidence.journalCommitted(), "Recovered operation journal was not committed");
        }, persistence);
    }

    private static OperationIntent worldIntent(GameTestHelper context, BlockPos position, String operationId) {
        MinecraftSnapshotAdapter snapshots = new MinecraftSnapshotAdapter(context.getLevel().registryAccess());
        return OperationIntent.prepared(
                operationId,
                "job-world",
                Optional.of("task-foundation"),
                Optional.empty(),
                4,
                OperationKind.WORLD_MUTATION,
                List.of(new WorldDelta(
                        context.getLevel().dimension().identifier().toString(),
                        position.getX(),
                        position.getY(),
                        position.getZ(),
                        snapshots.snapshot(Blocks.STONE.defaultBlockState()),
                        snapshots.snapshot(Blocks.OAK_PLANKS.defaultBlockState()),
                        DropPolicy.SUPPRESS)));
    }

    private static OperationIntent recoveryIntent(GameTestHelper context, BlockPos first, BlockPos second) {
        MinecraftSnapshotAdapter snapshots = new MinecraftSnapshotAdapter(context.getLevel().registryAccess());
        return OperationIntent.prepared(
                "recovery-prefix-operation",
                "job-recovery",
                Optional.of("task-recovery"),
                Optional.of("atomic-recovery"),
                9,
                OperationKind.WORLD_MUTATION,
                List.of(
                        worldDelta(context, snapshots, first),
                        worldDelta(context, snapshots, second)));
    }

    private static WorldDelta worldDelta(
            GameTestHelper context,
            MinecraftSnapshotAdapter snapshots,
            BlockPos position) {
        return new WorldDelta(
                context.getLevel().dimension().identifier().toString(),
                position.getX(),
                position.getY(),
                position.getZ(),
                snapshots.snapshot(Blocks.STONE.defaultBlockState()),
                snapshots.snapshot(Blocks.OAK_PLANKS.defaultBlockState()),
                DropPolicy.SUPPRESS);
    }

    private static <T> void await(
            GameTestHelper context,
            CompletableFuture<T> future,
            Consumer<T> assertions,
            PersistenceExecutor persistence) {
        AtomicBoolean finished = new AtomicBoolean();
        context.onEachTick(() -> {
            if (!future.isDone() || !finished.compareAndSet(false, true)) {
                return;
            }
            try {
                assertions.accept(future.join());
                persistence.close();
                context.succeed();
            } catch (Throwable failure) {
                persistence.close();
                context.fail("OperationIntent production test failed: " + rootMessage(failure));
            }
        });
    }

    private static Path walPath(String name) {
        return Path.of("build", "operation-gametest", name + "-" + UUID.randomUUID() + ".wal");
    }

    private static boolean hasSecurityCause(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SecurityException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static final class RecordingCommitLog implements FabricMutationExecutor.CommitLog {
        private final Set<String> operationIds = new HashSet<>();

        @Override
        public boolean isCommitted(String operationId) {
            return operationIds.contains(operationId);
        }

        @Override
        public void commit(OperationIntent intent) {
            if (!operationIds.add(intent.operationId())) {
                throw new IllegalStateException("operation journal committed twice: " + intent.operationId());
            }
        }
    }

    private record TransferEvidence(
            CoordinatorResult result,
            boolean activeIntent,
            int sourceCount,
            int destinationCount,
            boolean journalCommitted) {
    }

    private record DeniedEvidence(boolean activeIntent, boolean worldUnchanged) {
    }

    private record WorldEvidence(
            CoordinatorResult result,
            boolean activeIntent,
            boolean worldChanged,
            boolean journalCommitted) {
    }

    private record RecoveryEvidence(
            CoordinatorResult result,
            boolean activeIntent,
            boolean firstChanged,
            boolean secondChanged,
            boolean journalCommitted) {
    }
}
