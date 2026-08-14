package dev.ssa.fabric.builder;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.common.permission.PermissionPort;
import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.job.BuildJob.Diagnostic;
import dev.ssa.construction.job.BuildJob.Severity;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.construction.journal.JournalEntry;
import dev.ssa.construction.material.WorkBatchPlanner;
import dev.ssa.construction.material.WorkBatchPlanner.WorkBatch;
import dev.ssa.construction.operation.DropPolicy;
import dev.ssa.construction.operation.InventoryDelta;
import dev.ssa.construction.operation.OperationIntent;
import dev.ssa.construction.operation.OperationKind;
import dev.ssa.construction.operation.StackSnapshot;
import dev.ssa.construction.operation.WorldDelta;
import dev.ssa.construction.plan.TaskGraph;
import dev.ssa.construction.scaffold.ScaffoldPlan;
import dev.ssa.construction.scaffold.ScaffoldProvenance;
import dev.ssa.construction.scaffold.ScaffoldProvenance.Cell;
import dev.ssa.construction.task.BuildTask;
import dev.ssa.construction.task.TaskOperation;
import dev.ssa.fabric.construction.CoordinatorOutcome;
import dev.ssa.fabric.construction.CoordinatorResult;
import dev.ssa.fabric.construction.FabricMutationExecutor;
import dev.ssa.fabric.construction.MaterialTransferService;
import dev.ssa.fabric.construction.MinecraftSnapshotAdapter;
import dev.ssa.fabric.entity.BuilderEntity;
import dev.ssa.fabric.link.BuilderChestLinkService;
import dev.ssa.fabric.link.ContainerBinding;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

public final class BuilderController {
    private static final int MAXIMUM_BATCH_TASKS = 16;
    private static final int MAXIMUM_BATCH_ITEMS = 64;
    private static final int MATERIAL_RECHECK_TICKS = 20;
    private static final int PLACEMENT_COOLDOWN_TICKS = 8;
    private static final int BUILDER_INVENTORY_REVISION = 1;

    private final BuilderEntity builder;
    private final FabricMutationExecutor mutations;
    private final MaterialTransferService transfers;
    private final ServerBuildJobRepository repository;
    private final BuilderChestLinkService links;
    private final PermissionPort permissions;
    private final Function<ServerLevel, CompletableFuture<Void>> recoveryCheckpoint;
    private final BuilderStateMachine stateMachine = new BuilderStateMachine();
    private final WorkBatchPlanner batchPlanner = new WorkBatchPlanner();
    private final FabricNavigationAdapter navigation;
    private final RecoveryController navigationRecovery = new RecoveryController(2);
    private final FabricScaffoldPlanner scaffoldPlanner = new FabricScaffoldPlanner();

    private WorkOrder order;
    private JobCommitLog commitLog;
    private List<BuildTask> batchTasks = List.of();
    private Map<Item, Integer> batchMaterials = Map.of();
    private int batchIndex;
    private long nextMaterialCheckTick;
    private long nextPlacementTick;
    private CompletableFuture<CoordinatorResult> inFlight;
    private InFlightAction inFlightAction;
    private String failureReason = "";
    private ScaffoldProvenance activeScaffold;
    private int scaffoldCellIndex = -1;
    private ScaffoldMode scaffoldMode;

    public BuilderController(
            BuilderEntity builder,
            FabricMutationExecutor mutations,
            MaterialTransferService transfers,
            ServerBuildJobRepository repository,
            BuilderChestLinkService links,
            PermissionPort permissions) {
        this(
                builder,
                mutations,
                transfers,
                repository,
                links,
                permissions,
                ignored -> CompletableFuture.completedFuture(null));
    }

    public BuilderController(
            BuilderEntity builder,
            FabricMutationExecutor mutations,
            MaterialTransferService transfers,
            ServerBuildJobRepository repository,
            BuilderChestLinkService links,
            PermissionPort permissions,
            Function<ServerLevel, CompletableFuture<Void>> recoveryCheckpoint) {
        this.builder = Objects.requireNonNull(builder, "builder");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.links = Objects.requireNonNull(links, "links");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.recoveryCheckpoint = Objects.requireNonNull(recoveryCheckpoint, "recoveryCheckpoint");
        this.navigation = new FabricNavigationAdapter(builder, new InteractionPositionResolver());
    }

    public void assign(WorkOrder order) {
        if (this.order != null || stateMachine.state() != BuilderStateMachine.State.IDLE) {
            throw new IllegalStateException("Builder already has active work");
        }
        this.order = Objects.requireNonNull(order, "order");
        BuildJob job = job();
        order.taskGraph().frontier(job.completedTaskIds());
        repository.savePlan(job.jobId(), order.taskGraph());
        this.commitLog = new JobCommitLog(order.taskGraph());
        stateMachine.transition(BuilderStateMachine.State.RECOVERING);
    }

    public void tick(ServerLevel level) {
        if (order == null || stateMachine.state() == BuilderStateMachine.State.IDLE) {
            return;
        }
        Objects.requireNonNull(level, "level");
        if (builder.level() != level) {
            block("Builder controller ticked in the wrong level", Optional.empty());
            return;
        }
        if (finishInFlight(level)) {
            return;
        }
        BuildJobState durableState = job().state();
        if ((durableState == BuildJobState.NO_BUILDER
                        || durableState == BuildJobState.ORPHANED
                        || durableState == BuildJobState.QUARANTINED_RECOVERY)
                && stateMachine.state() != BuilderStateMachine.State.RECOVERING) {
            return;
        }

        switch (stateMachine.state()) {
            case RECOVERING -> startRecovery(level);
            case CHECK_MATERIALS -> checkMaterials(level);
            case WAIT_MATERIAL -> {
                if (level.getGameTime() >= nextMaterialCheckTick) {
                    stateMachine.transition(BuilderStateMachine.State.CHECK_MATERIALS);
                }
            }
            case NAVIGATE_CHEST -> tickChestNavigation(level);
            case FETCH_MATERIAL -> startNextTransfer(level);
            case NAVIGATE_SITE -> tickSiteNavigation(level);
            case EXECUTE_TASK -> {
                if (level.getGameTime() >= nextPlacementTick) {
                    startPlacement(level);
                }
            }
            case NAVIGATE_SCAFFOLD -> tickScaffoldNavigation(level);
            case EXECUTE_SCAFFOLD -> startScaffoldMutation(level);
            case SELECT_NEXT_TASK -> selectNextTask(level);
            case NO_CHEST, SUSPENDED_CHUNK_UNLOADED -> resumeIfAvailable(level);
            case BLOCKED, IDLE -> {
            }
        }
    }

