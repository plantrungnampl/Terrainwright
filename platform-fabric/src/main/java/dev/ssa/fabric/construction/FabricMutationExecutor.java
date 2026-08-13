package dev.ssa.fabric.construction;

import dev.ssa.construction.operation.DropPolicy;
import dev.ssa.construction.operation.EvidenceObservation;
import dev.ssa.construction.operation.InventoryDelta;
import dev.ssa.construction.operation.OperationDelta;
import dev.ssa.construction.operation.OperationIntent;
import dev.ssa.construction.operation.OperationKind;
import dev.ssa.construction.operation.OperationRecoveryClassifier;
import dev.ssa.construction.operation.OperationStatus;
import dev.ssa.construction.operation.ObservedEvidence;
import dev.ssa.construction.operation.RecoveryAction;
import dev.ssa.construction.operation.RecoveryDecision;
import dev.ssa.construction.operation.WorldDelta;
import dev.ssa.common.permission.PermissionPort;
import dev.ssa.fabric.persistence.OperationIntentStore;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;

public final class FabricMutationExecutor {
    private final OperationIntentStore store;
    private final Executor serverExecutor;
    private final OperationBoundaryListener listener;
    private final OperationRecoveryClassifier classifier = new OperationRecoveryClassifier();

    public FabricMutationExecutor(
            OperationIntentStore store,
            Executor serverExecutor,
            OperationBoundaryListener listener) {
        this.store = Objects.requireNonNull(store, "store");
        this.serverExecutor = Objects.requireNonNull(serverExecutor, "serverExecutor");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public CompletableFuture<CoordinatorResult> execute(OperationIntent intent, OperationEvidencePort evidence) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(evidence, "evidence");
        return CompletableFuture.runAsync(() -> {
                    listener.beforePrepare();
                    evidence.validate(intent);
                    RecoveryDecision before = classifier.classify(intent, evidence.observe(intent));
                    if (before.action() != RecoveryAction.ABORT_PREPARED) {
                        throw new IllegalStateException("operation evidence is not exact all-before state");
                    }
                }, serverExecutor)
                .thenCompose(ignored -> store.prepare(intent))
                .thenCompose(acknowledgement -> CompletableFuture.runAsync(() -> {
                    listener.afterPrepared(acknowledgement);
                    for (int index = 0; index < intent.deltas().size(); index++) {
                        evidence.apply(intent.deltas().get(index));
                        listener.afterDelta(index);
                    }
                    listener.afterAllDeltas();
                    RecoveryDecision after = classifier.classify(intent, evidence.observe(intent));
                    if (after.action() != RecoveryAction.FINALIZE_COMMIT) {
                        throw new IllegalStateException("operation did not produce exact all-after evidence");
                    }
                    evidence.commit(intent);
                    listener.afterJournalCommit();
                }, serverExecutor))
                .thenCompose(ignored -> store.transition(intent.operationId(), OperationStatus.COMMITTED))
                .thenCompose(ignored -> CompletableFuture.runAsync(listener::afterCommit, serverExecutor))
                .thenCompose(ignored -> store.clear(intent.operationId()))
                .thenApplyAsync(ignored -> {
                    listener.afterClear();
                    return new CoordinatorResult(CoordinatorOutcome.COMMITTED, intent.deltas().size());
                }, serverExecutor);
    }

    public CompletableFuture<CoordinatorResult> execute(
            OperationIntent intent,
            ServerLevel level,
            UUID owner,
            PermissionPort permissions,
            Collection<BoundInventory> inventories,
            CommitLog commitLog) {
        Objects.requireNonNull(intent, "intent");
        if (intent.kind() != OperationKind.MATERIAL_TRANSFER
                && intent.kind() != OperationKind.WORLD_MUTATION) {
            throw new IllegalArgumentException("unsupported operation kind: " + intent.kind());
        }
        return execute(intent, new FabricEvidence(
                level,
                owner,
                permissions,
                inventories,
                commitLog,
                true));
    }

    public CompletableFuture<CoordinatorResult> recover(OperationEvidencePort evidence) {
        Objects.requireNonNull(evidence, "evidence");
        return store.loadActive().thenCompose(active -> recover(active, evidence));
    }

    public CompletableFuture<CoordinatorResult> recover(
            ServerLevel level,
            UUID owner,
            PermissionPort permissions,
            Collection<BoundInventory> inventories,
            CommitLog commitLog) {
        return recover(new FabricEvidence(
                level,
                owner,
                permissions,
                inventories,
                commitLog,
                false));
    }

