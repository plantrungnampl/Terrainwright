package dev.ssa.fabric.builder;

import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.construction.plan.TaskGraph;
import dev.ssa.fabric.construction.FabricMutationExecutor;
import dev.ssa.fabric.construction.MaterialTransferService;
import dev.ssa.fabric.construction.OperationBoundaryListener;
import dev.ssa.fabric.entity.BuilderEntity;
import dev.ssa.fabric.entity.ModEntityTypes;
import dev.ssa.fabric.lifecycle.BuilderLifecycleTombstone;
import dev.ssa.fabric.job.ChunkSuspensionService;
import dev.ssa.fabric.job.JobRecoveryService;
import dev.ssa.fabric.link.BuilderChestLinkService;
import dev.ssa.fabric.link.ContainerBinding;
import dev.ssa.fabric.network.JobReplicationService;
import dev.ssa.fabric.permission.FabricPermissionAdapter;
import dev.ssa.fabric.persistence.OperationIntentStore;
import dev.ssa.fabric.persistence.PersistenceExecutor;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import dev.ssa.fabric.undo.FabricUndoExecutor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;

/** Production composition root for durable Builder controllers. */
public final class BuilderRuntimeService implements AutoCloseable {
    private static final Map<MinecraftServer, BuilderRuntimeService> SERVICES = new WeakHashMap<>();
    private static boolean initialized;

    private final MinecraftServer server;
    private final PersistenceExecutor persistence = new PersistenceExecutor("ssa-builder-persistence");
    private final FabricPermissionAdapter permissions;
    private final BuilderChestLinkService links;
    private final Path operationDirectory;
    private final Map<String, CompletableFuture<FabricUndoExecutor.UndoResult>> activeUndos = new HashMap<>();