    public BuilderStateMachine.State state() {
        return stateMachine.state();
    }

    public List<BuilderStateMachine.State> stateHistory() {
        return stateMachine.history();
    }

    public String failureReason() {
        return failureReason;
    }

    private boolean finishInFlight(ServerLevel level) {
        if (inFlight == null || !inFlight.isDone()) {
            return inFlight != null;
        }
        InFlightAction completedAction = inFlightAction;
        try {
            CoordinatorResult result = inFlight.join();
            inFlight = null;
            inFlightAction = null;
            if (result.outcome() == CoordinatorOutcome.QUARANTINED) {
                BuildJob current = job();
                if (current.state().canTransitionTo(BuildJobState.QUARANTINED_RECOVERY)) {
                    saveJob(current.recordDiagnosticAndTransition(
                            new Diagnostic(
                                    "OPERATION_INTENT_RECOVERY_QUARANTINED",
                                    Severity.ERROR,
                                    "OperationIntent recovery found unknown evidence",
                                    true,
                                    current.revision() + 1,
                                    currentTaskPosition()),
                            BuildJobState.QUARANTINED_RECOVERY));
                }
                block("OperationIntent recovery quarantined unknown evidence", currentTaskPosition());
                return true;
            }
            switch (completedAction) {
                case RECOVERY -> beginRecoveryCheckpoint(level);
                case RECOVERY_CHECKPOINT -> prepareJobAfterRecovery(level);
                case TRANSFER -> {
                    // Recompute the remaining exact material need on the next server tick.
                }
                case PLACEMENT -> {
                    nextPlacementTick = level.getGameTime() + PLACEMENT_COOLDOWN_TICKS;
                    if (hasPlacedScaffoldCells()) {
                        beginScaffoldCleanup(level);
                    } else {
                        stateMachine.transition(BuilderStateMachine.State.SELECT_NEXT_TASK);
                    }
                }
                case SCAFFOLD_PLACE -> continueScaffoldPlacement(level);
                case SCAFFOLD_REMOVE -> continueScaffoldCleanup(level);
                case SCAFFOLD_PLAN -> stateMachine.transition(BuilderStateMachine.State.CHECK_MATERIALS);
            }
            return inFlight != null;
        } catch (RuntimeException failure) {
            inFlight = null;
            inFlightAction = null;
            if (hasCause(failure, MissingLinkedContainerException.class)) {
                suspendForMissingChest(level);
                return true;
            }
            block("OperationIntent failed: " + rootMessage(failure), currentTaskPosition());
            return true;
        }
    }

    private void startRecovery(ServerLevel level) {
        BuildJob job = job();
        inFlight = mutations.recover(
                level,
                owner(job),
                permissions,
                intent -> recoveryInventories(level, intent),
                commitLog);
        inFlightAction = InFlightAction.RECOVERY;
    }

    private void beginRecoveryCheckpoint(ServerLevel level) {
        BuildJob current = job();
        if (current.state() == BuildJobState.NO_BUILDER
                || current.state() == BuildJobState.ORPHANED
                || current.state() == BuildJobState.QUARANTINED_RECOVERY) {
            stateMachine.transition(BuilderStateMachine.State.BLOCKED);
            return;
        }
        ContainerBinding binding = order.chestBinding();
        boolean projectComplete = current.completedTaskIds().size() == order.taskGraph().tasks().size();
        if (!projectComplete
                && (!level.isLoaded(binding.primaryPos()) || !links.isTransferEligible(level, binding))) {
            stateMachine.transition(BuilderStateMachine.State.SUSPENDED_CHUNK_UNLOADED);
            return;
        }
        inFlight = recoveryCheckpoint.apply(level)
                .thenApply(ignored -> new CoordinatorResult(CoordinatorOutcome.NO_ACTIVE_INTENT, 0));
        inFlightAction = InFlightAction.RECOVERY_CHECKPOINT;
    }

    private void prepareJobAfterRecovery(ServerLevel level) {
        BuildJob current = job();
        if (current.state() == BuildJobState.COMPLETED) {
            stateMachine.transition(BuilderStateMachine.State.CHECK_MATERIALS);
            finishCompletedJob();
            return;
        }
        if (current.state() == BuildJobState.IDLE) {
            saveJob(current.transitionTo(BuildJobState.PREPARING));
        } else if (current.state() == BuildJobState.PAUSED_NO_CHEST
                || current.state() == BuildJobState.SUSPENDED_CHUNK_UNLOADED) {
            saveJob(current.transitionTo(BuildJobState.PREPARING));
        }
        List<ScaffoldProvenance> activeScaffolds = repository.scaffolds().values().stream()
                .filter(scaffold -> scaffold.jobId().equals(current.jobId()) && !scaffold.isCleaned())
                .sorted(java.util.Comparator.comparing(ScaffoldProvenance::planId))
                .toList();
        if (activeScaffolds.size() > 1) {
            block("BuildJob has ambiguous active scaffold provenance", Optional.empty());
            return;
        }
        activeScaffold = activeScaffolds.isEmpty() ? null : activeScaffolds.getFirst();
        stateMachine.transition(BuilderStateMachine.State.CHECK_MATERIALS);
        if (activeScaffold != null
                && job().completedTaskIds().contains(activeScaffold.taskId())
                && hasPlacedScaffoldCells()) {
            beginScaffoldCleanup(level);
        }
    }

