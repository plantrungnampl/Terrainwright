package dev.ssa.fabric.spike.restart;

import dev.ssa.construction.operation.OperationIntent;
import dev.ssa.construction.operation.OperationStatus;
import dev.ssa.fabric.construction.CoordinatorOutcome;
import dev.ssa.fabric.construction.CoordinatorResult;
import dev.ssa.fabric.persistence.OperationIntentStore;
import dev.ssa.fabric.construction.OperationBoundaryListener;
import dev.ssa.fabric.construction.FabricMutationExecutor;
import dev.ssa.fabric.persistence.PersistenceExecutor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public final class S5RestartServerDriver {
    private static final int INJECTED_CRASH_EXIT_CODE = 70;
    private static final int HARNESS_FAILURE_EXIT_CODE = 72;
    private static final String MODE_CRASH = "crash";
    private static final String MODE_RECOVER = "recover";

    private final String mode;
    private final String fixtureId;
    private final S5CrashBoundary boundary;
    private final AtomicInteger blockedSchedulingTicks = new AtomicInteger();
    private final AtomicBoolean recoveryReady = new AtomicBoolean();
    private final AtomicBoolean schedulingOpen = new AtomicBoolean();
    private final AtomicBoolean schedulingRecorded = new AtomicBoolean();

    private volatile RestartFixtureRepository repository;
    private volatile RecoveryRun recoveryRun;

    private S5RestartServerDriver(String mode, String fixtureId, S5CrashBoundary boundary) {
        this.mode = mode;
        this.fixtureId = fixtureId;
        this.boundary = boundary;
    }

    public static void initializeIfRequested() {
        String mode = System.getenv("SSA_S5_MODE");
        if (mode == null || mode.isBlank()) {
            return;
        }
        if (!MODE_CRASH.equals(mode) && !MODE_RECOVER.equals(mode)) {
            throw new IllegalArgumentException("SSA_S5_MODE must be crash or recover");
        }
        String fixtureId = requiredEnvironment("SSA_S5_FIXTURE_ID");
        S5CrashBoundary boundary = S5CrashBoundary.fromExternalName(requiredEnvironment("SSA_S5_BOUNDARY"));
        S5RestartServerDriver driver = new S5RestartServerDriver(mode, fixtureId, boundary);
        ServerLifecycleEvents.SERVER_STARTED.register(driver::onServerStarted);
        ServerTickEvents.START_SERVER_TICK.register(driver::onServerTick);
    }

    private void onServerStarted(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path fixtureDirectory = worldRoot
                .resolve("data")
                .resolve("smart_survival_architect")
                .resolve("s5")
                .resolve(fixtureId);
        Thread controller = new Thread(
                () -> runController(server, worldRoot, fixtureDirectory),
                "ssa-s5-controller");
        controller.setDaemon(false);
        controller.start();
    }

    private void runController(MinecraftServer server, Path worldRoot, Path fixtureDirectory) {
        try {
            if (MODE_CRASH.equals(mode)) {
                runCrash(server, worldRoot, fixtureDirectory);
            } else {
                runRecovery(server, worldRoot, fixtureDirectory);
            }
        } catch (Throwable failure) {
            failHard(worldRoot, failure);
        }
    }

    private void runCrash(MinecraftServer server, Path worldRoot, Path fixtureDirectory) {
        String worldIdentity = loadOrCreateWorldIdentity(worldRoot);
        OperationIntent intent = S5RestartScenario.intent(fixtureId);
        RestartFixture fixture = S5RestartScenario.fixture(fixtureId, worldIdentity);
        Path fixturePath = fixtureDirectory.resolve("fixture.properties");
        repository = RestartFixtureRepository.create(fixturePath, fixture, intent);
        Path walPath = fixtureDirectory.resolve("operation-intent.wal");
        S5RestartScenario scenario = new S5RestartScenario(boundary, this::haltAtBoundary);

        try (PersistenceExecutor persistence = new PersistenceExecutor("ssa-s5-persistence")) {
            OperationIntentStore store = new OperationIntentStore(walPath, persistence, scenario.appendProbe());
            if (boundary == S5CrashBoundary.FOREIGN_EVIDENCE) {
                store.prepare(intent).join();
                repository.setForeign(0);
                haltAtBoundary(boundary);
            }
            FabricMutationExecutor coordinator = new FabricMutationExecutor(store, server::execute, scenario.listener());
            coordinator.execute(intent, repository).join();
        }
        throw new IllegalStateException("S5 operation completed without reaching " + boundary.externalName());
    }

    private void runRecovery(MinecraftServer server, Path worldRoot, Path fixtureDirectory) {
        String worldIdentity = loadOrCreateWorldIdentity(worldRoot);
        OperationIntent intent = S5RestartScenario.intent(fixtureId);
        RestartFixture expectedFixture = S5RestartScenario.fixture(fixtureId, worldIdentity);
        repository = RestartFixtureRepository.open(fixtureDirectory.resolve("fixture.properties"));
        if (!expectedFixture.equals(repository.fixture())) {
            throw new IllegalStateException("persisted restart fixture identity changed across process restart");
        }
        String evidenceBefore = repository.evidenceSha256();
        Path walPath = fixtureDirectory.resolve("operation-intent.wal");

        CoordinatorResult first;
        CoordinatorResult second;
        Optional<OperationStatus> activeStatus;
        try (PersistenceExecutor persistence = new PersistenceExecutor("ssa-s5-persistence")) {
            OperationIntentStore store = new OperationIntentStore(walPath, persistence);
            FabricMutationExecutor coordinator = new FabricMutationExecutor(
                    store, server::execute, OperationBoundaryListener.NONE);
            first = coordinator.recover(repository).join();
            second = coordinator.recover(repository).join();
            activeStatus = store.loadActive().join().map(OperationIntent::status);
        }

        validateRecovery(intent, first, second, activeStatus);
        String evidenceAfter = repository.evidenceSha256();
        if (boundary == S5CrashBoundary.FOREIGN_EVIDENCE) {
            if (!evidenceBefore.equals(evidenceAfter)) {
                throw new IllegalStateException("quarantine recovery mutated exact fixture evidence");
            }
            recoveryRun = new RecoveryRun(first, second, activeStatus, evidenceBefore, evidenceAfter);
            finish(server, false);
            return;
        }

        repository.markRecoveryComplete(first.outcome().name());
        recoveryRun = new RecoveryRun(first, second, activeStatus, evidenceBefore, evidenceAfter);
        recoveryReady.set(true);
    }

    private void validateRecovery(
            OperationIntent intent,
            CoordinatorResult first,
            CoordinatorResult second,
            Optional<OperationStatus> activeStatus) {
        if (!boundary.expectedFirstOutcomes().contains(first.outcome())) {
            throw new IllegalStateException("unexpected first recovery outcome: " + first.outcome());
        }
        if (second.outcome() != boundary.expectedSecondOutcome()) {
            throw new IllegalStateException("unexpected second recovery outcome: " + second.outcome());
        }
        boolean allAfter = evidenceMatches(intent, true);
        boolean allBefore = evidenceMatches(intent, false);
        if (boundary.expectsCommittedEvidence()) {
            if (!allAfter || repository.commitCount() != 1) {
                throw new IllegalStateException("applied restart window did not finish exact journal evidence");
            }
        } else if (boundary == S5CrashBoundary.FOREIGN_EVIDENCE) {
            if (repository.commitCount() != 0
                    || activeStatus.orElse(null) != OperationStatus.QUARANTINED) {
                throw new IllegalStateException("foreign evidence did not remain durably quarantined");
            }
        } else if (!allBefore || repository.commitCount() != 0) {
            throw new IllegalStateException("pre-mutation restart window changed exact evidence");
        }
    }

    private boolean evidenceMatches(OperationIntent intent, boolean after) {
        var observations = repository.observe(intent).observations();
        for (int index = 0; index < observations.size(); index++) {
            var expected = after ? intent.deltas().get(index).after() : intent.deltas().get(index).before();
            if (!expected.equals(observations.get(index).value())) {
                return false;
            }
        }
        return true;
    }

    private void onServerTick(MinecraftServer server) {
        if (repository == null || !MODE_RECOVER.equals(mode)) {
            return;
        }
        if (!schedulingOpen.get()) {
            blockedSchedulingTicks.incrementAndGet();
            if (recoveryReady.get()) {
                schedulingOpen.set(true);
            }
            return;
        }
        if (schedulingRecorded.compareAndSet(false, true)) {
            if (!repository.scheduleOnce()) {
                failHard(server.getWorldPath(LevelResource.ROOT),
                        new IllegalStateException("restart scheduling ran more than once"));
                return;
            }
            finish(server, true);
        }
    }

    private void finish(MinecraftServer server, boolean scheduled) {
        RecoveryRun run = Objects.requireNonNull(recoveryRun, "recoveryRun");
        if (scheduled != boundary.allowsScheduling()) {
            throw new IllegalStateException("scheduling outcome does not match restart boundary contract");
        }
        if (repository.scheduleCount() != (scheduled ? 1 : 0)) {
            throw new IllegalStateException("restart fixture has an unexpected scheduling count");
        }
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path resultPath = repository.path().resolveSibling("result.properties");
        Properties result = new Properties();
        result.setProperty("status", "PASS");
        result.setProperty("fixture_id", fixtureId);
        result.setProperty("boundary", boundary.externalName());
        result.setProperty("world_identity", repository.fixture().worldIdentity());
        result.setProperty("world_path", portablePath(worldRoot));
        result.setProperty("first_outcome", run.first.outcome().name());
        result.setProperty("second_outcome", run.second.outcome().name());
        result.setProperty("active_status", run.activeStatus.map(Enum::name).orElse("NONE"));
        result.setProperty("apply_count", Integer.toString(repository.applyCount()));
        result.setProperty("commit_count", Integer.toString(repository.commitCount()));
        result.setProperty("schedule_count", Integer.toString(repository.scheduleCount()));
        result.setProperty("blocked_scheduling_ticks", Integer.toString(blockedSchedulingTicks.get()));
        result.setProperty("evidence_sha256_before", run.evidenceBefore);
        result.setProperty("evidence_sha256_after", run.evidenceAfter);
        result.setProperty("metadata_verified", "true");
        result.setProperty("recovery_before_scheduling", "true");
        writeForcedProperties(resultPath, result, "Smart Survival Architect S5 process result");
        System.out.printf(
                "SSA_S5_RECOVERY fixture=%s boundary=%s first=%s second=%s scheduled=%s world=%s metadata=true exact=true%n",
                fixtureId,
                boundary.externalName(),
                run.first.outcome(),
                run.second.outcome(),
                scheduled,
                repository.fixture().worldIdentity());
        System.out.flush();
        server.halt(false);
    }

    private void haltAtBoundary(S5CrashBoundary reached) {
        System.out.printf(
                "SSA_S5_CRASH fixture=%s boundary=%s exit=%d%n",
                fixtureId,
                reached.externalName(),
                INJECTED_CRASH_EXIT_CODE);
        System.out.flush();
        Runtime.getRuntime().halt(INJECTED_CRASH_EXIT_CODE);
    }

    private void failHard(Path worldRoot, Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        System.err.printf(
                "SSA_S5_FAILURE fixture=%s boundary=%s world=%s type=%s message=%s%n",
                fixtureId,
                boundary.externalName(),
                portablePath(worldRoot),
                cause.getClass().getName(),
                cause.getMessage());
        cause.printStackTrace(System.err);
        System.err.flush();
        Runtime.getRuntime().halt(HARNESS_FAILURE_EXIT_CODE);
    }

    private static String loadOrCreateWorldIdentity(Path worldRoot) {
        Path path = worldRoot.resolve("data").resolve("smart_survival_architect").resolve("s5-world-id.txt");
        try {
            if (Files.exists(path)) {
                return Files.readString(path).trim();
            }
            Files.createDirectories(path.getParent());
            String identity = UUID.randomUUID().toString();
            byte[] bytes = (identity + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            return identity;
        } catch (IOException exception) {
            throw new UncheckedIOException("could not load S5 world identity", exception);
        }
    }

    private static void writeForcedProperties(Path path, Properties properties, String comment) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            properties.store(bytes, comment);
            try (FileChannel channel = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(bytes.toByteArray()));
                channel.force(true);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("could not persist S5 result", exception);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required for the S5 restart harness");
        }
        return value;
    }

    private static String portablePath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private record RecoveryRun(
            CoordinatorResult first,
            CoordinatorResult second,
            Optional<OperationStatus> activeStatus,
            String evidenceBefore,
            String evidenceAfter) {
    }
}
