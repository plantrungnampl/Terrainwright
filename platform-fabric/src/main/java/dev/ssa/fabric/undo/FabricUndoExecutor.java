package dev.ssa.fabric.undo;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.common.permission.PermissionPort;
import dev.ssa.construction.journal.JournalEntry;
import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.job.BuildJob.Diagnostic;
import dev.ssa.construction.job.BuildJob.Severity;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.construction.operation.DropPolicy;
import dev.ssa.construction.operation.OperationIntent;
import dev.ssa.construction.operation.OperationKind;
import dev.ssa.construction.operation.WorldDelta;
import dev.ssa.construction.undo.UndoPlanner;
import dev.ssa.fabric.construction.CoordinatorOutcome;
import dev.ssa.fabric.construction.FabricMutationExecutor;
import dev.ssa.fabric.construction.MinecraftSnapshotAdapter;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Executes the conservative reverse journal through the production OperationIntent coordinator. */
public final class FabricUndoExecutor {
    private final ServerLevel level;
    private final ServerBuildJobRepository repository;
    private final PermissionPort permissions;
    private final FabricMutationExecutor mutations;
    private final UndoPlanner planner = new UndoPlanner();
    private final Executor serverExecutor;
    private final UndoCommitLog commitLog = new UndoCommitLog();

    public FabricUndoExecutor(
            ServerLevel level,
            ServerBuildJobRepository repository,
            PermissionPort permissions,
            FabricMutationExecutor mutations) {
        this.level = Objects.requireNonNull(level, "level");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        serverExecutor = level.getServer()::execute;
    }