    private void checkMaterials(ServerLevel level) {
        BuildJob current = job();
        if (current.completedTaskIds().size() == order.taskGraph().tasks().size()) {
            finishCompletedJob();
            return;
        }
        Optional<WorkBatch> planned = batchPlanner.plan(
                order.taskGraph().frontier(current.completedTaskIds()),
                MAXIMUM_BATCH_TASKS,
                MAXIMUM_BATCH_ITEMS);
        if (planned.isEmpty()) {
            block("No eligible Builder task remained before project completion", Optional.empty());
            return;
        }
        WorkBatch batch = planned.orElseThrow();
        batchTasks = batch.taskIds().stream().map(order.taskGraph()::task).toList();
        batchIndex = 0;
        if (activeScaffold != null && !activeScaffold.taskId().equals(currentTask().id())) {
            block(
                    "Active scaffold belongs to a different Builder task: " + activeScaffold.taskId(),
                    currentTaskPosition());
            return;
        }
        for (BuildTask task : batchTasks) {
            if (task.operation() != TaskOperation.PLACE || task.atomicGroupId().isPresent()) {
                block("Task 15 supports only non-atomic PLACE tasks: " + task.id(), Optional.of(task.position()));
                return;
            }
        }
        batchMaterials = materialCounts(batch.materialCounts());
        if (hasUnplacedScaffoldCells()) {
            Map<Item, Integer> withScaffold = new LinkedHashMap<>(batchMaterials);
            Cell first = activeScaffold.cells().stream()
                    .filter(cell -> cell.placementOperationId().isEmpty())
                    .findFirst()
                    .orElseThrow();
            withScaffold.merge(
                    materialItem(first.state()),
                    (int) activeScaffold.cells().stream()
                            .filter(cell -> cell.placementOperationId().isEmpty())
                            .count(),
                    Integer::sum);
            batchMaterials = Map.copyOf(withScaffold);
        }
        Map<Item, Integer> additional = additionalMaterialCounts();
        if (additional.isEmpty()) {
            navigateToPendingWork(level);
            return;
        }
        Optional<Container> chest = linkedContainer(level);
        if (chest.isEmpty()) {
            suspendForMissingChest(level);
            return;
        }
        if (!canSupplyExactBundle(chest.orElseThrow(), additional)) {
            enterWaitMaterial(level);
            return;
        }
        transitionJobTo(BuildJobState.FETCHING_MATERIAL);
        stateMachine.transition(BuilderStateMachine.State.NAVIGATE_CHEST);
        navigation.begin(level, order.chestBinding().primaryPos());
    }

    private void tickChestNavigation(ServerLevel level) {
        switch (navigation.tick(level)) {
            case MOVING -> {
            }
            case ARRIVED -> stateMachine.transition(BuilderStateMachine.State.FETCH_MATERIAL);
            case BLOCKED -> block(
                    "No legal path to linked Builder Chest: " + navigation.diagnostic(),
                    Optional.empty());
            case SUSPENDED_CHUNK_UNLOADED -> suspendForChunkUnload(level);
        }
    }

    private void startNextTransfer(ServerLevel level) {
        Map<Item, Integer> additional = additionalMaterialCounts();
        if (additional.isEmpty()) {
            navigateToPendingWork(level);
            return;
        }
        Optional<Container> optionalChest = linkedContainer(level);
        if (optionalChest.isEmpty()) {
            suspendForMissingChest(level);
            return;
        }
        Container chest = optionalChest.orElseThrow();
        Item neededItem = additional.keySet().iterator().next();
        int remaining = additional.get(neededItem);
        TransferSlots slots = findTransferSlots(chest, neededItem, remaining)
                .orElseThrow(() -> new IllegalStateException("Preflight material bundle changed before transfer"));
        BuildJob current = job();
        inFlight = transfers.transfer(
                "builder-transfer-" + UUID.randomUUID(),
                current.jobId(),
                Optional.of(currentTask().id()),
                current.revision(),
                level,
                owner(current),
                permissions,
                chestBinding(chest),
                slots.sourceSlot(),
                builderBinding(),
                slots.destinationSlot(),
                slots.count(),
                commitLog);
        inFlightAction = InFlightAction.TRANSFER;
    }

    private void navigateToPendingWork(ServerLevel level) {
        if (hasUnplacedScaffoldCells()) {
            beginScaffoldPlacement(level);
        } else if (activeScaffold != null && hasPlacedScaffoldCells()) {
            navigateToCurrentTaskFromScaffold(level);
        } else {
            navigateToCurrentTask(level);
        }
    }

    private void navigateToCurrentTask(ServerLevel level) {
        startCurrentTaskNavigation(level, Optional.empty());
    }

    private void navigateToCurrentTaskFromScaffold(ServerLevel level) {
        if (!activeScaffold.taskId().equals(currentTask().id())) {
            block(
                    "Active scaffold belongs to a different Builder task: " + activeScaffold.taskId(),
                    currentTaskPosition());
            return;
        }
        Cell top = activeScaffold.cells().getLast();
        startCurrentTaskNavigation(level, Optional.of(scaffoldPosition(top).above()));
    }

    private void startCurrentTaskNavigation(ServerLevel level, Optional<BlockPos> scaffoldStandingPosition) {
        if (activeScaffold == null) {
            navigationRecovery.reset();
        }
        BuildJob current = job();
        if (current.state() == BuildJobState.WAIT_MATERIAL) {
            saveJob(current.transitionTo(BuildJobState.FETCHING_MATERIAL));
            current = job();
        }
        if (current.state() != BuildJobState.NAVIGATING) {
            saveJob(current.transitionTo(BuildJobState.NAVIGATING));
        }
        if (stateMachine.state() == BuilderStateMachine.State.CHECK_MATERIALS
                || stateMachine.state() == BuilderStateMachine.State.FETCH_MATERIAL
                || stateMachine.state() == BuilderStateMachine.State.EXECUTE_SCAFFOLD) {
            stateMachine.transition(BuilderStateMachine.State.NAVIGATE_SITE);
        } else if (stateMachine.state() == BuilderStateMachine.State.SELECT_NEXT_TASK) {
            stateMachine.transition(BuilderStateMachine.State.NAVIGATE_SITE);
        }
        BlockPos target = absolutePosition(job(), currentTask().position());
        if (scaffoldStandingPosition.isPresent()) {
            navigationRecovery.reset();
            navigation.beginFromScaffold(level, target, scaffoldStandingPosition.orElseThrow());
        } else {
            navigation.begin(level, target);
        }
    }

    private void tickSiteNavigation(ServerLevel level) {
        switch (navigation.tick(level)) {
            case MOVING -> {
            }
            case ARRIVED -> {
                transitionJobTo(BuildJobState.BUILDING);
                stateMachine.transition(BuilderStateMachine.State.EXECUTE_TASK);
            }
            case BLOCKED -> recoverSiteNavigation(level);
            case SUSPENDED_CHUNK_UNLOADED -> suspendForChunkUnload(level);
        }
    }

    private void recoverSiteNavigation(ServerLevel level) {
        BlockPos target = absolutePosition(job(), currentTask().position());
        if (activeScaffold != null && hasPlacedScaffoldCells()) {
            block(
                    "Builder remained unreachable after bounded scaffolding: " + navigation.diagnostic(),
                    currentTaskPosition());
            return;
        }
        Optional<ScaffoldPlan> plan = scaffoldPlanner.plan(level, builder, target);
        switch (navigationRecovery.next(plan.isPresent())) {
            case RETRY_ROUTE -> navigation.begin(level, target);
            case LOCAL_RESET -> {
                builder.getNavigation().stop();
                navigation.begin(level, target);
            }
            case SCAFFOLD -> {
                ScaffoldProvenance provenance = ScaffoldProvenance.planned(
                        job().jobId(),
                        "scaffold-" + UUID.randomUUID(),
                        currentTask().id(),
                        plan.orElseThrow());
                repository.saveScaffold(provenance);
                activeScaffold = provenance;
                inFlight = level.getDataStorage().scheduleSave()
                        .thenApply(ignored -> new CoordinatorResult(CoordinatorOutcome.NO_ACTIVE_INTENT, 0));
                inFlightAction = InFlightAction.SCAFFOLD_PLAN;
            }
            case BLOCKED -> block(
                    "No legal interaction position after bounded recovery: " + navigation.diagnostic(),
                    currentTaskPosition());
        }
    }