    private BuilderRuntimeService(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
        permissions = FabricPermissionAdapter.forServer(server);
        links = new BuilderChestLinkService(permissions);
        operationDirectory = server.getWorldPath(LevelResource.ROOT)
                .toAbsolutePath()
                .normalize()
                .resolve("data")
                .resolve("smart_survival_architect")
                .resolve("operations");
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof BuilderEntity builder) {
                service(level.getServer()).onBuilderUnloaded(level, builder);
            }
        });
        ServerTickEvents.END_LEVEL_TICK.register(BuilderRuntimeService::tickLevel);
        ServerLifecycleEvents.SERVER_STOPPED.register(BuilderRuntimeService::closeServer);
    }

    public static CompletableFuture<Optional<BuilderEntity>> start(
            ServerLevel level,
            BuildJob job,
            TaskGraph plan,
            ContainerBinding chestBinding,
            BlockPos spawnPosition) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(chestBinding, "chestBinding");
        Objects.requireNonNull(spawnPosition, "spawnPosition");
        BuilderRuntimeService service = service(level.getServer());
        return service.spawn(level, job, plan, chestBinding, spawnPosition);
    }

    public static CompletableFuture<Optional<BuilderEntity>> replace(
            ServerLevel level,
            UUID hutId,
            UUID ownerId,
            BlockPos spawnPosition) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(hutId, "hutId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(spawnPosition, "spawnPosition");
        return service(level.getServer()).replaceTombstoned(
                level, hutId, ownerId, spawnPosition);
    }

    public static CompletableFuture<JobReplicationService.CommandResult> stopJob(
            ServerLevel level,
            String jobId,
            UUID ownerId,
            long expectedRevision) {
        Objects.requireNonNull(level, "level");
        return service(level.getServer()).controls(level)
                .stop(jobId, Objects.requireNonNull(ownerId, "ownerId"), expectedRevision);
    }

    public static CompletableFuture<JobReplicationService.CommandResult> pauseJob(
            ServerLevel level,
            String jobId,
            UUID ownerId,
            long expectedRevision) {
        Objects.requireNonNull(level, "level");
        return service(level.getServer()).controls(level)
                .pause(jobId, Objects.requireNonNull(ownerId, "ownerId"), expectedRevision);
    }

    public static CompletableFuture<JobReplicationService.CommandResult> resumeJob(
            ServerLevel level,
            String jobId,
            UUID ownerId,
            long expectedRevision) {
        Objects.requireNonNull(level, "level");
        return service(level.getServer()).controls(level)
                .resume(jobId, Objects.requireNonNull(ownerId, "ownerId"), expectedRevision);
    }

    public static CompletableFuture<JobReplicationService.CommandResult> undoJob(
            ServerLevel level,
            String jobId,
            UUID ownerId,
            long expectedRevision) {
        Objects.requireNonNull(level, "level");
        return service(level.getServer()).controls(level)
                .undo(jobId, Objects.requireNonNull(ownerId, "ownerId"), expectedRevision);
    }

    public static void relinkChest(
            ServerLevel level,
            UUID hutId,
            ContainerBinding binding) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(hutId, "hutId");
        Objects.requireNonNull(binding, "binding");
        ServerBuildJobRepository.get(level)
                .findHut(hutId)
                .flatMap(ServerBuildJobRepository.HutState::builderLifecycle)
                .map(lifecycle -> level.getEntity(lifecycle.builderId()))
                .filter(BuilderEntity.class::isInstance)
                .map(BuilderEntity.class::cast)
                .ifPresent(builder -> builder.relinkChest(binding));
    }

    public static void observeDeath(ServerLevel level, BuilderEntity builder) {
        service(level.getServer()).recordBuilderLoss(level, builder, true);
    }

    public static void observeRemoval(ServerLevel level, BuilderEntity builder) {
        service(level.getServer()).recordBuilderLoss(level, builder, false);
    }

    public static void observeHutLoss(ServerLevel level, UUID hutId) {
        Objects.requireNonNull(level, "level");
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(level);
        repository.removeHut(Objects.requireNonNull(hutId, "hutId"));
        level.getDataStorage().scheduleSave();
    }

    private static void tickLevel(ServerLevel level) {
        service(level.getServer()).attachLoadedBuilders(level);
    }

    private static synchronized BuilderRuntimeService service(MinecraftServer server) {
        return SERVICES.computeIfAbsent(server, BuilderRuntimeService::new);
    }

    private static void closeServer(MinecraftServer server) {
        BuilderRuntimeService removed;
        synchronized (BuilderRuntimeService.class) {
            removed = SERVICES.remove(server);
        }
        if (removed != null) {
            removed.close();
        }
    }

    private CompletableFuture<Optional<BuilderEntity>> spawn(
            ServerLevel level,
            BuildJob job,
            TaskGraph plan,
            ContainerBinding chestBinding,
            BlockPos spawnPosition) {
        requireServerThread();
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(level);
        ServerBuildJobRepository.HutState hut = repository
                .findHut(UUID.fromString(job.hutId()))
                .orElseThrow(() -> new IllegalStateException("Builder job has no durable Hut"));
        if (hut.builderLifecycle().isPresent()) {
            BuilderLifecycleTombstone lifecycle = hut.builderLifecycle().orElseThrow();
            if (lifecycle.isTombstoned()) {
                throw new IllegalStateException("A tombstoned Builder requires explicit replacement");
            }
            Entity existing = level.getEntity(lifecycle.builderId());
            if (existing instanceof BuilderEntity builder) {
                attach(level, builder, job, plan, chestBinding);
                return CompletableFuture.completedFuture(Optional.of(builder));
            }
            if (lifecycle.status() == BuilderLifecycleTombstone.Status.ACTIVE) {
                markSpawnFailure(level, repository, hut.hutId(), job.jobId(), lifecycle.builderId());
            }
            return CompletableFuture.completedFuture(Optional.empty());
        }

        UUID builderId = UUID.randomUUID();
        repository.savePlan(job.jobId(), plan);
        repository.saveHutState(new ServerBuildJobRepository.HutState(
                hut.hutId(),
                hut.ownerId(),
                hut.activeJobId(),
                hut.containerBinding(),
                java.util.Optional.of(BuilderLifecycleTombstone.active(builderId)),
                hut.revision() + 1));
        CompletableFuture<Optional<BuilderEntity>> result = new CompletableFuture<>();
        level.getDataStorage().scheduleSave().whenComplete((ignored, saveFailure) -> server.execute(() -> {
            if (saveFailure != null) {
                try {
                    markSpawnFailure(level, repository, hut.hutId(), job.jobId(), builderId);
                } catch (RuntimeException recoveryFailure) {
                    saveFailure.addSuppressed(recoveryFailure);
                }
                result.completeExceptionally(saveFailure);
                return;
            }
            BuilderEntity builder = null;
            try {
                ServerBuildJobRepository.HutState durableHut = repository
                        .findHut(UUID.fromString(job.hutId()))
                        .orElseThrow();
                BuilderLifecycleTombstone durableLifecycle = durableHut.builderLifecycle().orElseThrow();
                if (!durableLifecycle.builderId().equals(builderId)
                        || durableLifecycle.status() != BuilderLifecycleTombstone.Status.ACTIVE) {
                    throw new IllegalStateException("Builder lifecycle changed before durable spawn");
                }
                builder = new BuilderEntity(ModEntityTypes.BUILDER, level);
                builder.setUUID(builderId);
                builder.setPos(
                        spawnPosition.getX() + 0.5,
                        spawnPosition.getY(),
                        spawnPosition.getZ() + 0.5);
                if (!level.addFreshEntity(builder)) {
                    throw new IllegalStateException("Minecraft rejected the production Builder spawn");
                }
                attach(level, builder, repository.findJob(job.jobId()).orElseThrow(), plan, chestBinding);
                result.complete(Optional.of(builder));
            } catch (Throwable failure) {
                try {
                    markSpawnFailure(level, repository, hut.hutId(), job.jobId(), builderId);
                } catch (RuntimeException recoveryFailure) {
                    failure.addSuppressed(recoveryFailure);
                }
                if (builder != null && !builder.isRemoved()) {
                    builder.discard();
                }
                result.completeExceptionally(failure);
            }
        }));
        return result;
    }

    private void markSpawnFailure(
            ServerLevel level,
            ServerBuildJobRepository repository,
            UUID hutId,
            String jobId,
            UUID builderId) {
        ServerBuildJobRepository.HutState currentHut = repository.findHut(hutId).orElse(null);
        if (currentHut == null) {
            return;
        }
        BuilderLifecycleTombstone lifecycle = currentHut.builderLifecycle().orElse(null);
        if (lifecycle == null
                || !lifecycle.builderId().equals(builderId)
                || lifecycle.isTombstoned()) {
            return;
        }
        BuildJob currentJob = repository.findJob(jobId).orElse(null);
        if (currentJob != null) {
            if (currentJob.state() == BuildJobState.IDLE) {
                currentJob = currentJob.transitionTo(BuildJobState.PREPARING);
            }
            if (currentJob.state().canTransitionTo(BuildJobState.NO_BUILDER)) {
                currentJob = currentJob.transitionTo(BuildJobState.NO_BUILDER);
                repository.saveJob(currentJob);
            }
        }
        repository.saveHutState(new ServerBuildJobRepository.HutState(
                currentHut.hutId(),
                currentHut.ownerId(),
                currentHut.activeJobId(),
                currentHut.containerBinding(),
                Optional.of(lifecycle.observeSpawnFailure(level.getGameTime())),
                currentHut.revision() + 1));
        level.getDataStorage().scheduleSave();
    }

    private CompletableFuture<Optional<BuilderEntity>> replaceTombstoned(
            ServerLevel level,
            UUID hutId,
            UUID ownerId,
            BlockPos spawnPosition) {
        requireServerThread();
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(level);
        ServerBuildJobRepository.HutState hut = replacementHut(repository, hutId, ownerId);
        BuilderLifecycleTombstone lifecycle = hut.builderLifecycle().orElseThrow();
        OperationIntentStore previousStore = new OperationIntentStore(
                operationDirectory.resolve(lifecycle.builderId() + ".wal"),
                persistence);
        CompletableFuture<Optional<BuilderEntity>> result = new CompletableFuture<>();
        new JobRecoveryService(repository)
                .quarantineLostBuilderIntent(lifecycle.builderId(), previousStore, server::execute)
                .whenComplete((intentOutcome, recoveryFailure) -> server.execute(() -> {
            if (recoveryFailure != null) {
                result.completeExceptionally(recoveryFailure);
                return;
            }
            if (intentOutcome == JobRecoveryService.IntentOutcome.QUARANTINED) {
                level.getDataStorage().scheduleSave().whenComplete((saved, saveFailure) -> {
                    if (saveFailure != null) {
                        result.completeExceptionally(saveFailure);
                    } else {
                        result.completeExceptionally(new IllegalStateException(
                                "Replacement blocked: the tombstoned Builder OperationIntent is quarantined"));
                    }
                });
                return;
            }
            try {
                ServerBuildJobRepository.HutState currentHut = replacementHut(
                        repository, hutId, ownerId);
                BuilderLifecycleTombstone currentLifecycle = currentHut.builderLifecycle().orElseThrow();
                if (currentLifecycle.revision() != lifecycle.revision()
                        || !currentLifecycle.builderId().equals(lifecycle.builderId())) {
                    throw new IllegalStateException("Builder lifecycle changed during replacement authorization");
                }
                String jobId = currentHut.activeJobId().orElseThrow();
                BuildJob job = repository.findJob(jobId).orElseThrow();
                TaskGraph plan = repository.findPlan(jobId).orElseThrow();
                ContainerBinding binding = currentHut.containerBinding().orElseThrow();
                UUID replacementId = UUID.randomUUID();
                repository.saveJob(job.transitionTo(dev.ssa.construction.job.BuildJobState.PREPARING));
                repository.saveHutState(new ServerBuildJobRepository.HutState(
                        currentHut.hutId(),
                        currentHut.ownerId(),
                        currentHut.activeJobId(),
                        currentHut.containerBinding(),
                        Optional.of(currentLifecycle.replaceWith(replacementId)),
                        currentHut.revision() + 1));
                level.getDataStorage().scheduleSave().whenComplete((ignored, saveFailure) -> server.execute(() -> {
                    if (saveFailure != null) {
                        result.completeExceptionally(saveFailure);
                        return;
                    }
                    try {
                        BuilderEntity replacement = new BuilderEntity(ModEntityTypes.BUILDER, level);
                        replacement.setUUID(replacementId);
                        replacement.setPos(
                                spawnPosition.getX() + 0.5,
                                spawnPosition.getY(),
                                spawnPosition.getZ() + 0.5);
                        if (!level.addFreshEntity(replacement)) {
                            replacement.discard();
                            throw new IllegalStateException("Minecraft rejected the replacement Builder spawn");
                        }
                        attach(level, replacement, repository.findJob(jobId).orElseThrow(), plan, binding);
                        result.complete(Optional.of(replacement));
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                }));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }));
        return result;
    }

    private static ServerBuildJobRepository.HutState replacementHut(
            ServerBuildJobRepository repository,
            UUID hutId,
            UUID ownerId) {
        ServerBuildJobRepository.HutState hut = repository.findHut(hutId)
                .orElseThrow(() -> new IllegalStateException("Replacement Hut is not durable"));
        if (!hut.ownerId().equals(ownerId)) {
            throw new IllegalStateException("Only the durable Hut owner may replace its Builder");
        }
        BuilderLifecycleTombstone lifecycle = hut.builderLifecycle()
                .orElseThrow(() -> new IllegalStateException("Replacement requires a durable Builder identity"));
        if (!lifecycle.canReplace()) {
            throw new IllegalStateException("Replacement requires durable tombstone evidence");
        }
        String jobId = hut.activeJobId()
                .orElseThrow(() -> new IllegalStateException("Replacement Hut has no active BuildJob"));
        BuildJob job = repository.findJob(jobId).orElseThrow();
        if (job.state() != dev.ssa.construction.job.BuildJobState.NO_BUILDER) {
            throw new IllegalStateException("Replacement requires a NO_BUILDER job");
        }
        if (hut.containerBinding().isEmpty() || repository.findPlan(jobId).isEmpty()) {
            throw new IllegalStateException("Replacement requires durable binding and task graph");
        }
        return hut;
    }

    private void attachLoadedBuilders(ServerLevel level) {
        requireServerThread();
        resumeDurableUndos(level);
        List<BuilderEntity> builders = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof BuilderEntity builder && !builder.hasController()) {
                builders.add(builder);
            }
        }
        if (builders.isEmpty()) {
            return;
        }
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(level);
        JobRecoveryService recovery = new JobRecoveryService(repository);
        for (BuilderEntity builder : builders) {
            JobRecoveryService.Reconciliation reconciliation =
                    recovery.reconcileLoadedBuilder(builder.getUUID());
            if (reconciliation.outcome() != JobRecoveryService.Outcome.READY_FOR_OPERATION_RECOVERY
                    && reconciliation.outcome() != JobRecoveryService.Outcome.SUSPENDED
                    && reconciliation.outcome() != JobRecoveryService.Outcome.ORPHANED
                    && reconciliation.outcome() != JobRecoveryService.Outcome.STOPPING) {
                continue;
            }
            ServerBuildJobRepository.HutState hut = repository
                    .findHut(reconciliation.hutId().orElseThrow())
                    .orElseThrow();
            String jobId = reconciliation.jobId().orElseThrow();
            BuildJob job = repository.findJob(jobId)
                    .orElseThrow(() -> new IllegalStateException("Builder Hut references a missing BuildJob"));
            TaskGraph plan = repository.findPlan(jobId)
                    .orElseThrow(() -> new IllegalStateException("Builder job has no durable task graph"));
            attach(level, builder, job, plan, hut.containerBinding().orElseThrow());
        }
    }

    private JobReplicationService controls(ServerLevel level) {
        requireServerThread();
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(level);
        return new JobReplicationService(repository, permissions, new JobReplicationService.CommandExecutor() {
            @Override
            public CompletableFuture<Void> pause(BuildJob pausedJob) {
                return level.getDataStorage().scheduleSave().thenApply(ignored -> (Void) null);
            }

            @Override
            public CompletableFuture<Void> resume(BuildJob resumedJob) {
                return level.getDataStorage().scheduleSave().thenApply(ignored -> (Void) null);
            }

            @Override
            public CompletableFuture<Void> stop(BuildJob stoppingJob) {
                return level.getDataStorage().scheduleSave().thenApply(ignored -> (Void) null);
            }

            @Override
            public CompletableFuture<Void> undo(BuildJob undoingJob) {
                return level.getDataStorage().scheduleSave()
                        .thenComposeAsync(
                                ignored -> beginUndo(level, undoingJob).thenApply(result -> (Void) null),
                                server::execute);
            }
        });
    }

    private void resumeDurableUndos(ServerLevel level) {
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(level);
        String levelId = level.dimension().identifier().toString();
        for (BuildJob job : repository.jobs().values()) {
            if (job.state() != BuildJobState.UNDOING
                    || !job.worldId().toString().equals(levelId)
                    || activeUndos.containsKey(job.jobId())) {
                continue;
            }
            UUID ownerId;
            try {
                ownerId = UUID.fromString(job.ownerId());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (server.getPlayerList().getPlayer(ownerId) == null) {
                continue;
            }
            beginUndo(level, job);
        }
    }

    private CompletableFuture<FabricUndoExecutor.UndoResult> beginUndo(
            ServerLevel level,
            BuildJob job) {
        CompletableFuture<FabricUndoExecutor.UndoResult> existing = activeUndos.get(job.jobId());
        if (existing != null) {
            return existing;
        }
        UUID ownerId = UUID.fromString(job.ownerId());
        String walName = "undo-" + UUID.nameUUIDFromBytes(
                job.jobId().getBytes(StandardCharsets.UTF_8)) + ".wal";
        OperationIntentStore store = new OperationIntentStore(
                operationDirectory.resolve(walName),
                persistence);
        FabricMutationExecutor mutations = new FabricMutationExecutor(
                store,
                server::execute,
                OperationBoundaryListener.NONE);
        CompletableFuture<FabricUndoExecutor.UndoResult> future = new FabricUndoExecutor(
                        level,
                        ServerBuildJobRepository.get(level),
                        permissions,
                        mutations)
                .undo(job.jobId(), ownerId);
        activeUndos.put(job.jobId(), future);
        future.whenComplete((ignored, failure) -> server.execute(() ->
                activeUndos.remove(job.jobId(), future)));
        return future;
    }

    private void onBuilderUnloaded(ServerLevel level, BuilderEntity builder) {
        requireServerThread();
        new ChunkSuspensionService(ServerBuildJobRepository.get(level)).suspendBuilder(builder.getUUID());
        level.getDataStorage().scheduleSave();
    }

    private void recordBuilderLoss(
            ServerLevel level,
            BuilderEntity builder,
            boolean death) {
        requireServerThread();
        JobRecoveryService recovery = new JobRecoveryService(ServerBuildJobRepository.get(level));
        if (death) {
            recovery.observeDeath(builder.getUUID(), level.getGameTime());
        } else {
            recovery.observeRemoval(builder.getUUID(), level.getGameTime());
        }
        level.getDataStorage().scheduleSave();
    }

    private void attach(
            ServerLevel level,
            BuilderEntity builder,
            BuildJob job,
            TaskGraph plan,
            ContainerBinding chestBinding) {
        if (builder.hasController()) {
            return;
        }
        OperationIntentStore store = new OperationIntentStore(
                operationDirectory.resolve(builder.getUUID() + ".wal"),
                persistence);
        FabricMutationExecutor mutations = new FabricMutationExecutor(
                store,
                server::execute,
                OperationBoundaryListener.NONE);
        BuilderController controller = new BuilderController(
                builder,
                mutations,
                new MaterialTransferService(mutations),
                ServerBuildJobRepository.get(level),
                new BuilderChestLinkService(permissions, UUID.fromString(job.ownerId())),
                permissions,
                recoveredLevel -> {
                    new ChunkSuspensionService(ServerBuildJobRepository.get(recoveredLevel))
                            .resumeBuilder(builder.getUUID());
                    return recoveredLevel.getDataStorage().scheduleSave()
                            .thenApply(ignored -> (Void) null);
                });
        builder.attachController(controller);
        controller.assign(new BuilderController.WorkOrder(job.jobId(), plan, chestBinding));
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Builder runtime must run on the Minecraft server thread");
        }
    }

    @Override
    public void close() {
        persistence.close();
    }
}
