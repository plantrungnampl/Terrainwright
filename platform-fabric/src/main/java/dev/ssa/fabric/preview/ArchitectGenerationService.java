package dev.ssa.fabric.preview;

import dev.ssa.architect.ArchitectEngine;
import dev.ssa.architect.ArchitectEngine.GenerationResult;
import dev.ssa.architect.material.BlockCapabilityRegistry;
import dev.ssa.architect.model.TerrainSnapshot;
import dev.ssa.architect.style.StylePack;
import dev.ssa.fabric.network.PreviewPayloads.RequestPreview;
import dev.ssa.fabric.preview.PreviewSessionService.PreviewSession;
import dev.ssa.fabric.survey.SurveyModeService;
import dev.ssa.fabric.survey.SurveyModeService.SiteToken;
import dev.ssa.fabric.world.FabricTerrainScanner;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class ArchitectGenerationService {
    public static final long DEFAULT_PREVIEW_TTL = 20L * 30L;

    private final TokenAuthority tokens;
    private final TerrainCapture terrainCapture;
    private final PreviewSessionService sessions;
    private final ArchitectEngine engine;
    private final Executor workerExecutor;
    private final LongSupplier revisionClock;
    private final Map<UUID, ActiveRequest> activeRequests = new HashMap<>();

    public ArchitectGenerationService(
            SurveyModeService surveys,
            FabricTerrainScanner scanner,
            PreviewSessionService sessions,
            Executor workerExecutor,
            LongSupplier revisionClock) {
        this(
                new TokenAuthority() {
                    @Override
                    public Optional<SiteToken> validate(UUID owner, String rawToken, long revision) {
                        return surveys.validateToken(owner, rawToken, revision);
                    }

                    @Override
                    public Optional<SiteToken> consume(UUID owner, String rawToken, long revision) {
                        return surveys.consumeToken(owner, rawToken, revision);
                    }
                },
                scanner::scan,
                sessions,
                workerExecutor,
                revisionClock);
        Objects.requireNonNull(surveys, "surveys");
        Objects.requireNonNull(scanner, "scanner");
    }

    ArchitectGenerationService(
            TokenAuthority tokens,
            TerrainCapture terrainCapture,
            PreviewSessionService sessions,
            Executor workerExecutor,
            LongSupplier revisionClock) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.terrainCapture = Objects.requireNonNull(terrainCapture, "terrainCapture");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.engine = new ArchitectEngine();
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.revisionClock = Objects.requireNonNull(revisionClock, "revisionClock");
    }

    public CompletableFuture<RequestOutcome> request(
            ServerLevel level,
            UUID owner,
            RequestPreview request,
            StylePack style,
            BlockCapabilityRegistry registry) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(registry, "registry");
        if (!level.getServer().isSameThread()) {
            return CompletableFuture.completedFuture(RequestOutcome.rejected(Failure.SERVER_THREAD_REQUIRED));
        }

        long revision = revisionClock.getAsLong();
        Optional<SiteToken> trustedToken = tokens.validate(owner, request.surveyToken(), revision);
        if (trustedToken.isEmpty()) {
            return CompletableFuture.completedFuture(RequestOutcome.rejected(Failure.INVALID_SURVEY_TOKEN));
        }
        SiteToken token = trustedToken.orElseThrow();
        if (!token.dimensionId().equals(level.dimension().identifier())) {
            return CompletableFuture.completedFuture(RequestOutcome.rejected(Failure.WRONG_DIMENSION));
        }
        Optional<TerrainSnapshot> terrain = terrainCapture.scan(
                level,
                token.anchor(),
                request.requirements().targetWidth(),
                request.requirements().targetDepth());
        if (terrain.isEmpty()) {
            return CompletableFuture.completedFuture(RequestOutcome.rejected(Failure.TERRAIN_UNAVAILABLE));
        }

        TerrainSnapshot snapshot = terrain.orElseThrow();
        return requestCaptured(
                owner,
                request,
                token,
                snapshot,
                () -> engine.generate(request.requirements(), snapshot, style, registry),
                level.getServer()::execute);
    }

    public void cancel(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        ActiveRequest active;
        synchronized (activeRequests) {
            active = activeRequests.remove(owner);
        }
        if (active != null) {
            active.response().complete(RequestOutcome.replaced());
            active.cancelWorker();
            purgeCancelledWorkers();
        }
        sessions.cancel(owner);
    }

    CompletableFuture<RequestOutcome> requestCaptured(
            UUID owner,
            RequestPreview request,
            SiteToken token,
            TerrainSnapshot terrain,
            Supplier<GenerationResult> generation,
            Executor serverExecutor) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(terrain, "terrain");
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(serverExecutor, "serverExecutor");
        if (!token.ownerId().equals(owner)
                || !token.tokenHash().equals(SurveyModeService.hashToken(request.surveyToken()))) {
            return CompletableFuture.completedFuture(RequestOutcome.rejected(Failure.INVALID_SURVEY_TOKEN));
        }
        Optional<SiteToken> consumed = tokens.consume(
                owner,
                request.surveyToken(),
                revisionClock.getAsLong());
        if (consumed.isEmpty() || !consumed.orElseThrow().equals(token)) {
            return CompletableFuture.completedFuture(RequestOutcome.rejected(Failure.INVALID_SURVEY_TOKEN));
        }
        sessions.cancel(owner);

        CompletableFuture<RequestOutcome> response = new CompletableFuture<>();
        ActiveRequest current = new ActiveRequest(response);
        CompletableFuture<GenerationResult> completion = new CompletableFuture<>();
        FutureTask<Void> worker = new FutureTask<>(() -> {
            try {
                completion.complete(generation.get());
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
            return null;
        });
        current.attach(worker);
        ActiveRequest replaced;
        synchronized (activeRequests) {
            replaced = activeRequests.put(owner, current);
        }
        if (replaced != null) {
            replaced.response().complete(RequestOutcome.replaced());
            replaced.cancelWorker();
            purgeCancelledWorkers();
        }

        completion.whenComplete((result, failure) -> serverExecutor.execute(
                () -> publish(owner, request, token, terrain, current, result, failure)));
        try {
            workerExecutor.execute(worker);
        } catch (RejectedExecutionException exception) {
            synchronized (activeRequests) {
                if (activeRequests.get(owner) == current) {
                    activeRequests.remove(owner);
                }
            }
            current.response().complete(RequestOutcome.rejected(Failure.SERVER_BUSY, token));
        }
        return response;
    }

    private void publish(
            UUID owner,
            RequestPreview request,
            SiteToken token,
            TerrainSnapshot terrain,
            ActiveRequest requestState,
            GenerationResult result,
            Throwable failure) {
        synchronized (activeRequests) {
            if (activeRequests.get(owner) != requestState) {
                return;
            }
            activeRequests.remove(owner);
        }
        if (failure != null || !(result instanceof GenerationResult.Success success)) {
            requestState.response().complete(RequestOutcome.rejected(Failure.GENERATION_FAILED, token));
            return;
        }

        long revision = revisionClock.getAsLong();
        if (revision > token.expiresAtRevision()) {
            requestState.response().complete(RequestOutcome.rejected(Failure.SURVEY_EXPIRED));
            return;
        }
        SiteToken snapshotBoundToken = new SiteToken(
                token.ownerId(),
                token.dimensionId(),
                token.architectTablePos(),
                token.anchor(),
                token.tokenHash(),
                terrain.revisionFingerprint(),
                token.expiresAtRevision());
        long requestedExpiry = revision > Long.MAX_VALUE - DEFAULT_PREVIEW_TTL
                ? Long.MAX_VALUE
                : revision + DEFAULT_PREVIEW_TTL;
        PreviewSession session = sessions.store(
                owner,
                snapshotBoundToken,
                success.blueprint(),
                request.rotation(),
                Math.min(requestedExpiry, token.expiresAtRevision()),
                request.requestNonce(),
                terrain.width(),
                terrain.depth());
        requestState.response().complete(RequestOutcome.ready(session, token));
    }

    private void purgeCancelledWorkers() {
        if (workerExecutor instanceof ThreadPoolExecutor pool) {
            pool.purge();
        }
    }

    interface TokenAuthority {
        Optional<SiteToken> validate(UUID owner, String rawToken, long revision);

        Optional<SiteToken> consume(UUID owner, String rawToken, long revision);
    }

    interface TerrainCapture {
        Optional<TerrainSnapshot> scan(ServerLevel level, BlockPos anchor, int width, int depth);
    }

    public record RequestOutcome(
            Status status,
            Optional<PreviewSession> session,
            Optional<Failure> failure,
            Optional<SiteToken> consumedToken) {
        public RequestOutcome {
            Objects.requireNonNull(status, "status");
            session = Objects.requireNonNull(session, "session");
            failure = Objects.requireNonNull(failure, "failure");
            consumedToken = Objects.requireNonNull(consumedToken, "consumedToken");
            if ((status == Status.PREVIEW_READY) != session.isPresent()) {
                throw new IllegalArgumentException("Only a ready request may include a PreviewSession");
            }
            if ((status == Status.REJECTED) != failure.isPresent()) {
                throw new IllegalArgumentException("Only a rejected request must include a failure");
            }
        }

        private static RequestOutcome ready(PreviewSession session, SiteToken consumedToken) {
            return new RequestOutcome(
                    Status.PREVIEW_READY,
                    Optional.of(session),
                    Optional.empty(),
                    Optional.of(consumedToken));
        }

        private static RequestOutcome rejected(Failure failure) {
            return new RequestOutcome(Status.REJECTED, Optional.empty(), Optional.of(failure), Optional.empty());
        }

        private static RequestOutcome rejected(Failure failure, SiteToken consumedToken) {
            return new RequestOutcome(
                    Status.REJECTED,
                    Optional.empty(),
                    Optional.of(failure),
                    Optional.of(consumedToken));
        }

        private static RequestOutcome replaced() {
            return new RequestOutcome(Status.REPLACED, Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    public enum Status {
        PREVIEW_READY,
        REJECTED,
        REPLACED
    }

    public enum Failure {
        SERVER_THREAD_REQUIRED,
        INVALID_SURVEY_TOKEN,
        SURVEY_EXPIRED,
        WRONG_DIMENSION,
        TERRAIN_UNAVAILABLE,
        GENERATION_FAILED,
        SERVER_BUSY
    }

    private static final class ActiveRequest {
        private final CompletableFuture<RequestOutcome> response;
        private Future<?> worker;

        private ActiveRequest(CompletableFuture<RequestOutcome> response) {
            this.response = response;
        }

        private CompletableFuture<RequestOutcome> response() {
            return response;
        }

        private synchronized void attach(Future<?> worker) {
            this.worker = worker;
        }

        private synchronized void cancelWorker() {
            if (worker != null) {
                worker.cancel(true);
            }
        }
    }
}
