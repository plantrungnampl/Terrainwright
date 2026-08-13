package dev.ssa.fabric.builder;

import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.plan.TaskGraph;
import dev.ssa.fabric.construction.FabricMutationExecutor;
import dev.ssa.fabric.construction.MaterialTransferService;
import dev.ssa.fabric.construction.OperationBoundaryListener;
import dev.ssa.fabric.entity.BuilderEntity;
import dev.ssa.fabric.entity.ModEntityTypes;
import dev.ssa.fabric.lifecycle.BuilderLifecycleTombstone;
import dev.ssa.fabric.link.BuilderChestLinkService;
import dev.ssa.fabric.link.ContainerBinding;
import dev.ssa.fabric.permission.FabricPermissionAdapter;
import dev.ssa.fabric.persistence.OperationIntentStore;
import dev.ssa.fabric.persistence.PersistenceExecutor;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
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

    private BuilderRuntimeService(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
        permissions = new FabricPermissionAdapter(server);
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
        ServerTickEvents.END_LEVEL_TICK.register(BuilderRuntimeService::tickLevel);
        ServerLifecycleEvents.SERVER_STOPPED.register(BuilderRuntimeService::closeServer);
    }

    public static Optional<BuilderEntity> start(
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

    private Optional<BuilderEntity> spawn(
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
                return Optional.of(builder);
            }
            return Optional.empty();
        }

        BuilderEntity builder = new BuilderEntity(ModEntityTypes.BUILDER, level);
        builder.setPos(
                spawnPosition.getX() + 0.5,
                spawnPosition.getY(),
                spawnPosition.getZ() + 0.5);
        if (!level.addFreshEntity(builder)) {
            builder.discard();
            throw new IllegalStateException("Minecraft rejected the production Builder spawn");
        }
        repository.savePlan(job.jobId(), plan);
        repository.saveHutState(new ServerBuildJobRepository.HutState(
                hut.hutId(),
                hut.ownerId(),
                hut.activeJobId(),
                hut.containerBinding(),
                java.util.Optional.of(BuilderLifecycleTombstone.active(builder.getUUID())),
                hut.revision() + 1));
        attach(level, builder, job, plan, chestBinding);
        return Optional.of(builder);
    }

    private void attachLoadedBuilders(ServerLevel level) {
        requireServerThread();
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
        for (BuilderEntity builder : builders) {
            List<ServerBuildJobRepository.HutState> owners = repository.huts().values().stream()
                    .filter(hut -> hut.builderLifecycle().stream()
                            .anyMatch(lifecycle -> !lifecycle.isTombstoned()
                                    && lifecycle.builderId().equals(builder.getUUID())))
                    .toList();
            if (owners.size() > 1) {
                throw new IllegalStateException("One Builder identity is linked to multiple Huts");
            }
            if (owners.isEmpty()) {
                continue;
            }
            ServerBuildJobRepository.HutState hut = owners.getFirst();
            if (hut.activeJobId().isEmpty() || hut.containerBinding().isEmpty()) {
                continue;
            }
            String jobId = hut.activeJobId().orElseThrow();
            BuildJob job = repository.findJob(jobId)
                    .orElseThrow(() -> new IllegalStateException("Builder Hut references a missing BuildJob"));
            TaskGraph plan = repository.findPlan(jobId)
                    .orElseThrow(() -> new IllegalStateException("Builder job has no durable task graph"));
            attach(level, builder, job, plan, hut.containerBinding().orElseThrow());
        }
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
                links,
                permissions);
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
