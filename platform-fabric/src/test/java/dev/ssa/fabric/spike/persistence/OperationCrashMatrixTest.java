package dev.ssa.fabric.spike.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.construction.spike.persistence.BlockStateSnapshot;
import dev.ssa.construction.spike.persistence.DropPolicy;
import dev.ssa.construction.spike.persistence.EvidenceObservation;
import dev.ssa.construction.spike.persistence.EvidenceSnapshot;
import dev.ssa.construction.spike.persistence.InventoryDelta;
import dev.ssa.construction.spike.persistence.ObservedEvidence;
import dev.ssa.construction.spike.persistence.OperationDelta;
import dev.ssa.construction.spike.persistence.OperationIntent;
import dev.ssa.construction.spike.persistence.OperationKind;
import dev.ssa.construction.spike.persistence.OperationStatus;
import dev.ssa.construction.spike.persistence.StackSnapshot;
import dev.ssa.construction.spike.persistence.WorldDelta;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OperationCrashMatrixTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void materialTransferSurvivesEveryCrashWindow() throws Exception {
        runCrashMatrix(materialTransfer(), "material_transfer");
    }

    @Test
    void placementConsumptionSurvivesEveryCrashWindow() throws Exception {
        runCrashMatrix(placement(), "placement_consumption");
    }

    @Test
    void atomicMultiBlockMutationSurvivesEveryCrashWindow() throws Exception {
        runCrashMatrix(atomicMutation(), "atomic_multi_block");
    }

    @Test
    void foreignEvidenceQuarantinesWithoutAutomaticMutation() throws Exception {
        OperationIntent intent = materialTransfer();
        Path directory = temporaryDirectory.resolve("unknown");
        Files.createDirectories(directory);
        Path wal = directory.resolve("intent.wal");
        Path evidence = directory.resolve("evidence.bin");

        try (PersistenceExecutor persistence = new PersistenceExecutor("ssa-persistence")) {
            new FileOperationIntentStore(wal, persistence).prepare(intent).join();
        }
        FixtureEvidencePort fixture = new FixtureEvidencePort(evidence, intent);
        fixture.setForeign(0);

        CoordinatorResult first;
        CoordinatorResult second;
        try (PersistenceExecutor persistence = new PersistenceExecutor("ssa-persistence")) {
            FileOperationIntentStore store = new FileOperationIntentStore(wal, persistence);
            ExecutorService server = newServerExecutor();
            try {
                OperationCoordinator coordinator = new OperationCoordinator(store, server, OperationBoundaryListener.NONE);
                first = coordinator.recover(fixture).join();
                long quarantinedWalLength = Files.size(wal);
                second = coordinator.recover(fixture).join();
                assertEquals(quarantinedWalLength, Files.size(wal),
                        "idempotent quarantine appended another WAL record");
                assertEquals(OperationStatus.QUARANTINED, store.loadActive().join().orElseThrow().status());
            } finally {
                close(server);
            }
        }

        assertEquals(CoordinatorOutcome.QUARANTINED, first.outcome());
        assertEquals(CoordinatorOutcome.QUARANTINED, second.outcome());
        assertEquals(0, fixture.applyCount());
        assertFalse(fixture.committed());
        System.out.println("SSA_S4_CRASH operation=material_transfer point=foreign_evidence decision=QUARANTINED exact=true idempotent=true");
    }

    @Test
    void executeRejectsForeignBeforeEvidenceWithoutPreparing() throws Exception {
        OperationIntent intent = placement();
        Path wal = temporaryDirectory.resolve("foreign-before-execute.wal");
        FixtureEvidencePort fixture = new FixtureEvidencePort(
                temporaryDirectory.resolve("foreign-before-execute.bin"), intent);
        fixture.setForeign(0);

        try (PersistenceExecutor persistence = new PersistenceExecutor("ssa-persistence")) {
            FileOperationIntentStore store = new FileOperationIntentStore(wal, persistence);
            ExecutorService server = newServerExecutor();
            try {
                OperationCoordinator coordinator = new OperationCoordinator(store, server, OperationBoundaryListener.NONE);
                assertThrows(CompletionException.class, () -> coordinator.execute(intent, fixture).join());
                assertTrue(store.loadActive().join().isEmpty());
            } finally {
                close(server);
            }
        }
        assertEquals(0, fixture.applyCount());
        assertFalse(fixture.committed());
    }

    @Test
    void executeVerifiesAllAfterEvidenceBeforeCommit() throws Exception {
        OperationIntent intent = placement();
        Path wal = temporaryDirectory.resolve("failed-after-verification.wal");
        AtomicBoolean committed = new AtomicBoolean();
        OperationEvidencePort faultyEvidence = new OperationEvidencePort() {
            @Override
            public ObservedEvidence observe(OperationIntent observedIntent) {
                List<EvidenceObservation> observations = observedIntent.deltas().stream()
                        .map(delta -> new EvidenceObservation(delta.evidenceKey(), delta.before()))
                        .toList();
                return new ObservedEvidence(observations);
            }

            @Override
            public void apply(OperationDelta delta) {
            }

            @Override
            public boolean isCommitted(String operationId) {
                return committed.get();
            }

            @Override
            public void commit(OperationIntent committedIntent) {
                committed.set(true);
            }
        };

        try (PersistenceExecutor persistence = new PersistenceExecutor("ssa-persistence")) {
            FileOperationIntentStore store = new FileOperationIntentStore(wal, persistence);
            ExecutorService server = newServerExecutor();
            try {
                OperationCoordinator coordinator = new OperationCoordinator(
                        store, server, OperationBoundaryListener.NONE);
                assertThrows(CompletionException.class, () -> coordinator.execute(intent, faultyEvidence).join());
                assertEquals(OperationStatus.PREPARED, store.loadActive().join().orElseThrow().status());
            } finally {
                close(server);
            }
        }
        assertFalse(committed.get(), "journal/task commit ran without exact all-after evidence");
    }

    @Test
    void terminalIntentWithForeignEvidenceEscalatesToStickyQuarantine() throws Exception {
        for (OperationStatus terminal : List.of(OperationStatus.ABORTED, OperationStatus.COMMITTED)) {
            OperationIntent intent = materialTransfer();
            Path directory = temporaryDirectory.resolve("terminal-foreign-" + terminal.name().toLowerCase());
            Files.createDirectories(directory);
            Path wal = directory.resolve("intent.wal");
            FixtureEvidencePort fixture = new FixtureEvidencePort(directory.resolve("evidence.bin"), intent);
            fixture.setForeign(0);

            try (PersistenceExecutor persistence = new PersistenceExecutor("ssa-persistence")) {
                FileOperationIntentStore store = new FileOperationIntentStore(wal, persistence);
                store.prepare(intent).join();
                store.transition(intent.operationId(), terminal).join();
                ExecutorService server = newServerExecutor();
                try {
                    OperationCoordinator coordinator = new OperationCoordinator(
                            store, server, OperationBoundaryListener.NONE);
                    assertEquals(CoordinatorOutcome.QUARANTINED, coordinator.recover(fixture).join().outcome());
                    long quarantinedWalLength = Files.size(wal);
                    assertEquals(CoordinatorOutcome.QUARANTINED, coordinator.recover(fixture).join().outcome());
                    assertEquals(quarantinedWalLength, Files.size(wal));
                    assertEquals(OperationStatus.QUARANTINED, store.loadActive().join().orElseThrow().status());
                } finally {
                    close(server);
                }
            }
        }
    }

    @Test
    void serverEventLoopRemainsResponsiveWhileFsyncIsBlocked() throws Exception {
        OperationIntent intent = placement();
        Path wal = temporaryDirectory.resolve("thread-handoff.wal");
        FixtureEvidencePort fixture = new FixtureEvidencePort(
                temporaryDirectory.resolve("thread-handoff.bin"), intent);
        CountDownLatch writeReached = new CountDownLatch(1);
        CountDownLatch releaseFsync = new CountDownLatch(1);
        AtomicBoolean firstAppend = new AtomicBoolean(true);
        AtomicReference<String> ioThread = new AtomicReference<>();

        try (PersistenceExecutor persistence = new PersistenceExecutor("ssa-persistence")) {
            FileOperationIntentStore store = new FileOperationIntentStore(wal, persistence, path -> {
                if (firstAppend.compareAndSet(true, false)) {
                    ioThread.set(Thread.currentThread().getName());
                    writeReached.countDown();
                    try {
                        if (!releaseFsync.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("timed out waiting to release fsync");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted while delaying fsync", exception);
                    }
                }
            });
            ExecutorService server = newServerExecutor();
            try {
                OperationCoordinator coordinator = new OperationCoordinator(store, server, OperationBoundaryListener.NONE);
                CompletableFuture<CoordinatorResult> execution = coordinator.execute(intent, fixture);
                assertTrue(writeReached.await(5, TimeUnit.SECONDS), "persistence append was not reached");

                String heartbeatThread = CompletableFuture.supplyAsync(
                        () -> Thread.currentThread().getName(), server).get(1, TimeUnit.SECONDS);
                assertTrue(heartbeatThread.startsWith("ssa-server-"));
                assertFalse(execution.isDone(), "operation completed before durable acknowledgement");

                releaseFsync.countDown();
                assertEquals(CoordinatorOutcome.COMMITTED, execution.join().outcome());
            } finally {
                releaseFsync.countDown();
                close(server);
            }
        }

        assertTrue(ioThread.get().startsWith("ssa-persistence-"));
        assertTrue(fixture.mutationThreads().stream().allMatch(name -> name.startsWith("ssa-server-")));
        System.out.printf(
                "SSA_S4_THREADS fsync=%s mutation=%s server_heartbeat=responsive%n",
                ioThread.get(),
                fixture.mutationThreads().getFirst());
    }

    private void runCrashMatrix(OperationIntent intent, String operationLabel) throws Exception {
        List<CrashTarget> targets = new ArrayList<>();
        targets.add(CrashTarget.beforePrepare());
        targets.add(CrashTarget.afterAppendBeforeAck());
        targets.add(CrashTarget.afterPrepared());
        for (int index = 0; index < intent.deltas().size(); index++) {
            targets.add(CrashTarget.afterDelta(index));
        }
        targets.add(CrashTarget.afterAllDeltas());
        targets.add(CrashTarget.afterJournalCommit());
        targets.add(CrashTarget.afterWalCommit());
        targets.add(CrashTarget.afterClear());

        for (CrashTarget target : targets) {
            runCrashCase(intent, operationLabel, target);
        }
    }

    private void runCrashCase(OperationIntent intent, String operationLabel, CrashTarget target) throws Exception {
        Path directory = temporaryDirectory.resolve(operationLabel).resolve(target.label());
        Files.createDirectories(directory);
        Path wal = directory.resolve("intent.wal");
        Path evidencePath = directory.resolve("evidence.bin");
        FixtureEvidencePort fixture = new FixtureEvidencePort(evidencePath, intent);

        AppendProbe appendProbe = target.phase() == CrashPhase.AFTER_APPEND_BEFORE_ACK
                ? path -> { throw new InjectedCrash(target.label()); }
                : AppendProbe.NONE;
        try (PersistenceExecutor persistence = new PersistenceExecutor("ssa-persistence")) {
            FileOperationIntentStore store = new FileOperationIntentStore(wal, persistence, appendProbe);
            ExecutorService server = newServerExecutor();
            try {
                OperationCoordinator coordinator = new OperationCoordinator(store, server, crashListener(target));
                assertThrows(CompletionException.class, () -> coordinator.execute(intent, fixture).join());
            } finally {
                close(server);
            }
        }

        FixtureEvidencePort reopenedFixture = new FixtureEvidencePort(evidencePath, intent);
        CoordinatorResult firstRecovery;
        CoordinatorResult secondRecovery;
        try (PersistenceExecutor persistence = new PersistenceExecutor("ssa-persistence")) {
            FileOperationIntentStore reopenedStore = new FileOperationIntentStore(wal, persistence);
            ExecutorService server = newServerExecutor();
            try {
                OperationCoordinator recovery = new OperationCoordinator(
                        reopenedStore, server, OperationBoundaryListener.NONE);
                firstRecovery = recovery.recover(reopenedFixture).join();
                secondRecovery = recovery.recover(reopenedFixture).join();
                assertTrue(reopenedStore.loadActive().join().isEmpty(), "supported recovery left an active intent");
            } finally {
                close(server);
            }
        }

        boolean shouldCommit = target.hasAppliedEvidence();
        if (shouldCommit) {
            assertTrue(reopenedFixture.allAfter(), "supported prefix did not reach exact all-after evidence");
            assertTrue(reopenedFixture.committed(), "supported prefix did not commit journal/task evidence");
            assertEquals(1, reopenedFixture.commitCount(), "journal/task commit was duplicated");
            assertConservation(intent, reopenedFixture);
        } else {
            assertTrue(reopenedFixture.allBefore(), "pre-mutation crash changed exact evidence");
            assertFalse(reopenedFixture.committed(), "pre-mutation crash committed journal/task evidence");
        }
        assertEquals(CoordinatorOutcome.NO_ACTIVE_INTENT, secondRecovery.outcome());
        System.out.printf(
                "SSA_S4_CRASH operation=%s point=%s decision=%s exact=true idempotent=true%n",
                operationLabel,
                target.label(),
                firstRecovery.outcome());
    }

    private static OperationBoundaryListener crashListener(CrashTarget target) {
        return new OperationBoundaryListener() {
            @Override
            public void beforePrepare() {
                crash(CrashPhase.BEFORE_PREPARE, -1);
            }

            @Override
            public void afterPrepared(DurableAcknowledgement acknowledgement) {
                crash(CrashPhase.AFTER_PREPARED, -1);
            }

            @Override
            public void afterDelta(int deltaIndex) {
                crash(CrashPhase.AFTER_DELTA, deltaIndex);
            }

            @Override
            public void afterAllDeltas() {
                crash(CrashPhase.AFTER_ALL_DELTAS, -1);
            }

            @Override
            public void afterJournalCommit() {
                crash(CrashPhase.AFTER_JOURNAL_COMMIT, -1);
            }

            @Override
            public void afterCommit() {
                crash(CrashPhase.AFTER_COMMIT, -1);
            }

            @Override
            public void afterClear() {
                crash(CrashPhase.AFTER_CLEAR, -1);
            }

            private void crash(CrashPhase phase, int deltaIndex) {
                if (target.phase() == phase && (phase != CrashPhase.AFTER_DELTA || target.deltaIndex() == deltaIndex)) {
                    throw new InjectedCrash(target.label());
                }
            }
        };
    }

    private static void assertConservation(OperationIntent intent, FixtureEvidencePort fixture) {
        int beforeInventory = inventoryCount(intent, false);
        int afterInventory = inventoryCount(intent, true);
        if (intent.kind() == OperationKind.MATERIAL_TRANSFER) {
            assertEquals(beforeInventory, afterInventory, "material transfer duplicated or lost items");
        } else if (intent.deltas().stream().anyMatch(InventoryDelta.class::isInstance)) {
            assertEquals(beforeInventory - 1, afterInventory, "placement did not consume exactly one item");
        }
        assertTrue(fixture.allAfter());
    }

    private static int inventoryCount(OperationIntent intent, boolean after) {
        return intent.deltas().stream()
                .filter(InventoryDelta.class::isInstance)
                .map(InventoryDelta.class::cast)
                .mapToInt(delta -> (after ? delta.after() : delta.before()).count())
                .sum();
    }

    private static OperationIntent materialTransfer() {
        byte[] components = bytes("{custom_name='Batch'}");
        return OperationIntent.prepared("transfer-1", "job-1", 3, OperationKind.MATERIAL_TRANSFER, List.of(
                new InventoryDelta("chest", 2, 0,
                        StackSnapshot.of("minecraft:oak_planks", 8, components),
                        StackSnapshot.of("minecraft:oak_planks", 4, components)),
                new InventoryDelta("builder", 7, 1,
                        StackSnapshot.empty(),
                        StackSnapshot.of("minecraft:oak_planks", 4, components))));
    }

    private static OperationIntent placement() {
        return OperationIntent.prepared("place-1", "job-1", 4, OperationKind.WORLD_MUTATION, List.of(
                new InventoryDelta("builder", 7, 1,
                        StackSnapshot.of("minecraft:oak_stairs", 1, bytes("{custom_name='Roof'}")),
                        StackSnapshot.empty()),
                new WorldDelta("minecraft:overworld", 2, 70, 4,
                        block("minecraft:air", ""),
                        block("minecraft:oak_stairs", "facing=north;half=top"),
                        DropPolicy.NOT_APPLICABLE)));
    }

    private static OperationIntent atomicMutation() {
        return OperationIntent.prepared("atomic-1", "job-1", 5, OperationKind.WORLD_MUTATION, List.of(
                new WorldDelta("minecraft:overworld", 0, 64, 0,
                        block("minecraft:air", ""), block("minecraft:stone", ""), DropPolicy.NOT_APPLICABLE),
                new WorldDelta("minecraft:overworld", 1, 64, 0,
                        block("minecraft:air", ""), block("minecraft:oak_stairs", "facing=east"), DropPolicy.NOT_APPLICABLE),
                new WorldDelta("minecraft:overworld", 2, 64, 0,
                        block("minecraft:dirt", ""), block("minecraft:air", ""), DropPolicy.SUPPRESS)));
    }

    private static BlockStateSnapshot block(String id, String properties) {
        return BlockStateSnapshot.of(id, bytes(properties));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static ExecutorService newServerExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "ssa-server-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void close(ExecutorService executor) throws InterruptedException {
        executor.shutdown();
        assertTrue(executor.awaitTermination(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS));
    }

    private enum CrashPhase {
        BEFORE_PREPARE,
        AFTER_APPEND_BEFORE_ACK,
        AFTER_PREPARED,
        AFTER_DELTA,
        AFTER_ALL_DELTAS,
        AFTER_JOURNAL_COMMIT,
        AFTER_COMMIT,
        AFTER_CLEAR
    }

    private record CrashTarget(CrashPhase phase, int deltaIndex, String label) {
        static CrashTarget beforePrepare() {
            return new CrashTarget(CrashPhase.BEFORE_PREPARE, -1, "before_prepared_append");
        }

        static CrashTarget afterAppendBeforeAck() {
            return new CrashTarget(CrashPhase.AFTER_APPEND_BEFORE_ACK, -1, "after_append_before_fsync_ack");
        }

        static CrashTarget afterPrepared() {
            return new CrashTarget(CrashPhase.AFTER_PREPARED, -1, "after_durable_prepared");
        }

        static CrashTarget afterDelta(int index) {
            return new CrashTarget(CrashPhase.AFTER_DELTA, index, "after_delta_" + (index + 1));
        }

        static CrashTarget afterAllDeltas() {
            return new CrashTarget(CrashPhase.AFTER_ALL_DELTAS, -1, "after_all_deltas_before_commit");
        }

        static CrashTarget afterJournalCommit() {
            return new CrashTarget(
                    CrashPhase.AFTER_JOURNAL_COMMIT,
                    -1,
                    "after_journal_commit_before_wal_commit");
        }

        static CrashTarget afterWalCommit() {
            return new CrashTarget(CrashPhase.AFTER_COMMIT, -1, "after_wal_commit_before_clear");
        }

        static CrashTarget afterClear() {
            return new CrashTarget(CrashPhase.AFTER_CLEAR, -1, "after_clear_checkpoint");
        }

        boolean hasAppliedEvidence() {
            return phase == CrashPhase.AFTER_DELTA
                    || phase == CrashPhase.AFTER_ALL_DELTAS
                    || phase == CrashPhase.AFTER_JOURNAL_COMMIT
                    || phase == CrashPhase.AFTER_COMMIT
                    || phase == CrashPhase.AFTER_CLEAR;
        }
    }

    private static final class InjectedCrash extends RuntimeException {
        private InjectedCrash(String point) {
            super("injected crash at " + point);
        }
    }

    private static final class FixtureEvidencePort implements OperationEvidencePort {
        private final Path path;
        private final OperationIntent intent;
        private final boolean[] after;
        private final List<String> mutationThreads = new ArrayList<>();
        private int commitCount;
        private int foreignIndex = -1;
        private int applyCount;

        private FixtureEvidencePort(Path path, OperationIntent intent) throws IOException {
            this.path = path;
            this.intent = intent;
            this.after = new boolean[intent.deltas().size()];
            if (Files.exists(path)) {
                ByteBuffer state = ByteBuffer.wrap(Files.readAllBytes(path));
                int count = state.getInt();
                if (count != after.length) {
                    throw new IOException("fixture evidence count changed");
                }
                for (int index = 0; index < after.length; index++) {
                    after[index] = state.get() != 0;
                }
                commitCount = state.getInt();
                foreignIndex = state.getInt();
            } else {
                persist();
            }
        }

        @Override
        public ObservedEvidence observe(OperationIntent observedIntent) {
            assertEquals(intent.operationId(), observedIntent.operationId());
            List<EvidenceObservation> observations = new ArrayList<>();
            for (int index = 0; index < intent.deltas().size(); index++) {
                OperationDelta delta = intent.deltas().get(index);
                EvidenceSnapshot value = after[index] ? delta.after() : delta.before();
                if (index == foreignIndex) {
                    value = foreign(value);
                }
                observations.add(new EvidenceObservation(delta.evidenceKey(), value));
            }
            return new ObservedEvidence(observations);
        }

        @Override
        public void apply(OperationDelta delta) {
            int index = intent.deltas().indexOf(delta);
            if (index < 0 || after[index] || foreignIndex == index) {
                throw new IllegalStateException("delta is not at exact before evidence: " + delta.evidenceKey());
            }
            after[index] = true;
            applyCount++;
            mutationThreads.add(Thread.currentThread().getName());
            persistUnchecked();
        }

        @Override
        public boolean isCommitted(String operationId) {
            assertEquals(intent.operationId(), operationId);
            return commitCount > 0;
        }

        @Override
        public void commit(OperationIntent committedIntent) {
            assertEquals(intent.operationId(), committedIntent.operationId());
            commitCount++;
            mutationThreads.add(Thread.currentThread().getName());
            persistUnchecked();
        }

        void setForeign(int index) throws IOException {
            foreignIndex = index;
            persist();
        }

        boolean allBefore() {
            for (boolean value : after) {
                if (value) {
                    return false;
                }
            }
            return foreignIndex < 0;
        }

        boolean allAfter() {
            for (boolean value : after) {
                if (!value) {
                    return false;
                }
            }
            return foreignIndex < 0;
        }

        boolean committed() {
            return commitCount > 0;
        }

        int commitCount() {
            return commitCount;
        }

        int applyCount() {
            return applyCount;
        }

        List<String> mutationThreads() {
            return List.copyOf(mutationThreads);
        }

        private void persistUnchecked() {
            try {
                persist();
            } catch (IOException exception) {
                throw new IllegalStateException("could not persist fixture evidence", exception);
            }
        }

        private void persist() throws IOException {
            ByteBuffer state = ByteBuffer.allocate(Integer.BYTES + after.length + Integer.BYTES + Integer.BYTES);
            state.putInt(after.length);
            for (boolean value : after) {
                state.put((byte) (value ? 1 : 0));
            }
            state.putInt(commitCount);
            state.putInt(foreignIndex);
            state.flip();
            try (FileChannel channel = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                while (state.hasRemaining()) {
                    channel.write(state);
                }
                channel.force(true);
            }
        }

        private static EvidenceSnapshot foreign(EvidenceSnapshot value) {
            if (value instanceof StackSnapshot stack) {
                return stack.count() == 0
                        ? StackSnapshot.of("minecraft:diamond", 1, bytes("foreign"))
                        : StackSnapshot.of(stack.itemId(), stack.count(), bytes("foreign-components"));
            }
            BlockStateSnapshot block = (BlockStateSnapshot) value;
            return BlockStateSnapshot.of(block.blockId(), bytes("foreign-properties"));
        }
    }
}