    public CompletableFuture<UndoResult> undo(String jobId, UUID owner) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(owner, "owner");
        BuildJob current = requireOwnedJob(jobId, owner);
        if (current.state() == BuildJobState.STOPPED || current.state() == BuildJobState.COMPLETED) {
            current = current.transitionTo(BuildJobState.UNDOING);
            repository.saveJob(current);
        } else if (current.state() != BuildJobState.UNDOING) {
            throw new IllegalStateException("Safe Undo requires a stopped, completed, or recovering Undo job");
        }
        List<JournalEntry> reverseJournal = planner.reverseJournal(current.blockJournal());
        long undoRevision = current.revision();
        return checkpoint()
                .thenComposeAsync(ignored -> mutations.recover(
                        level,
                        owner,
                        permissions,
                        List.of(),
                        commitLog), serverExecutor)
                .thenComposeAsync(recovery -> {
                    if (recovery.outcome() == CoordinatorOutcome.QUARANTINED) {
                        return quarantine(jobId, "Safe Undo OperationIntent recovery found unknown evidence");
                    }
                    CompletableFuture<UndoResult> result = new CompletableFuture<>();
                    process(jobId, owner, undoRevision, reverseJournal, 0, 0, new ArrayList<>(), result);
                    return result;
                }, serverExecutor);
    }

    private void process(
            String jobId,
            UUID owner,
            long undoRevision,
            List<JournalEntry> reverseJournal,
            int startIndex,
            int restoredCells,
            List<UndoPlanner.UndoDecision> conflicts,
            CompletableFuture<UndoResult> result) {
        int index = startIndex;
        int restored = restoredCells;
        while (index < reverseJournal.size()) {
            JournalEntry entry = reverseJournal.get(index);
            BlockPos position = absolutePosition(requireOwnedJob(jobId, owner), entry.position());
            if (!level.isLoaded(position)) {
                result.completeExceptionally(new IllegalStateException(
                        "Safe Undo target is not loaded: " + position));
                return;
            }
            BlockState currentState = level.getBlockState(position);
            UndoPlanner.UndoDecision decision = planner.decide(
                    entry,
                    specification(currentState),
                    permissions.canModify(owner, gridPos(position)),
                    level.getBlockEntity(position) != null,
                    restore(entry.previousState()).getBlock() instanceof EntityBlock);
            if (decision.type() == UndoPlanner.UndoDecision.Type.CONFLICT_PRESERVE_CURRENT) {
                conflicts.add(decision);
                index++;
                continue;
            }

            OperationIntent intent = undoIntent(jobId, undoRevision, entry, position);
            int nextIndex = index + 1;
            int nextRestored = restored + 1;
            mutations.execute(intent, level, owner, permissions, List.of(), commitLog)
                    .whenCompleteAsync((ignored, failure) -> {
                        if (failure != null) {
                            result.completeExceptionally(failure);
                        } else {
                            process(
                                    jobId,
                                    owner,
                                    undoRevision,
                                    reverseJournal,
                                    nextIndex,
                                    nextRestored,
                                    conflicts,
                                    result);
                        }
                    }, serverExecutor);
            return;
        }
        finish(jobId, owner, restored, conflicts, result);
    }

    private void finish(
            String jobId,
            UUID owner,
            int restoredCells,
            List<UndoPlanner.UndoDecision> conflicts,
            CompletableFuture<UndoResult> result) {
        BuildJob current = requireOwnedJob(jobId, owner);
        if (current.state() != BuildJobState.UNDOING) {
            result.completeExceptionally(new IllegalStateException(
                    "Safe Undo job left UNDOING before its reverse journal completed"));
            return;
        }
        repository.saveJob(current.transitionTo(BuildJobState.UNDO_COMPLETED));
        checkpoint().whenComplete((ignored, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure);
            } else {
                result.complete(new UndoResult(jobId, restoredCells, List.copyOf(conflicts)));
            }
        });
    }

    private CompletableFuture<UndoResult> quarantine(String jobId, String message) {
        BuildJob current = repository.findJob(jobId).orElseThrow();
        if (current.state().canTransitionTo(BuildJobState.QUARANTINED_RECOVERY)) {
            repository.saveJob(current.recordDiagnosticAndTransition(
                    new Diagnostic(
                            "UNDO_INTENT_RECOVERY_QUARANTINED",
                            Severity.ERROR,
                            message,
                            true,
                            current.revision() + 1,
                            Optional.empty()),
                    BuildJobState.QUARANTINED_RECOVERY));
        }
        return checkpoint().thenCompose(ignored -> CompletableFuture.failedFuture(
                new IllegalStateException(message)));
    }

    private OperationIntent undoIntent(
            String jobId,
            long undoRevision,
            JournalEntry entry,
            BlockPos position) {
        String operationId = "undo-" + UUID.nameUUIDFromBytes(
                (jobId + ":" + entry.entryId()).getBytes(StandardCharsets.UTF_8));
        MinecraftSnapshotAdapter snapshots = new MinecraftSnapshotAdapter(level.registryAccess());
        return OperationIntent.prepared(
                operationId,
                jobId,
                Optional.of(operationId),
                Optional.empty(),
                undoRevision,
                OperationKind.WORLD_MUTATION,
                List.of(new WorldDelta(
                        level.dimension().identifier().toString(),
                        position.getX(),
                        position.getY(),
                        position.getZ(),
                        snapshots.snapshot(restore(entry.writtenState())),
                        snapshots.snapshot(restore(entry.previousState())),
                        DropPolicy.SUPPRESS)));
    }

    private BuildJob requireOwnedJob(String jobId, UUID owner) {
        BuildJob job = repository.findJob(jobId)
                .orElseThrow(() -> new IllegalStateException("Safe Undo job is missing: " + jobId));
        if (!job.ownerId().equals(owner.toString())) {
            throw new SecurityException("Only the durable BuildJob owner may execute Safe Undo");
        }
        return job;
    }

    private CompletableFuture<Void> checkpoint() {
        return level.getDataStorage().scheduleSave().thenApply(ignored -> (Void) null);
    }

    private static BlockPos absolutePosition(BuildJob job, GridPos relative) {
        int rotatedX;
        int rotatedZ;
        switch (job.rotation()) {
            case 0 -> {
                rotatedX = relative.x();
                rotatedZ = relative.z();
            }
            case 90 -> {
                rotatedX = -relative.z();
                rotatedZ = relative.x();
            }
            case 180 -> {
                rotatedX = -relative.x();
                rotatedZ = -relative.z();
            }
            case 270 -> {
                rotatedX = relative.z();
                rotatedZ = -relative.x();
            }
            default -> throw new IllegalStateException("Unsupported job rotation: " + job.rotation());
        }
        return new BlockPos(
                job.origin().x() + rotatedX,
                job.origin().y() + relative.y(),
                job.origin().z() + rotatedZ);
    }

    private static GridPos gridPos(BlockPos position) {
        return new GridPos(position.getX(), position.getY(), position.getZ());
    }

    private static BlockState restore(BlockStateSpec specification) {
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(specification.blockId().toString()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown journal block: " + specification.blockId()));
        BlockState state = block.defaultBlockState();
        for (Map.Entry<String, String> entry : specification.properties().entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            if (property == null) {
                throw new IllegalArgumentException(
                        "Unknown block property " + entry.getKey() + " for " + specification.blockId());
            }
            state = setProperty(state, property, entry.getValue());
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState setProperty(
            BlockState state,
            Property<T> property,
            String value) {
        T parsed = property.getValue(value)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invalid value " + value + " for block property " + property.getName()));
        return state.setValue(property, parsed);
    }

    private static BlockStateSpec specification(BlockState state) {
        Map<String, String> properties = new TreeMap<>();
        state.getProperties().forEach(property ->
                properties.put(property.getName(), propertyValueName(state, property)));
        return BlockStateSpec.of(
                NamespacedId.parse(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()),
                properties);
    }

    private static <T extends Comparable<T>> String propertyValueName(
            BlockState state,
            Property<T> property) {
        return property.getName(state.getValue(property));
    }

    public record UndoResult(
            String jobId,
            int restoredCells,
            List<UndoPlanner.UndoDecision> conflicts) {
        public UndoResult {
            Objects.requireNonNull(jobId, "jobId");
            conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
            if (restoredCells < 0) {
                throw new IllegalArgumentException("restoredCells must not be negative");
            }
        }
    }

    private final class UndoCommitLog implements FabricMutationExecutor.CommitLog {
        private final Set<String> committedOperations = ConcurrentHashMap.newKeySet();

        @Override
        public boolean isCommitted(String operationId) {
            return committedOperations.contains(operationId);
        }

        @Override
        public CompletableFuture<Void> commit(OperationIntent intent) {
            return checkpoint().thenRun(() -> committedOperations.add(intent.operationId()));
        }
    }
}