    private void beginScaffoldPlacement(ServerLevel level) {
        activeScaffold = reloadActiveScaffold();
        scaffoldMode = ScaffoldMode.PLACE;
        scaffoldCellIndex = firstUnplacedScaffoldCell();
        transitionJobTo(BuildJobState.NAVIGATING);
        stateMachine.transition(BuilderStateMachine.State.NAVIGATE_SCAFFOLD);
        navigation.begin(level, scaffoldPosition(activeScaffold.cells().get(scaffoldCellIndex)));
    }

    private void beginScaffoldCleanup(ServerLevel level) {
        activeScaffold = reloadActiveScaffold();
        scaffoldMode = ScaffoldMode.REMOVE;
        scaffoldCellIndex = lastPlacedScaffoldCell();
        stateMachine.transition(BuilderStateMachine.State.NAVIGATE_SCAFFOLD);
        navigation.begin(level, scaffoldPosition(activeScaffold.cells().get(scaffoldCellIndex)));
    }

    private void tickScaffoldNavigation(ServerLevel level) {
        switch (navigation.tick(level)) {
            case MOVING -> {
            }
            case ARRIVED -> stateMachine.transition(BuilderStateMachine.State.EXECUTE_SCAFFOLD);
            case BLOCKED -> block(
                    "Builder could not reach temporary scaffold cell: " + navigation.diagnostic(),
                    Optional.of(activeScaffold.cells().get(scaffoldCellIndex).position()));
            case SUSPENDED_CHUNK_UNLOADED -> suspendForChunkUnload(level);
        }
    }

    private void startScaffoldMutation(ServerLevel level) {
        if (scaffoldMode == ScaffoldMode.PLACE) {
            startScaffoldPlacement(level);
        } else if (scaffoldMode == ScaffoldMode.REMOVE) {
            startScaffoldRemoval(level);
        } else {
            throw new IllegalStateException("Builder has no scaffold mutation mode");
        }
    }

    private void startScaffoldPlacement(ServerLevel level) {
        Cell cell = activeScaffold.cells().get(scaffoldCellIndex);
        Item item = materialItem(cell.state());
        int inventorySlot = findBuilderMaterialSlot(item)
                .orElseThrow(() -> new IllegalStateException("Builder lost temporary scaffold material"));
        BlockPos position = scaffoldPosition(cell);
        BlockState beforeState = level.getBlockState(position);
        if (!beforeState.isAir()) {
            block("Temporary scaffold target contains a foreign block", Optional.of(cell.position()));
            return;
        }
        MinecraftSnapshotAdapter snapshots = new MinecraftSnapshotAdapter(level.registryAccess());
        ItemStack beforeStack = builder.carriedItems().getItem(inventorySlot).copy();
        ItemStack afterStack = beforeStack.getCount() == 1
                ? ItemStack.EMPTY
                : beforeStack.copyWithCount(beforeStack.getCount() - 1);
        OperationIntent intent = OperationIntent.prepared(
                "scaffold-place-" + UUID.randomUUID(),
                job().jobId(),
                Optional.of(scaffoldTaskId(ScaffoldMode.PLACE, scaffoldCellIndex)),
                Optional.empty(),
                job().revision(),
                OperationKind.WORLD_MUTATION,
                List.of(
                        new InventoryDelta(
                                builderInventoryId(),
                                BUILDER_INVENTORY_REVISION,
                                inventorySlot,
                                snapshots.snapshot(beforeStack),
                                snapshots.snapshot(afterStack)),
                        new WorldDelta(
                                level.dimension().identifier().toString(),
                                position.getX(),
                                position.getY(),
                                position.getZ(),
                                snapshots.snapshot(beforeState),
                                snapshots.snapshot(restore(cell.state())),
                                DropPolicy.NOT_APPLICABLE)));
        inFlight = mutations.execute(
                intent, level, owner(job()), permissions, List.of(builderBinding()), commitLog);
        inFlightAction = InFlightAction.SCAFFOLD_PLACE;
    }

    private void startScaffoldRemoval(ServerLevel level) {
        Cell cell = activeScaffold.cells().get(scaffoldCellIndex);
        Item item = materialItem(cell.state());
        int inventorySlot = findBuilderInsertSlot(item)
                .orElseThrow(() -> new IllegalStateException("Builder inventory cannot receive cleaned scaffold"));
        BlockPos position = scaffoldPosition(cell);
        BlockState beforeState = level.getBlockState(position);
        BlockState intendedState = restore(cell.state());
        if (!beforeState.equals(intendedState)) {
            block(
                    "Temporary scaffold state differs at cleanup; actual="
                            + specification(beforeState) + ", intended=" + specification(intendedState),
                    Optional.of(cell.position()));
            return;
        }
        MinecraftSnapshotAdapter snapshots = new MinecraftSnapshotAdapter(level.registryAccess());
        ItemStack beforeStack = builder.carriedItems().getItem(inventorySlot).copy();
        ItemStack afterStack = beforeStack.isEmpty()
                ? new ItemStack(item, 1)
                : beforeStack.copyWithCount(beforeStack.getCount() + 1);
        OperationIntent intent = OperationIntent.prepared(
                "scaffold-remove-" + UUID.randomUUID(),
                job().jobId(),
                Optional.of(scaffoldTaskId(ScaffoldMode.REMOVE, scaffoldCellIndex)),
                Optional.empty(),
                job().revision(),
                OperationKind.WORLD_MUTATION,
                List.of(
                        new InventoryDelta(
                                builderInventoryId(),
                                BUILDER_INVENTORY_REVISION,
                                inventorySlot,
                                snapshots.snapshot(beforeStack),
                                snapshots.snapshot(afterStack)),
                        new WorldDelta(
                                level.dimension().identifier().toString(),
                                position.getX(),
                                position.getY(),
                                position.getZ(),
                                snapshots.snapshot(beforeState),
                                snapshots.snapshot(Blocks.AIR.defaultBlockState()),
                                DropPolicy.SUPPRESS)));
        inFlight = mutations.execute(
                intent, level, owner(job()), permissions, List.of(builderBinding()), commitLog);
        inFlightAction = InFlightAction.SCAFFOLD_REMOVE;
    }

