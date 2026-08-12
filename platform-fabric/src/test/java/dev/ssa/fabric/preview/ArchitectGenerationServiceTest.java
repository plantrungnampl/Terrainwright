package dev.ssa.fabric.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.ArchitectEngine.GenerationResult;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.EntrancePreference;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.StyleId;
import dev.ssa.architect.model.TerrainSnapshot;
import dev.ssa.architect.model.TerrainSnapshot.SlopeMetrics;
import dev.ssa.fabric.network.PreviewPayloads.RequestPreview;
import dev.ssa.fabric.survey.SurveyModeService.SiteToken;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class ArchitectGenerationServiceTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void completionReturnsToServerExecutorBeforePublishingPreview() {
        PreviewSessionService sessions = new PreviewSessionService();
        TokenAuthorityStub tokens = new TokenAuthorityStub();
        TestExecutor worker = new TestExecutor();
        TestExecutor server = new TestExecutor();
        SiteToken token = tokens.add("survey-one", 900);
        ArchitectGenerationService service = new ArchitectGenerationService(
                tokens,
                (level, anchor, width, depth) -> Optional.empty(),
                sessions,
                worker,
                () -> 100);

        var future = service.requestCaptured(
                OWNER,
                request("survey-one", 1),
                token,
                terrain("terrain-one"),
                () -> new GenerationResult.Success(PreviewTestFixtures.blueprint(1), diagnostics()),
                server);

        assertTrue(tokens.validate(OWNER, "survey-one", 100).isEmpty());
        worker.runNext();
        assertTrue(sessions.activeSession(OWNER).isEmpty());
        assertTrue(!future.isDone());

        server.runNext();
        assertEquals(
                PreviewTestFixtures.blueprint(1),
                future.join().session().orElseThrow().blueprint());
        assertEquals("terrain-one", future.join().session().orElseThrow().worldRevision());
    }

    @Test
    void staleCompletionCannotOverrideNewerRequest() {
        PreviewSessionService sessions = new PreviewSessionService();
        TokenAuthorityStub tokens = new TokenAuthorityStub();
        TestExecutor worker = new TestExecutor();
        TestExecutor server = new TestExecutor();
        SiteToken firstToken = tokens.add("survey-one", 900);
        SiteToken secondToken = tokens.add("survey-two", 900);
        ArchitectGenerationService service = new ArchitectGenerationService(
                tokens,
                (level, anchor, width, depth) -> Optional.empty(),
                sessions,
                worker,
                () -> 100);
        sessions.store(OWNER, firstToken, PreviewTestFixtures.blueprint(0), 0, 800, 0, 9, 9);

        var first = service.requestCaptured(
                OWNER,
                request("survey-one", 1),
                firstToken,
                terrain("terrain-one"),
                () -> new GenerationResult.Success(PreviewTestFixtures.blueprint(1), diagnostics()),
                server);
        assertTrue(sessions.activeSession(OWNER).isEmpty());
        var second = service.requestCaptured(
                OWNER,
                request("survey-two", 2),
                secondToken,
                terrain("terrain-two"),
                () -> new GenerationResult.Success(PreviewTestFixtures.blueprint(2), diagnostics()),
                server);

        assertEquals(ArchitectGenerationService.Status.REPLACED, first.join().status());
        worker.runLast();
        server.runAll();
        assertEquals(ArchitectGenerationService.Status.PREVIEW_READY, second.join().status());
        assertEquals(
                PreviewTestFixtures.blueprint(2),
                sessions.activeSession(OWNER).orElseThrow().blueprint());

        worker.runAll();
        server.runAll();
        assertEquals(
                PreviewTestFixtures.blueprint(2),
                sessions.activeSession(OWNER).orElseThrow().blueprint());
        assertTrue(tokens.validate(OWNER, "survey-one", 100).isEmpty());
    }

    @Test
    void replacementInterruptsGenerationAlreadyRunningOnWorker() throws Exception {
        PreviewSessionService sessions = new PreviewSessionService();
        TokenAuthorityStub tokens = new TokenAuthorityStub();
        SiteToken firstToken = tokens.add("survey-one", 900);
        SiteToken secondToken = tokens.add("survey-two", 900);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ArchitectGenerationService service = new ArchitectGenerationService(
                    tokens,
                    (level, anchor, width, depth) -> Optional.empty(),
                    sessions,
                    worker,
                    () -> 100);
            var first = service.requestCaptured(
                    OWNER,
                    request("survey-one", 1),
                    firstToken,
                    terrain("terrain-one"),
                    () -> {
                        started.countDown();
                        try {
                            while (true) {
                                Thread.sleep(1_000);
                            }
                        } catch (InterruptedException exception) {
                            interrupted.countDown();
                            throw new java.util.concurrent.CancellationException("replaced");
                        }
                    },
                    Runnable::run);
            assertTrue(started.await(5, TimeUnit.SECONDS));

            var second = service.requestCaptured(
                    OWNER,
                    request("survey-two", 2),
                    secondToken,
                    terrain("terrain-two"),
                    () -> new GenerationResult.Success(PreviewTestFixtures.blueprint(2), diagnostics()),
                    Runnable::run);

            assertEquals(ArchitectGenerationService.Status.REPLACED, first.join().status());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "replaced generation was not interrupted");
            assertEquals(
                    ArchitectGenerationService.Status.PREVIEW_READY,
                    second.get(5, TimeUnit.SECONDS).status());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void saturatedWorkerRejectsRequestWithoutLeavingItActive() {
        PreviewSessionService sessions = new PreviewSessionService();
        TokenAuthorityStub tokens = new TokenAuthorityStub();
        SiteToken token = tokens.add("survey-one", 900);
        ArchitectGenerationService service = new ArchitectGenerationService(
                tokens,
                (level, anchor, width, depth) -> Optional.empty(),
                sessions,
                command -> {
                    throw new java.util.concurrent.RejectedExecutionException("queue full");
                },
                () -> 100);

        var rejected = service.requestCaptured(
                OWNER,
                request("survey-one", 1),
                token,
                terrain("terrain-one"),
                () -> new GenerationResult.Success(PreviewTestFixtures.blueprint(1), diagnostics()),
                Runnable::run);

        assertEquals(ArchitectGenerationService.Status.REJECTED, rejected.join().status());
        assertEquals(
                ArchitectGenerationService.Failure.SERVER_BUSY,
                rejected.join().failure().orElseThrow());
        service.cancel(OWNER);
        assertTrue(sessions.activeSession(OWNER).isEmpty());
    }

    @Test
    void replacingQueuedRequestRemovesCancelledQueueEntry() throws Exception {
        UUID otherOwner = UUID.fromString("33333333-3333-3333-3333-333333333333");
        PreviewSessionService sessions = new PreviewSessionService();
        TokenAuthorityStub tokens = new TokenAuthorityStub();
        SiteToken runningToken = tokens.add("survey-running", 900);
        SiteToken queuedToken = tokens.add(otherOwner, "survey-queued", 900);
        SiteToken replacementToken = tokens.add(otherOwner, "survey-replacement", 900);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor worker = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        try {
            ArchitectGenerationService service = new ArchitectGenerationService(
                    tokens,
                    (level, anchor, width, depth) -> Optional.empty(),
                    sessions,
                    worker,
                    () -> 100);
            service.requestCaptured(
                    OWNER,
                    request("survey-running", 1),
                    runningToken,
                    terrain("terrain-running"),
                    () -> {
                        running.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new java.util.concurrent.CancellationException("cancelled");
                        }
                        return new GenerationResult.Success(PreviewTestFixtures.blueprint(1), diagnostics());
                    },
                    Runnable::run);
            assertTrue(running.await(5, TimeUnit.SECONDS));
            var queued = service.requestCaptured(
                    otherOwner,
                    request("survey-queued", 2),
                    queuedToken,
                    terrain("terrain-queued"),
                    () -> new GenerationResult.Success(PreviewTestFixtures.blueprint(2), diagnostics()),
                    Runnable::run);

            var replacement = service.requestCaptured(
                    otherOwner,
                    request("survey-replacement", 3),
                    replacementToken,
                    terrain("terrain-replacement"),
                    () -> new GenerationResult.Success(PreviewTestFixtures.blueprint(3), diagnostics()),
                    Runnable::run);

            assertEquals(ArchitectGenerationService.Status.REPLACED, queued.join().status());
            assertTrue(!replacement.isDone(), "replacement was rejected by a cancelled queue tombstone");
            release.countDown();
            assertEquals(
                    ArchitectGenerationService.Status.PREVIEW_READY,
                    replacement.get(5, TimeUnit.SECONDS).status());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    private static RequestPreview request(String token, long nonce) {
        return new RequestPreview(
                token,
                new HouseRequirements(
                        StyleId.parse("smart_survival_architect:medieval"),
                        9,
                        9,
                        1,
                        1,
                        true,
                        true,
                        false,
                        false,
                        EntrancePreference.SOUTH,
                        nonce),
                0,
                nonce);
    }

    private static TerrainSnapshot terrain(String revision) {
        int width = 9;
        int depth = 9;
        return new TerrainSnapshot(
                new GridPos(0, 64, 0),
                width,
                depth,
                64,
                64,
                java.util.Collections.nCopies(width * depth, 64),
                java.util.Collections.nCopies(
                        width * depth, NamespacedId.parse("minecraft:stone")),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                new SlopeMetrics(0, 0),
                Map.of(),
                revision);
    }

    private static dev.ssa.architect.ArchitectEngine.GenerationDiagnostics diagnostics() {
        return new dev.ssa.architect.ArchitectEngine.GenerationDiagnostics(
                dev.ssa.architect.ArchitectEngine.CANDIDATE_COUNT,
                1,
                java.util.stream.IntStream.range(0, dev.ssa.architect.ArchitectEngine.CANDIDATE_COUNT)
                        .mapToObj(index -> index == 0
                                ? new dev.ssa.architect.ArchitectEngine.CandidateDiagnostic(
                                        index,
                                        index,
                                        dev.ssa.architect.ArchitectEngine.CandidateStatus.VALID,
                                        Optional.of(dev.ssa.architect.scoring.ScoreBreakdown.unscored()),
                                        List.of())
                                : new dev.ssa.architect.ArchitectEngine.CandidateDiagnostic(
                                        index,
                                        index,
                                        dev.ssa.architect.ArchitectEngine.CandidateStatus.REJECTED,
                                        Optional.empty(),
                                        List.of("TEST_REJECTED")))
                        .toList());
    }

    private static final class TokenAuthorityStub implements ArchitectGenerationService.TokenAuthority {
        private final Map<String, SiteToken> tokens = new HashMap<>();

        private SiteToken add(String rawToken, long expiry) {
            return add(OWNER, rawToken, expiry);
        }

        private SiteToken add(UUID owner, String rawToken, long expiry) {
            SiteToken token = new SiteToken(
                    owner,
                    Identifier.parse("minecraft:overworld"),
                    new BlockPos(0, 64, 0),
                    new BlockPos(4, 64, 4),
                    dev.ssa.fabric.survey.SurveyModeService.hashToken(rawToken),
                    "selection-revision",
                    expiry);
            tokens.put(rawToken, token);
            return token;
        }

        @Override
        public Optional<SiteToken> validate(UUID owner, String rawToken, long revision) {
            SiteToken token = tokens.get(rawToken);
            return token != null && token.ownerId().equals(owner) && revision <= token.expiresAtRevision()
                    ? Optional.of(token)
                    : Optional.empty();
        }

        @Override
        public Optional<SiteToken> consume(UUID owner, String rawToken, long revision) {
            Optional<SiteToken> valid = validate(owner, rawToken, revision);
            valid.ifPresent(ignored -> tokens.remove(rawToken));
            return valid;
        }
    }

    private static final class TestExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runNext() {
            tasks.removeFirst().run();
        }

        private void runLast() {
            tasks.removeLast().run();
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                runNext();
            }
        }
    }
}
