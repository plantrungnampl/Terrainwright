package dev.ssa.fabric.network;

import dev.ssa.architect.material.BlockCapabilityRegistry;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.style.StylePack;
import dev.ssa.common.permission.PermissionPort;
import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.plan.ConstructionPlanner;
import dev.ssa.fabric.TerrainwrightMod;
import dev.ssa.fabric.builder.BuilderRuntimeService;
import dev.ssa.fabric.network.PreviewPayloads.ConfirmPreview;
import dev.ssa.fabric.network.PreviewPayloads.CancelSurvey;
import dev.ssa.fabric.network.PreviewPayloads.PreviewFailure;
import dev.ssa.fabric.network.PreviewPayloads.PreviewResult;
import dev.ssa.fabric.network.PreviewPayloads.RequestPreview;
import dev.ssa.fabric.network.PreviewPayloads.SelectSurveySite;
import dev.ssa.fabric.network.PreviewPayloads.StartSurvey;
import dev.ssa.fabric.network.PreviewPayloads.SurveyTokenResult;
import dev.ssa.fabric.network.PreviewPayloads.SurveyStatus;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import dev.ssa.fabric.permission.FabricPermissionAdapter;
import dev.ssa.fabric.preview.ArchitectGenerationService;
import dev.ssa.fabric.preview.PreviewSessionService;
import dev.ssa.fabric.survey.SurveyModeService;
import dev.ssa.fabric.style.StyleDataLoader;
import dev.ssa.fabric.world.FabricTerrainScanner;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PreviewNetworking {
    private static final int MAX_PREVIEW_PACKET_BYTES = 16 * 1024 * 1024;
    private static final int PREVIEW_WORKERS = 2;
    private static final int PREVIEW_QUEUE_CAPACITY = 16;
    private static final int PLAYER_REQUEST_COOLDOWN_TICKS = 5;
    private static final Logger LOGGER = LoggerFactory.getLogger(TerrainwrightMod.MOD_ID + "/preview");
    private static final Map<MinecraftServer, Services> SERVICES = new WeakHashMap<>();
    private static boolean initialized;

    private PreviewNetworking() {}

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        PayloadTypeRegistry.serverboundPlay().register(StartSurvey.TYPE, StartSurvey.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CancelSurvey.TYPE, CancelSurvey.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SelectSurveySite.TYPE, SelectSurveySite.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RequestPreview.TYPE, RequestPreview.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ConfirmPreview.TYPE, ConfirmPreview.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SurveyTokenResult.TYPE, SurveyTokenResult.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SurveyStatus.TYPE, SurveyStatus.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PreviewFailure.TYPE, PreviewFailure.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(
                PreviewResult.TYPE,
                PreviewResult.CODEC,
                MAX_PREVIEW_PACKET_BYTES);

        ServerPlayNetworking.registerGlobalReceiver(StartSurvey.TYPE, PreviewNetworking::startSurvey);
        ServerPlayNetworking.registerGlobalReceiver(CancelSurvey.TYPE, PreviewNetworking::cancelSurvey);
        ServerPlayNetworking.registerGlobalReceiver(SelectSurveySite.TYPE, PreviewNetworking::selectSite);
        ServerPlayNetworking.registerGlobalReceiver(RequestPreview.TYPE, PreviewNetworking::requestPreview);
        ServerPlayNetworking.registerGlobalReceiver(ConfirmPreview.TYPE, PreviewNetworking::confirmPreview);
        ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> {
            Services services = services(server);
            UUID owner = listener.player.getUUID();
            services.surveys().cancel(owner);
            services.generation().cancel(owner);
            services.forget(owner);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(PreviewNetworking::close);
    }

    private static void startSurvey(StartSurvey payload, ServerPlayNetworking.Context context) {
        Services services = services(context.server());
        services.generation().cancel(context.player().getUUID());
        SurveyModeService.StartResult result = services.surveys().start(
                context.player(),
                payload.architectTablePos(),
                context.server().getTickCount());
        if (result.failure().isPresent()) {
            LOGGER.debug("Rejected Survey start for {}: {}", context.player().getUUID(), result.failure().orElseThrow());
        }
        sendSurveyStatus(context.player(), SurveyStatus.Action.START, result.session().isPresent());
    }

    private static void cancelSurvey(CancelSurvey payload, ServerPlayNetworking.Context context) {
        Services services = services(context.server());
        UUID owner = context.player().getUUID();
        services.generation().cancel(owner);
        services.surveys().cancel(owner);
    }

    private static void selectSite(SelectSurveySite payload, ServerPlayNetworking.Context context) {
        Services services = services(context.server());
        String selectionRevision = context.player().level().dimension().identifier()
                + ":" + context.server().getTickCount();
        SurveyModeService.SelectionResult result = services.surveys().selectSite(
                context.player(),
                payload.anchor(),
                selectionRevision,
                context.server().getTickCount());
        if (result.token().isPresent()
                && ServerPlayNetworking.canSend(context.player(), SurveyTokenResult.TYPE)) {
            ServerPlayNetworking.send(
                    context.player(),
                    new SurveyTokenResult(result.token().orElseThrow().rawToken()));
        } else if (result.failure().isPresent()) {
            LOGGER.debug("Rejected Survey selection for {}: {}", context.player().getUUID(), result.failure().orElseThrow());
            sendSurveyStatus(context.player(), SurveyStatus.Action.SELECT_SITE, false);
        }
    }

    private static void requestPreview(RequestPreview payload, ServerPlayNetworking.Context context) {
        Services services = services(context.server());
        if (!services.admit(context.player().getUUID(), context.server().getTickCount())) {
            LOGGER.debug("Rate-limited preview request for {}", context.player().getUUID());
            sendFailure(context.player(), payload.requestNonce(), PreviewFailure.Reason.RATE_LIMITED);
            returnRetryToken(context.player(), payload.surveyToken());
            return;
        }
        Optional<StyleDataLoader.LoadedStyle> loadedStyle = StyleDataLoader.find(payload.requirements().styleId());
        if (loadedStyle.isEmpty()) {
            LOGGER.debug("Rejected unknown preview style {}", payload.requirements().styleId());
            sendFailure(context.player(), payload.requestNonce(), PreviewFailure.Reason.INVALID_SURVEY);
            returnRetryToken(context.player(), payload.surveyToken());
            return;
        }
        StylePack style = loadedStyle.orElseThrow().style();
        BlockCapabilityRegistry registry = loadedStyle.orElseThrow().capabilities();
        services.generation().request(
                        context.player().level(),
                        context.player().getUUID(),
                        payload,
                        style,
                        registry)
                .thenAccept(outcome -> {
                    if (outcome.session().isPresent()
                            && ServerPlayNetworking.canSend(context.player(), PreviewResult.TYPE)) {
                        PreviewSessionService.PreviewSession session = outcome.session().orElseThrow();
                        ServerPlayNetworking.send(context.player(), new PreviewResult(
                                session.id(),
                                session.blueprintHash(),
                                session.blueprint(),
                                session.anchor(),
                                session.rotation(),
                                session.expiryRevision(),
                                session.requestNonce()));
                    }
                    outcome.failure().ifPresent(failure -> sendFailure(
                            context.player(), payload.requestNonce(), failureReason(failure)));
                    if (outcome.failure().filter(
                                    failure -> failure == ArchitectGenerationService.Failure.TERRAIN_UNAVAILABLE)
                            .isPresent()) {
                        returnRetryToken(context.player(), payload.surveyToken());
                    }
                    if (outcome.status() != ArchitectGenerationService.Status.REPLACED
                            && ServerPlayNetworking.canSend(context.player(), SurveyTokenResult.TYPE)) {
                        outcome.consumedToken()
                                .flatMap(token -> services.surveys().reissue(
                                        token, context.server().getTickCount()))
                                .ifPresent(token -> ServerPlayNetworking.send(
                                        context.player(), new SurveyTokenResult(token.rawToken())));
                    }
                });
    }

    private static void returnRetryToken(ServerPlayer player, String rawToken) {
        if (ServerPlayNetworking.canSend(player, SurveyTokenResult.TYPE)) {
            ServerPlayNetworking.send(player, new SurveyTokenResult(rawToken));
        }
    }

    private static void sendFailure(ServerPlayer player, long requestNonce, PreviewFailure.Reason reason) {
        if (ServerPlayNetworking.canSend(player, PreviewFailure.TYPE)) {
            ServerPlayNetworking.send(player, new PreviewFailure(requestNonce, reason));
        }
    }

    private static void sendSurveyStatus(ServerPlayer player, SurveyStatus.Action action, boolean accepted) {
        if (ServerPlayNetworking.canSend(player, SurveyStatus.TYPE)) {
            ServerPlayNetworking.send(player, new SurveyStatus(action, accepted));
        }
    }

    private static PreviewFailure.Reason failureReason(ArchitectGenerationService.Failure failure) {
        return switch (failure) {
            case INVALID_SURVEY_TOKEN, SERVER_THREAD_REQUIRED -> PreviewFailure.Reason.INVALID_SURVEY;
            case SURVEY_EXPIRED -> PreviewFailure.Reason.SURVEY_EXPIRED;
            case WRONG_DIMENSION -> PreviewFailure.Reason.WRONG_DIMENSION;
            case TERRAIN_UNAVAILABLE -> PreviewFailure.Reason.TERRAIN_UNAVAILABLE;
            case GENERATION_FAILED -> PreviewFailure.Reason.GENERATION_FAILED;
            case SERVER_BUSY -> PreviewFailure.Reason.SERVER_BUSY;
        };
    }

    private static void confirmPreview(ConfirmPreview payload, ServerPlayNetworking.Context context) {
        Services services = services(context.server());
        ServerPlayer player = context.player();
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(player.level());
        PreviewSessionService.ConfirmationResult confirmation = services.sessions().confirmDetailed(
                player.level(),
                services.scanner(),
                services.permissions(),
                repository,
                player.getUUID(),
                payload,
                context.server().getTickCount());
        if (!confirmation.confirmed()) {
            PreviewSessionService.ConfirmationFailure failure = confirmation.failure().orElseThrow();
            LOGGER.debug("Rejected preview confirmation for {}: {}", player.getUUID(), failure);
            player.sendSystemMessage(Component.literal("Terrainwright: " + failure.message()));
            return;
        }
        PreviewSessionService.ConfirmationAuthority authority = confirmation.authority().orElseThrow();
        BuildJob job = BuildJob.create(
                UUID.randomUUID().toString(),
                authority.owner().toString(),
                authority.hutId().toString(),
                authority.blueprint().id().toString(),
                authority.blueprintHash(),
                NamespacedId.parse(authority.dimensionId().toString()),
                gridPos(authority.anchor()),
                authority.rotation());
        ServerBuildJobRepository.HutState hut = repository.findHut(authority.hutId()).orElseThrow();
        repository.saveJob(job);
        var plan = new ConstructionPlanner().plan(authority.blueprint());
        repository.savePlan(job.jobId(), plan);
        repository.saveHutState(new ServerBuildJobRepository.HutState(
                hut.hutId(),
                hut.ownerId(),
                Optional.of(job.jobId()),
                hut.containerBinding(),
                hut.builderLifecycle(),
                hut.revision() + 1));

        sendSurveyStatus(player, SurveyStatus.Action.CONFIRM, true);
        try {
            BuilderRuntimeService.start(
                            player.level(),
                            job,
                            plan,
                            hut.containerBinding().orElseThrow(),
                            authority.anchor().above())
                    .whenComplete((builder, failure) -> {
                        if (failure == null && builder.isPresent()) {
                            return;
                        }
                        context.server().execute(() -> {
                            if (failure != null) {
                                LOGGER.error("Durable Builder spawn failed for job {}", job.jobId(), failure);
                            } else {
                                LOGGER.error("Durable Builder spawn returned no entity for job {}", job.jobId());
                            }
                            if (player.connection != null) {
                                player.sendSystemMessage(Component.literal(
                                        "Terrainwright: The build was confirmed, but the Builder could not start. "
                                                + "Open the Builder Hut to replace it."));
                            }
                        });
                    });
        } catch (RuntimeException failure) {
            LOGGER.error("Durable Builder start failed synchronously for job {}", job.jobId(), failure);
            player.sendSystemMessage(Component.literal(
                    "Terrainwright: The build was confirmed, but the Builder could not start. "
                            + "Open the Builder Hut to recover it."));
        }
    }

    private static Services services(MinecraftServer server) {
        synchronized (SERVICES) {
            return SERVICES.computeIfAbsent(server, PreviewNetworking::createServices);
        }
    }

    private static Services createServices(MinecraftServer server) {
        PermissionPort permissions = FabricPermissionAdapter.forServer(server);
        SurveyModeService surveys = new SurveyModeService(permissions);
        PreviewSessionService sessions = new PreviewSessionService();
        FabricTerrainScanner scanner = new FabricTerrainScanner();
        ExecutorService workers = new ThreadPoolExecutor(
                PREVIEW_WORKERS,
                PREVIEW_WORKERS,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(PREVIEW_QUEUE_CAPACITY),
                Thread.ofVirtual().name("ssa-preview-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        ArchitectGenerationService generation = new ArchitectGenerationService(
                surveys,
                scanner,
                sessions,
                workers,
                server::getTickCount);
        return new Services(permissions, surveys, sessions, scanner, generation, workers, new HashMap<>());
    }

    private static void close(MinecraftServer server) {
        Services removed;
        synchronized (SERVICES) {
            removed = SERVICES.remove(server);
        }
        if (removed != null) {
            removed.workers().shutdownNow();
        }
        FabricPermissionAdapter.releaseServer(server);
    }

    private static GridPos gridPos(BlockPos position) {
        return new GridPos(position.getX(), position.getY(), position.getZ());
    }

    private record Services(
            PermissionPort permissions,
            SurveyModeService surveys,
            PreviewSessionService sessions,
            FabricTerrainScanner scanner,
            ArchitectGenerationService generation,
            ExecutorService workers,
            Map<UUID, Integer> lastRequestTicks) {
        private Services {
            lastRequestTicks = new HashMap<>(lastRequestTicks);
        }

        private synchronized boolean admit(UUID owner, int currentTick) {
            Integer last = lastRequestTicks.get(owner);
            if (last != null && currentTick - last < PLAYER_REQUEST_COOLDOWN_TICKS) {
                return false;
            }
            lastRequestTicks.put(owner, currentTick);
            return true;
        }

        private synchronized void forget(UUID owner) {
            lastRequestTicks.remove(owner);
        }
    }
}