    private void continueScaffoldPlacement(ServerLevel level) {
        activeScaffold = reloadActiveScaffold();
        if (hasUnplacedScaffoldCells()) {
            beginScaffoldPlacement(level);
            return;
        }
        navigateToCurrentTaskFromScaffold(level);
    }

    private void continueScaffoldCleanup(ServerLevel level) {
        activeScaffold = reloadActiveScaffold();
        if (hasPlacedScaffoldCells()) {
            beginScaffoldCleanup(level);
            return;
        }
        activeScaffold = null;
        scaffoldMode = null;
        scaffoldCellIndex = -1;
        stateMachine.transition(BuilderStateMachine.State.SELECT_NEXT_TASK);
    }

    private void startPlacement(ServerLevel level) {
        BuildTask task = currentTask();
        BuildJob current = job();
        Item material = materialItem(task.materialRequirement().orElseThrow().state());
        int inventorySlot = findBuilderMaterialSlot(material)
                .orElseThrow(() -> new IllegalStateException("Builder lost carried material before placement"));
        BlockPos position = absolutePosition(current, task.position());
        BlockState beforeState = level.getBlockState(position);
        if (!beforeState.isAir()) {
            block(
                    "PLACE task found a non-air foreign block: " + specification(beforeState),
                    Optional.of(task.position()));
            return;
        }
        BlockState afterState = restore(task.materialRequirement().orElseThrow().state());
        MinecraftSnapshotAdapter snapshots = new MinecraftSnapshotAdapter(level.registryAccess());
        ItemStack beforeStack = builder.carriedItems().getItem(inventorySlot).copy();
        ItemStack afterStack = beforeStack.getCount() == 1
                ? ItemStack.EMPTY
                : beforeStack.copyWithCount(beforeStack.getCount() - 1);
        OperationIntent intent = OperationIntent.prepared(
                "builder-place-" + UUID.randomUUID(),
                current.jobId(),
                Optional.of(task.id()),
                task.atomicGroupId(),
                current.revision(),
                OperationKind.WORLD_MUTATION,
                List.of(
                        new InventoryDelta(
                                builderInventoryId(),
                                BUILDER_INVENTORY_REVISION,
                                inventorySlot,
                                snapshots.snapshot(beforeStack),
                                snapshots.snapshot(afterStack)),
                        new WorldDelta(
                                level.dimension().identifier().toString(),
                                position.getX(),
                                position.getY(),
                                position.getZ(),
                                snapshots.snapshot(beforeState),
                                snapshots.snapshot(afterState),
                                DropPolicy.NOT_APPLICABLE)));
        inFlight = mutations.execute(
                intent,
                level,
                owner(current),
                permissions,
                List.of(builderBinding()),
                commitLog);
        inFlightAction = InFlightAction.PLACEMENT;
    }

    private void selectNextTask(ServerLevel level) {
        batchIndex++;
        while (batchIndex < batchTasks.size()
                && job().completedTaskIds().contains(batchTasks.get(batchIndex).id())) {
            batchIndex++;
        }
        if (batchIndex < batchTasks.size()) {
            navigateToCurrentTask(level);
            return;
        }
        stateMachine.transition(BuilderStateMachine.State.CHECK_MATERIALS);
    }

    private void finishCompletedJob() {
        BuildJob current = job();
        if (current.state() != BuildJobState.COMPLETED) {
            saveJob(current.transitionTo(BuildJobState.COMPLETED));
        }
        stateMachine.transition(BuilderStateMachine.State.IDLE);
        order = null;
    }

    private void enterWaitMaterial(ServerLevel level) {
        transitionJobTo(BuildJobState.WAIT_MATERIAL);
        nextMaterialCheckTick = level.getGameTime() + MATERIAL_RECHECK_TICKS;
        stateMachine.transition(BuilderStateMachine.State.WAIT_MATERIAL);
    }

    private void suspendForMissingChest(ServerLevel level) {
        BuildJob current = job();
        if (current.state() != BuildJobState.PAUSED_NO_CHEST) {
            saveJob(current.transitionTo(BuildJobState.PAUSED_NO_CHEST));
        }
        nextMaterialCheckTick = level.getGameTime() + MATERIAL_RECHECK_TICKS;
        stateMachine.transition(BuilderStateMachine.State.NO_CHEST);
        failureReason = "Linked Builder Chest is unavailable or its topology changed";
    }

    private void suspendForChunkUnload(ServerLevel level) {
        transitionJobTo(BuildJobState.SUSPENDED_CHUNK_UNLOADED);
        nextMaterialCheckTick = level.getGameTime() + MATERIAL_RECHECK_TICKS;
        stateMachine.transition(BuilderStateMachine.State.SUSPENDED_CHUNK_UNLOADED);
    }

    private void resumeIfAvailable(ServerLevel level) {
        if (level.getGameTime() < nextMaterialCheckTick
                || (requiresLinkedChest() && linkedContainer(level).isEmpty())) {
            return;
        }
        if (!batchTasks.isEmpty()
                && batchIndex < batchTasks.size()
                && !level.isPositionEntityTicking(absolutePosition(job(), currentTask().position()))) {
            nextMaterialCheckTick = level.getGameTime() + MATERIAL_RECHECK_TICKS;
            return;
        }
        stateMachine.transition(BuilderStateMachine.State.RECOVERING);
    }

    private void block(String reason, Optional<GridPos> position) {
        failureReason = Objects.requireNonNull(reason, "reason");
        if (order != null) {
            BuildJob current = job();
            if (current.state() != BuildJobState.PAUSED_BLOCKED
                    && current.state() != BuildJobState.COMPLETED) {
                Diagnostic diagnostic = new Diagnostic(
                        "BUILDER_TASK_BLOCKED",
                        Severity.ERROR,
                        reason.length() <= 500 ? reason : reason.substring(0, 500),
                        true,
                        current.revision() + 1,
                        position);
                try {
                    saveJob(current.recordDiagnosticAndTransition(diagnostic, BuildJobState.PAUSED_BLOCKED));
                } catch (IllegalStateException ignored) {
                    // The exact controller reason remains available when an interrupt state is already terminal.
                }
            }
        }
        if (stateMachine.state() != BuilderStateMachine.State.BLOCKED) {
            stateMachine.transition(BuilderStateMachine.State.BLOCKED);
        }
        builder.getNavigation().stop();
    }