    private CompletableFuture<CoordinatorResult> recover(
            Optional<OperationIntent> active,
            OperationEvidencePort evidence) {
        if (active.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new CoordinatorResult(CoordinatorOutcome.NO_ACTIVE_INTENT, 0));
        }
        OperationIntent intent = active.orElseThrow();
        return CompletableFuture.supplyAsync(
                        () -> classifier.classify(intent, evidence.observe(intent)),
                        serverExecutor)
                .thenCompose(decision -> recover(intent, evidence, decision));
    }

    private CompletableFuture<CoordinatorResult> recover(
            OperationIntent intent,
            OperationEvidencePort evidence,
            RecoveryDecision decision) {
        return switch (decision.action()) {
            case ABORT_PREPARED -> intent.status() == OperationStatus.ABORTED
                    ? clear(intent, new CoordinatorResult(CoordinatorOutcome.ABORTED, 0))
                    : terminalAndClear(
                            intent,
                            OperationStatus.ABORTED,
                            new CoordinatorResult(CoordinatorOutcome.ABORTED, 0));
            case COMPLETE_SUFFIX -> completeSuffix(intent, evidence, decision.completedPrefixLength());
            case FINALIZE_COMMIT -> finalizeCommit(intent, evidence, decision.completedPrefixLength());
            case QUARANTINE_UNKNOWN_EVIDENCE -> intent.status() == OperationStatus.QUARANTINED
                    ? CompletableFuture.completedFuture(
                            new CoordinatorResult(CoordinatorOutcome.QUARANTINED, 0))
                    : store.transition(intent.operationId(), OperationStatus.QUARANTINED)
                            .thenApply(ignored -> new CoordinatorResult(CoordinatorOutcome.QUARANTINED, 0));
        };
    }

    private CompletableFuture<CoordinatorResult> completeSuffix(
            OperationIntent intent,
            OperationEvidencePort evidence,
            int completedPrefixLength) {
        return CompletableFuture.runAsync(() -> {
                    for (int index = completedPrefixLength; index < intent.deltas().size(); index++) {
                        evidence.apply(intent.deltas().get(index));
                    }
                    RecoveryDecision verification = classifier.classify(intent, evidence.observe(intent));
                    if (verification.action() != RecoveryAction.FINALIZE_COMMIT) {
                        throw new IllegalStateException("completed suffix did not produce exact all-after evidence");
                    }
                    if (!evidence.isCommitted(intent.operationId())) {
                        evidence.commit(intent);
                    }
                }, serverExecutor)
                .thenCompose(ignored -> terminalAndClear(
                        intent,
                        OperationStatus.COMMITTED,
                        new CoordinatorResult(CoordinatorOutcome.COMMITTED, intent.deltas().size())));
    }

    private CompletableFuture<CoordinatorResult> finalizeCommit(
            OperationIntent intent,
            OperationEvidencePort evidence,
            int completedPrefixLength) {
        return CompletableFuture.runAsync(() -> {
                    if (!evidence.isCommitted(intent.operationId())) {
                        evidence.commit(intent);
                    }
                }, serverExecutor)
                .thenCompose(ignored -> intent.status() == OperationStatus.COMMITTED
                        ? clear(intent, new CoordinatorResult(CoordinatorOutcome.COMMITTED, completedPrefixLength))
                        : terminalAndClear(
                                intent,
                                OperationStatus.COMMITTED,
                                new CoordinatorResult(CoordinatorOutcome.COMMITTED, completedPrefixLength)));
    }

    private CompletableFuture<CoordinatorResult> terminalAndClear(
            OperationIntent intent,
            OperationStatus terminalStatus,
            CoordinatorResult result) {
        return store.transition(intent.operationId(), terminalStatus)
                .thenCompose(ignored -> clear(intent, result));
    }

    private CompletableFuture<CoordinatorResult> clear(OperationIntent intent, CoordinatorResult result) {
        return store.clear(intent.operationId()).thenApply(ignored -> result);
    }

    public record BoundInventory(String inventoryId, int bindingRevision, Container container) {
        public BoundInventory {
            Objects.requireNonNull(inventoryId, "inventoryId");
            Objects.requireNonNull(container, "container");
            if (inventoryId.isBlank() || inventoryId.length() > 160) {
                throw new IllegalArgumentException("inventoryId must contain 1 to 160 characters");
            }
            if (bindingRevision < 0) {
                throw new IllegalArgumentException("bindingRevision must not be negative");
            }
        }
    }

    public interface CommitLog {
        boolean isCommitted(String operationId);

        void commit(OperationIntent intent);
    }

    private static final class FabricEvidence implements OperationEvidencePort {
        private final ServerLevel level;
        private final UUID owner;
        private final PermissionPort permissions;
        private final Map<String, BoundInventory> inventories;
        private final CommitLog commitLog;
        private final MinecraftSnapshotAdapter snapshots;
        private final boolean enforcePermissionOnApply;

        private FabricEvidence(
                ServerLevel level,
                UUID owner,
                PermissionPort permissions,
                Collection<BoundInventory> inventories,
                CommitLog commitLog,
                boolean enforcePermissionOnApply) {
            this.level = Objects.requireNonNull(level, "level");
            this.owner = Objects.requireNonNull(owner, "owner");
            this.permissions = Objects.requireNonNull(permissions, "permissions");
            this.commitLog = Objects.requireNonNull(commitLog, "commitLog");
            this.snapshots = new MinecraftSnapshotAdapter(level.registryAccess());
            this.enforcePermissionOnApply = enforcePermissionOnApply;
            Map<String, BoundInventory> byId = new HashMap<>();
            for (BoundInventory inventory : Objects.requireNonNull(inventories, "inventories")) {
                if (byId.putIfAbsent(inventory.inventoryId(), inventory) != null) {
                    throw new IllegalArgumentException("duplicate inventory identity: " + inventory.inventoryId());
                }
            }
            this.inventories = Map.copyOf(byId);
        }

        @Override
        public void validate(OperationIntent intent) {
            for (OperationDelta delta : intent.deltas()) {
                if (delta instanceof InventoryDelta inventoryDelta) {
                    inventory(inventoryDelta);
                    continue;
                }
                WorldDelta worldDelta = (WorldDelta) delta;
                BlockPos position = position(worldDelta);
                if (!worldDelta.before().blockId().equals("minecraft:air")
                        && worldDelta.dropPolicy() != DropPolicy.SUPPRESS) {
                    throw new IllegalStateException("non-air world replacement must suppress drops: " + position);
                }
                if (!level.isLoaded(position)) {
                    throw new IllegalStateException("world mutation target is not loaded: " + position);
                }
                if (!permissions.canModify(owner, gridPos(position))) {
                    throw new SecurityException("owner cannot modify world mutation target: " + position);
                }
            }
        }

        @Override
        public ObservedEvidence observe(OperationIntent intent) {
            return new ObservedEvidence(intent.deltas().stream()
                    .map(this::observe)
                    .toList());
        }

        private EvidenceObservation observe(OperationDelta delta) {
            if (delta instanceof InventoryDelta inventoryDelta) {
                BoundInventory inventory = inventory(inventoryDelta);
                return new EvidenceObservation(
                        delta.evidenceKey(),
                        snapshots.snapshot(inventory.container().getItem(inventoryDelta.slot())));
            }
            WorldDelta worldDelta = (WorldDelta) delta;
            BlockPos position = position(worldDelta);
            if (!level.isLoaded(position)) {
                throw new IllegalStateException("world mutation target unloaded during operation: " + position);
            }
            return new EvidenceObservation(delta.evidenceKey(), snapshots.snapshot(level.getBlockState(position)));
        }

        @Override
        public void apply(OperationDelta delta) {
            if (delta instanceof InventoryDelta inventoryDelta) {
                BoundInventory inventory = inventory(inventoryDelta);
                if (!snapshots.snapshot(inventory.container().getItem(inventoryDelta.slot()))
                        .equals(inventoryDelta.before())) {
                    throw new IllegalStateException("inventory slot changed before mutation: " + delta.evidenceKey());
                }
                inventory.container().setItem(inventoryDelta.slot(), snapshots.restore(inventoryDelta.after()));
                inventory.container().setChanged();
                return;
            }
            WorldDelta worldDelta = (WorldDelta) delta;
            BlockPos position = position(worldDelta);
            if (enforcePermissionOnApply && !permissions.canModify(owner, gridPos(position))) {
                throw new SecurityException("permission changed before world mutation: " + position);
            }
            if (!snapshots.snapshot(level.getBlockState(position)).equals(worldDelta.before())) {
                throw new IllegalStateException("world state changed before mutation: " + delta.evidenceKey());
            }
            if (!level.setBlock(position, snapshots.restore(worldDelta.after()), Block.UPDATE_ALL)) {
                throw new IllegalStateException("world mutation was rejected: " + position);
            }
        }

        @Override
        public boolean isCommitted(String operationId) {
            return commitLog.isCommitted(operationId);
        }

        @Override
        public void commit(OperationIntent intent) {
            commitLog.commit(intent);
        }

        private BoundInventory inventory(InventoryDelta delta) {
            BoundInventory inventory = inventories.get(delta.inventoryId());
            if (inventory == null || inventory.bindingRevision() != delta.bindingRevision()) {
                throw new IllegalStateException("inventory binding does not match intent: " + delta.evidenceKey());
            }
            if (delta.slot() >= inventory.container().getContainerSize()) {
                throw new IllegalStateException("inventory slot is outside bound container: " + delta.evidenceKey());
            }
            return inventory;
        }

        private BlockPos position(WorldDelta delta) {
            String levelId = level.dimension().identifier().toString();
            if (!levelId.equals(delta.worldId())) {
                throw new IllegalStateException("world identity does not match intent: " + delta.evidenceKey());
            }
            return new BlockPos(delta.x(), delta.y(), delta.z());
        }

        private static dev.ssa.architect.model.GridPos gridPos(BlockPos position) {
            return new dev.ssa.architect.model.GridPos(position.getX(), position.getY(), position.getZ());
        }
    }
}
