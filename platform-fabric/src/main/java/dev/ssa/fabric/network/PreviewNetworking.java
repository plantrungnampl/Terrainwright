package dev.ssa.fabric.network;

import dev.ssa.architect.material.BlockCapability;
import dev.ssa.architect.material.BlockCapabilityRegistry;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.StyleId;
import dev.ssa.architect.style.JapaneseStyle;
import dev.ssa.architect.style.MedievalStyle;
import dev.ssa.architect.style.ModernStyle;
import dev.ssa.architect.style.StylePack;
import dev.ssa.common.permission.PermissionPort;
import dev.ssa.construction.job.BuildJob;
import dev.ssa.fabric.SmartSurvivalArchitectMod;
import dev.ssa.fabric.network.PreviewPayloads.ConfirmPreview;
import dev.ssa.fabric.network.PreviewPayloads.PreviewResult;
import dev.ssa.fabric.network.PreviewPayloads.RequestPreview;
import dev.ssa.fabric.network.PreviewPayloads.SelectSurveySite;
import dev.ssa.fabric.network.PreviewPayloads.StartSurvey;
import dev.ssa.fabric.network.PreviewPayloads.SurveyTokenResult;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import dev.ssa.fabric.preview.ArchitectGenerationService;
import dev.ssa.fabric.preview.PreviewSessionService;
import dev.ssa.fabric.survey.SurveyModeService;
import dev.ssa.fabric.world.FabricTerrainScanner;
import java.util.HashMap;
import java.util.HashSet;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PreviewNetworking {
    private static final int MAX_PREVIEW_PACKET_BYTES = 16 * 1024 * 1024;
    private static final int PREVIEW_WORKERS = 2;
    private static final int PREVIEW_QUEUE_CAPACITY = 16;
    private static final int PLAYER_REQUEST_COOLDOWN_TICKS = 5;
    private static final Logger LOGGER = LoggerFactory.getLogger(SmartSurvivalArchitectMod.MOD_ID + "/preview");
    private static final Map<StyleId, StylePack> STYLES = styles();
    private static final Map<MinecraftServer, Services> SERVICES = new WeakHashMap<>();
    private static boolean initialized;

    private PreviewNetworking() {}

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        PayloadTypeRegistry.serverboundPlay().register(StartSurvey.TYPE, StartSurvey.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SelectSurveySite.TYPE, SelectSurveySite.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RequestPreview.TYPE, RequestPreview.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ConfirmPreview.TYPE, ConfirmPreview.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SurveyTokenResult.TYPE, SurveyTokenResult.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(
                PreviewResult.TYPE,
                PreviewResult.CODEC,
                MAX_PREVIEW_PACKET_BYTES);

        ServerPlayNetworking.registerGlobalReceiver(StartSurvey.TYPE, PreviewNetworking::startSurvey);
        ServerPlayNetworking.registerGlobalReceiver(SelectSurveySite.TYPE, PreviewNetworking::selectSite);
        ServerPlayNetworking.registerGlobalReceiver(RequestPreview.TYPE, PreviewNetworking::requestPreview);
        ServerPlayNetworking.registerGlobalReceiver(ConfirmPreview.TYPE, PreviewNetworking::confirmPreview);
        ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> {
            Services services = services(server);
            UUID owner = listener.player.getUUID();
            services.surveys().cancel(owner);
            services.generation().cancel(owner);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(PreviewNetworking::close);
    }

    private static void startSurvey(StartSurvey payload, ServerPlayNetworking.Context context) {
        Services services = services(context.server());
        SurveyModeService.StartResult result = services.surveys().start(
                context.player(),
                payload.architectTablePos(),
                context.server().getTickCount());
        if (result.failure().isPresent()) {
            LOGGER.debug("Rejected Survey start for {}: {}", context.player().getUUID(), result.failure().orElseThrow());
        }
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
        }
    }

    private static void requestPreview(RequestPreview payload, ServerPlayNetworking.Context context) {
        Services services = services(context.server());
        if (!services.admit(context.player().getUUID(), context.server().getTickCount())) {
            LOGGER.debug("Rate-limited preview request for {}", context.player().getUUID());
            return;
        }
        StylePack style = STYLES.get(payload.requirements().styleId());
        if (style == null) {
            LOGGER.debug("Rejected unknown preview style {}", payload.requirements().styleId());
            return;
        }
        BlockCapabilityRegistry registry = capabilities(style);
        services.generation().request(
                        context.player().level(),
                        context.player().getUUID(),
                        payload,
                        style,
                        registry)
                .thenAccept(outcome -> {
                    if (outcome.session().isEmpty()
                            || !ServerPlayNetworking.canSend(context.player(), PreviewResult.TYPE)) {
                        return;
                    }
                    PreviewSessionService.PreviewSession session = outcome.session().orElseThrow();
                    ServerPlayNetworking.send(context.player(), new PreviewResult(
                            session.id(),
                            session.blueprintHash(),
                            session.blueprint(),
                            session.rotation(),
                            session.expiryRevision(),
                            session.requestNonce()));
                });
    }

    private static void confirmPreview(ConfirmPreview payload, ServerPlayNetworking.Context context) {
        Services services = services(context.server());
        ServerPlayer player = context.player();
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(player.level());
        Optional<PreviewSessionService.ConfirmationAuthority> confirmed = services.sessions().confirm(
                player.level(),
                services.scanner(),
                services.permissions(),
                repository,
                player.getUUID(),
                payload,
                context.server().getTickCount());
        if (confirmed.isEmpty()) {
            return;
        }
        PreviewSessionService.ConfirmationAuthority authority = confirmed.orElseThrow();
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
        repository.saveHutState(new ServerBuildJobRepository.HutState(
                hut.hutId(),
                hut.ownerId(),
                Optional.of(job.jobId()),
                hut.containerBinding(),
                hut.builderLifecycle(),
                hut.revision() + 1));
    }

    private static Services services(MinecraftServer server) {
        synchronized (SERVICES) {
            return SERVICES.computeIfAbsent(server, PreviewNetworking::createServices);
        }
    }

    private static Services createServices(MinecraftServer server) {
        PermissionPort permissions = (owner, position) -> {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            return player != null
                    && player.level().mayInteract(
                            player,
                            new BlockPos(position.x(), position.y(), position.z()));
        };
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
    }

    private static Map<StyleId, StylePack> styles() {
        StylePack medieval = new MedievalStyle();
        StylePack japanese = new JapaneseStyle();
        StylePack modern = new ModernStyle();
        return Map.of(
                medieval.id(), medieval,
                japanese.id(), japanese,
                modern.id(), modern);
    }

    private static BlockCapabilityRegistry capabilities(StylePack style) {
        Map<NamespacedId, java.util.Set<BlockCapability>> entries = new HashMap<>();
        style.fallbackPalette().values().forEach(candidates -> candidates.forEach(candidate -> entries
                .computeIfAbsent(candidate.state().blockId(), ignored -> new HashSet<>())
                .addAll(candidate.requiredCapabilities())));
        return BlockCapabilityRegistry.of(entries);
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
    }
}