    private Optional<Container> linkedContainer(ServerLevel level) {
        ContainerBinding binding = order.chestBinding();
        if (!links.isTransferEligible(level, binding) || !level.isLoaded(binding.primaryPos())) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(binding.primaryPos());
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getContainer(
                chestBlock,
                state,
                level,
                binding.primaryPos(),
                false));
    }

    private Collection<FabricMutationExecutor.BoundInventory> recoveryInventories(
            ServerLevel level,
            OperationIntent intent) {
        boolean needsChest = intent.kind() == OperationKind.MATERIAL_TRANSFER
                || intent.deltas().stream()
                        .filter(InventoryDelta.class::isInstance)
                        .map(InventoryDelta.class::cast)
                        .anyMatch(delta -> delta.inventoryId().equals(order.chestBinding().inventoryId().toString()));
        if (!needsChest) {
            return List.of(builderBinding());
        }
        Container chest = linkedContainer(level).orElseThrow(MissingLinkedContainerException::new);
        return boundInventories(chest);
    }

    private boolean requiresLinkedChest() {
        return !batchTasks.isEmpty()
                && batchIndex >= 0
                && batchIndex < batchTasks.size()
                && !additionalMaterialCounts().isEmpty();
    }

    private Map<Item, Integer> materialCounts(
            Map<BuildTask.MaterialRequirement, Integer> requirements) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        requirements.forEach((requirement, count) ->
                counts.merge(materialItem(requirement.state()), count, Integer::sum));
        return Map.copyOf(counts);
    }

    private Map<Item, Integer> additionalMaterialCounts() {
        Map<Item, Integer> additional = new LinkedHashMap<>();
        batchMaterials.forEach((item, required) -> {
            int missing = required - carriedCanonicalItemCount(item);
            if (missing > 0) {
                additional.put(item, missing);
            }
        });
        return additional;
    }

    private boolean canSupplyExactBundle(Container chest, Map<Item, Integer> additional) {
        List<ItemStack> simulated = new ArrayList<>();
        for (int slot = 0; slot < builder.carriedItems().getContainerSize(); slot++) {
            simulated.add(builder.carriedItems().getItem(slot).copy());
        }
        for (Map.Entry<Item, Integer> need : additional.entrySet()) {
            int remaining = need.getValue();
            for (int sourceSlot = 0; sourceSlot < chest.getContainerSize() && remaining > 0; sourceSlot++) {
                ItemStack source = chest.getItem(sourceSlot);
                if (!isCanonicalMaterial(source, need.getKey())) {
                    continue;
                }
                int offered = Math.min(remaining, source.getCount());
                int inserted = simulateInsert(simulated, source, offered);
                remaining -= inserted;
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    private static int simulateInsert(List<ItemStack> inventory, ItemStack source, int count) {
        int remaining = count;
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack destination = inventory.get(slot);
            if (!destination.isEmpty() && ItemStack.isSameItemSameComponents(source, destination)) {
                int accepted = Math.min(remaining, destination.getMaxStackSize() - destination.getCount());
                if (accepted > 0) {
                    inventory.set(slot, destination.copyWithCount(destination.getCount() + accepted));
                    remaining -= accepted;
                }
            }
        }
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            if (inventory.get(slot).isEmpty()) {
                int accepted = Math.min(remaining, source.getMaxStackSize());
                inventory.set(slot, source.copyWithCount(accepted));
                remaining -= accepted;
            }
        }
        return count - remaining;
    }

    private Optional<TransferSlots> findTransferSlots(Container chest, Item item, int requested) {
        for (int sourceSlot = 0; sourceSlot < chest.getContainerSize(); sourceSlot++) {
            ItemStack source = chest.getItem(sourceSlot);
            if (!isCanonicalMaterial(source, item)) {
                continue;
            }
            for (int destinationSlot = 0;
                    destinationSlot < builder.carriedItems().getContainerSize();
                    destinationSlot++) {
                ItemStack destination = builder.carriedItems().getItem(destinationSlot);
                if (!destination.isEmpty() && !ItemStack.isSameItemSameComponents(source, destination)) {
                    continue;
                }
                int room = source.getMaxStackSize() - destination.getCount();
                int count = Math.min(requested, Math.min(source.getCount(), room));
                if (count > 0) {
                    return Optional.of(new TransferSlots(sourceSlot, destinationSlot, count));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Integer> findBuilderMaterialSlot(Item item) {
        for (int slot = 0; slot < builder.carriedItems().getContainerSize(); slot++) {
            if (isCanonicalMaterial(builder.carriedItems().getItem(slot), item)) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    private Optional<Integer> findBuilderInsertSlot(Item item) {
        for (int slot = 0; slot < builder.carriedItems().getContainerSize(); slot++) {
            ItemStack stack = builder.carriedItems().getItem(slot);
            if (isCanonicalMaterial(stack, item) && stack.getCount() < stack.getMaxStackSize()) {
                return Optional.of(slot);
            }
        }
        for (int slot = 0; slot < builder.carriedItems().getContainerSize(); slot++) {
            if (builder.carriedItems().getItem(slot).isEmpty()) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    private boolean hasUnplacedScaffoldCells() {
        return activeScaffold != null && activeScaffold.cells().stream()
                .anyMatch(cell -> cell.placementOperationId().isEmpty());
    }

    private boolean hasPlacedScaffoldCells() {
        return activeScaffold != null && activeScaffold.cells().stream().anyMatch(Cell::isPlaced);
    }

    private int firstUnplacedScaffoldCell() {
        for (int index = 0; index < activeScaffold.cells().size(); index++) {
            if (activeScaffold.cells().get(index).placementOperationId().isEmpty()) {
                return index;
            }
        }
        throw new IllegalStateException("Scaffold plan has no unplaced cell");
    }

    private int lastPlacedScaffoldCell() {
        for (int index = activeScaffold.cells().size() - 1; index >= 0; index--) {
            if (activeScaffold.cells().get(index).isPlaced()) {
                return index;
            }
        }
        throw new IllegalStateException("Scaffold plan has no placed cell");
    }

    private ScaffoldProvenance reloadActiveScaffold() {
        if (activeScaffold == null) {
            throw new IllegalStateException("Builder has no active scaffold provenance");
        }
        return repository.findScaffold(activeScaffold.planId())
                .orElseThrow(() -> new IllegalStateException("Active scaffold provenance is missing"));
    }

    private String scaffoldTaskId(ScaffoldMode mode, int index) {
        return "scaffold:" + activeScaffold.planId() + ":"
                + (mode == ScaffoldMode.PLACE ? "place" : "remove") + ":" + index;
    }

    private static BlockPos scaffoldPosition(Cell cell) {
        return new BlockPos(cell.position().x(), cell.position().y(), cell.position().z());
    }

    private int carriedCanonicalItemCount(Item item) {
        int count = 0;
        for (int slot = 0; slot < builder.carriedItems().getContainerSize(); slot++) {
            ItemStack stack = builder.carriedItems().getItem(slot);
            if (isCanonicalMaterial(stack, item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean isCanonicalMaterial(ItemStack stack, Item item) {
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, new ItemStack(item, 1));
    }

    private Collection<FabricMutationExecutor.BoundInventory> boundInventories(Container chest) {
        return List.of(chestBinding(chest), builderBinding());
    }

    private FabricMutationExecutor.BoundInventory chestBinding(Container chest) {
        return new FabricMutationExecutor.BoundInventory(
                order.chestBinding().inventoryId().toString(),
                Math.toIntExact(order.chestBinding().revision()),
                chest,
                Optional.of(new GridPos(
                        order.chestBinding().primaryPos().getX(),
                        order.chestBinding().primaryPos().getY(),
                        order.chestBinding().primaryPos().getZ())));
    }

    private FabricMutationExecutor.BoundInventory builderBinding() {
        return new FabricMutationExecutor.BoundInventory(
                builderInventoryId(),
                BUILDER_INVENTORY_REVISION,
                builder.carriedItems());
    }

    private String builderInventoryId() {
        return "builder:" + builder.getUUID();
    }

    private BuildTask currentTask() {
        if (batchIndex < 0 || batchIndex >= batchTasks.size()) {
            throw new IllegalStateException("Builder has no current task");
        }
        return batchTasks.get(batchIndex);
    }

    private Optional<GridPos> currentTaskPosition() {
        return batchIndex >= 0 && batchIndex < batchTasks.size()
                ? Optional.of(batchTasks.get(batchIndex).position())
                : Optional.empty();
    }

    private BuildJob job() {
        if (order == null) {
            throw new IllegalStateException("Builder has no work order");
        }
        return repository.findJob(order.jobId())
                .orElseThrow(() -> new IllegalStateException("Builder job is missing: " + order.jobId()));
    }

    private void transitionJobTo(BuildJobState state) {
        BuildJob current = job();
        if (current.state() != state) {
            saveJob(current.transitionTo(state));
        }
    }

    private void saveJob(BuildJob job) {
        repository.saveJob(job);
    }

    private static UUID owner(BuildJob job) {
        return UUID.fromString(job.ownerId());
    }

    private static Item materialItem(BlockStateSpec state) {
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(state.blockId().toString()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown task block: " + state.blockId()));
        Item item = block.asItem();
        if (item == Items.AIR) {
            throw new IllegalArgumentException("Task block has no survival inventory item: " + state.blockId());
        }
        return item;
    }

    private static BlockState restore(BlockStateSpec specification) {
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(specification.blockId().toString()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown task block: " + specification.blockId()));
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

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public record WorkOrder(String jobId, TaskGraph taskGraph, ContainerBinding chestBinding) {
        public WorkOrder {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(taskGraph, "taskGraph");
            Objects.requireNonNull(chestBinding, "chestBinding");
            if (jobId.isBlank() || jobId.length() > 160) {
                throw new IllegalArgumentException("jobId must contain 1 to 160 characters");
            }
        }
    }

    private final class JobCommitLog implements FabricMutationExecutor.CommitLog {
        private final TaskGraph graph;
        private final Set<String> pendingCheckpoints = ConcurrentHashMap.newKeySet();
        private final Set<String> failedCheckpoints = ConcurrentHashMap.newKeySet();
        private final Set<String> durableCheckpoints = ConcurrentHashMap.newKeySet();
        private final Map<String, CompletableFuture<Void>> checkpointFutures = new ConcurrentHashMap<>();

        private JobCommitLog(TaskGraph graph) {
            this.graph = graph;
        }

        @Override
        public boolean isCommitted(String operationId) {
            if (pendingCheckpoints.contains(operationId) || failedCheckpoints.contains(operationId)) {
                return false;
            }
            if (durableCheckpoints.contains(operationId)) {
                return true;
            }
            boolean scaffoldCommitted = repository.scaffolds().values().stream()
                    .flatMap(scaffold -> scaffold.cells().stream())
                    .anyMatch(cell -> cell.placementOperationId().filter(operationId::equals).isPresent()
                            || cell.removalOperationId().filter(operationId::equals).isPresent());
            if (scaffoldCommitted) {
                return true;
            }
            return job().blockJournal().stream()
                    .anyMatch(entry -> entry.operationId().equals(operationId));
        }

        @Override
        public CompletableFuture<Void> commit(OperationIntent intent) {
            if (durableCheckpoints.contains(intent.operationId())) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> prior = checkpointFutures.get(intent.operationId());
            if (prior != null) {
                return prior;
            }
            if (intent.kind() == OperationKind.MATERIAL_TRANSFER) {
                return checkpoint(intent.operationId());
            }
            String taskId = intent.taskId()
                    .orElseThrow(() -> new IllegalStateException("World mutation intent has no task identity"));
            if (taskId.startsWith("scaffold:")) {
                return commitScaffold(intent, taskId);
            }
            BuildTask task = graph.task(taskId);
            List<WorldDelta> worldDeltas = intent.deltas().stream()
                    .filter(WorldDelta.class::isInstance)
                    .map(WorldDelta.class::cast)
                    .toList();
            if (worldDeltas.size() != 1) {
                throw new IllegalStateException("Task 15 commits exactly one world delta per task");
            }
            WorldDelta world = worldDeltas.getFirst();
            BuildJob existing = job();
            Optional<JournalEntry> priorEntry = existing.blockJournal().stream()
                    .filter(entry -> entry.operationId().equals(intent.operationId()))
                    .findFirst();
            if (priorEntry.isPresent()) {
                if (!priorEntry.orElseThrow().taskId().equals(taskId)
                        || !absolutePosition(existing, task.position())
                                .equals(new BlockPos(world.x(), world.y(), world.z()))) {
                    throw new IllegalStateException("OperationIntent conflicts with its existing journal evidence");
                }
                return checkpoint(intent.operationId());
            }
            BuildJob current = restoreBuildingState(job(), intent.jobRevision());
            if (!absolutePosition(current, task.position()).equals(new BlockPos(world.x(), world.y(), world.z()))) {
                throw new IllegalStateException("OperationIntent world position does not match its BuildTask");
            }
            MinecraftSnapshotAdapter snapshots = new MinecraftSnapshotAdapter(builder.registryAccess());
            long sequence = current.blockJournal().isEmpty()
                    ? 0
                    : current.blockJournal().getLast().sequence() + 1;
            JournalEntry entry = new JournalEntry(
                    sequence,
                    intent.operationId() + ":0",
                    intent.operationId(),
                    taskId,
                    task.position(),
                    specification(snapshots.restore(world.before())),
                    specification(snapshots.restore(world.after())),
                    current.revision() + 1);
            saveJob(current.recordCompletion(taskId, entry));
            return checkpoint(intent.operationId());
        }

        private CompletableFuture<Void> commitScaffold(OperationIntent intent, String taskId) {
            ScaffoldTask scaffoldTask = ScaffoldTask.parse(taskId);
            ScaffoldProvenance provenance = repository.findScaffold(scaffoldTask.planId())
                    .orElseThrow(() -> new IllegalStateException("Scaffold operation has no provenance"));
            if (!provenance.jobId().equals(intent.jobId())) {
                throw new IllegalStateException("Scaffold provenance belongs to a different BuildJob");
            }
            if (scaffoldTask.index() < 0 || scaffoldTask.index() >= provenance.cells().size()) {
                throw new IllegalStateException("Scaffold operation cell index is out of bounds");
            }
            List<WorldDelta> worldDeltas = intent.deltas().stream()
                    .filter(WorldDelta.class::isInstance)
                    .map(WorldDelta.class::cast)
                    .toList();
            if (worldDeltas.size() != 1) {
                throw new IllegalStateException("Scaffold operation must mutate exactly one world cell");
            }
            WorldDelta world = worldDeltas.getFirst();
            Cell cell = provenance.cells().get(scaffoldTask.index());
            if (!scaffoldPosition(cell).equals(new BlockPos(world.x(), world.y(), world.z()))) {
                throw new IllegalStateException("Scaffold operation position does not match provenance");
            }
            MinecraftSnapshotAdapter snapshots = new MinecraftSnapshotAdapter(builder.registryAccess());
            BlockState before = snapshots.restore(world.before());
            BlockState after = snapshots.restore(world.after());
            ScaffoldProvenance updated;
            if (scaffoldTask.mode() == ScaffoldMode.PLACE) {
                if (!before.isAir() || !after.equals(restore(cell.state()))) {
                    throw new IllegalStateException("Scaffold placement evidence does not match provenance");
                }
                updated = provenance.recordPlaced(scaffoldTask.index(), intent.operationId());
            } else {
                if (!before.equals(restore(cell.state())) || !after.isAir()) {
                    throw new IllegalStateException("Scaffold cleanup evidence does not match provenance");
                }
                updated = provenance.recordRemoved(scaffoldTask.index(), intent.operationId());
            }
            repository.saveScaffold(updated);
            return checkpoint(intent.operationId());
        }

        private CompletableFuture<Void> checkpoint(String operationId) {
            CompletableFuture<Void> existing = checkpointFutures.get(operationId);
            if (existing != null) {
                return existing;
            }
            pendingCheckpoints.add(operationId);
            failedCheckpoints.remove(operationId);
            CompletableFuture<Void> checkpoint;
            try {
                ServerLevel level = (ServerLevel) builder.level();
                checkpoint = level.getDataStorage().scheduleSave().thenApply(ignored -> null);
            } catch (RuntimeException failure) {
                pendingCheckpoints.remove(operationId);
                failedCheckpoints.add(operationId);
                return CompletableFuture.failedFuture(failure);
            }
            checkpointFutures.put(operationId, checkpoint);
            checkpoint.whenComplete((ignored, failure) -> {
                checkpointFutures.remove(operationId);
                pendingCheckpoints.remove(operationId);
                if (failure == null) {
                    durableCheckpoints.add(operationId);
                    failedCheckpoints.remove(operationId);
                } else {
                    failedCheckpoints.add(operationId);
                }
            });
            return checkpoint;
        }

        private BuildJob restoreBuildingState(BuildJob current, long expectedRevision) {
            if (current.state() == BuildJobState.BUILDING) {
                if (current.revision() != expectedRevision) {
                    throw new IllegalStateException("BuildJob revision does not match its OperationIntent");
                }
                return current;
            }
            while (current.revision() < expectedRevision && current.state() != BuildJobState.BUILDING) {
                current = switch (current.state()) {
                    case IDLE, PAUSED, PAUSED_MISSING_MATERIAL, PAUSED_NO_CHEST,
                            PAUSED_BLOCKED, PAUSED_CONFLICT, PAUSED_PROTECTED,
                            SUSPENDED_CHUNK_UNLOADED -> current.transitionTo(BuildJobState.PREPARING);
                    case PREPARING -> current.transitionTo(BuildJobState.NAVIGATING);
                    case WAIT_MATERIAL -> current.transitionTo(BuildJobState.FETCHING_MATERIAL);
                    case FETCHING_MATERIAL -> current.transitionTo(BuildJobState.NAVIGATING);
                    case NAVIGATING -> current.transitionTo(BuildJobState.BUILDING);
                    default -> throw new IllegalStateException(
                            "BuildJob cannot recover construction commit while " + current.state());
                };
            }
            if (current.state() != BuildJobState.BUILDING || current.revision() != expectedRevision) {
                throw new IllegalStateException("BuildJob recovery did not reconstruct the intent revision");
            }
            return current;
        }
    }

    private enum InFlightAction {
        RECOVERY,
        RECOVERY_CHECKPOINT,
        TRANSFER,
        PLACEMENT,
        SCAFFOLD_PLACE,
        SCAFFOLD_REMOVE,
        SCAFFOLD_PLAN
    }

    private enum ScaffoldMode {
        PLACE,
        REMOVE
    }

    private record ScaffoldTask(String planId, ScaffoldMode mode, int index) {
        private static ScaffoldTask parse(String taskId) {
            String[] parts = taskId.split(":", -1);
            if (parts.length != 4 || !parts[0].equals("scaffold") || parts[1].isBlank()) {
                throw new IllegalStateException("Invalid scaffold task identity: " + taskId);
            }
            ScaffoldMode mode = switch (parts[2]) {
                case "place" -> ScaffoldMode.PLACE;
                case "remove" -> ScaffoldMode.REMOVE;
                default -> throw new IllegalStateException("Invalid scaffold task action: " + taskId);
            };
            try {
                return new ScaffoldTask(parts[1], mode, Integer.parseInt(parts[3]));
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("Invalid scaffold task index: " + taskId, exception);
            }
        }
    }

    private record TransferSlots(int sourceSlot, int destinationSlot, int count) {
    }

    private static final class MissingLinkedContainerException extends RuntimeException {
    }
}
