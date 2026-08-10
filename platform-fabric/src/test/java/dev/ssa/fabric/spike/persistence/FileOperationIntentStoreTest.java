package dev.ssa.fabric.spike.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.construction.spike.persistence.BlockStateSnapshot;
import dev.ssa.construction.spike.persistence.DropPolicy;
import dev.ssa.construction.spike.persistence.InventoryDelta;
import dev.ssa.construction.spike.persistence.OperationIntent;
import dev.ssa.construction.spike.persistence.OperationKind;
import dev.ssa.construction.spike.persistence.OperationStatus;
import dev.ssa.construction.spike.persistence.StackSnapshot;
import dev.ssa.construction.spike.persistence.WorldDelta;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileOperationIntentStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void durableStatusesAndClearSurviveReopen() throws Exception {
        Path wal = temporaryDirectory.resolve("operation-intent.wal");
        OperationIntent intent = placementIntent("place-1");

        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            FileOperationIntentStore store = new FileOperationIntentStore(wal, executor);
            DurableAcknowledgement prepared = store.prepare(intent).join();
            assertEquals(OperationStatus.PREPARED, prepared.status());
            assertTrue(prepared.ioThread().startsWith("ssa-persistence-"));
        }
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            FileOperationIntentStore reopened = new FileOperationIntentStore(wal, executor);
            assertEquals(OperationStatus.PREPARED, reopened.readActive().orElseThrow().status());
            reopened.transition("place-1", OperationStatus.COMMITTED).join();
        }
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            FileOperationIntentStore reopened = new FileOperationIntentStore(wal, executor);
            assertEquals(OperationStatus.COMMITTED, reopened.readActive().orElseThrow().status());
            reopened.clear("place-1").join();
        }
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            assertTrue(new FileOperationIntentStore(wal, executor).readActive().isEmpty());
        }
    }

    @Test
    void incompleteFinalFrameIsIgnored() throws Exception {
        Path wal = temporaryDirectory.resolve("truncated.wal");
        OperationIntent intent = placementIntent("place-1");
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            new FileOperationIntentStore(wal, executor).prepare(intent).join();
        }
        Files.write(wal, new byte[] {0x53, 0x53, 0x41, 0x34, 0, 1}, StandardOpenOption.APPEND);

        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            OperationIntent recovered = new FileOperationIntentStore(wal, executor).readActive().orElseThrow();
            assertEquals(intent, recovered);
        }
    }

    @Test
    void checksumInvalidCompleteFrameFailsClosed() throws Exception {
        Path wal = temporaryDirectory.resolve("corrupt.wal");
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            new FileOperationIntentStore(wal, executor).prepare(placementIntent("place-1")).join();
        }
        byte[] bytes = Files.readAllBytes(wal);
        bytes[bytes.length - 1] ^= 0x5A;
        Files.write(wal, bytes);

        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            FileOperationIntentStore reopened = new FileOperationIntentStore(wal, executor);
            assertThrows(WalCorruptionException.class, reopened::readActive);
        }
    }

    @Test
    void oneActiveIntentRejectsASecondOperation() {
        Path wal = temporaryDirectory.resolve("single-active.wal");
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            FileOperationIntentStore store = new FileOperationIntentStore(wal, executor);
            store.prepare(placementIntent("place-1")).join();

            CompletionException failure = assertThrows(CompletionException.class,
                    () -> store.prepare(placementIntent("place-2")).join());
            assertTrue(failure.getCause() instanceof IllegalStateException);
        }
    }

    @Test
    void appendBeforeForceDoesNotAcknowledgeAndCannotEnableSideEffects() {
        Path wal = temporaryDirectory.resolve("unacknowledged.wal");
        List<String> calls = new ArrayList<>();
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            FileOperationIntentStore store = new FileOperationIntentStore(wal, executor, path -> {
                calls.add(Thread.currentThread().getName());
                throw new IOException("simulated crash after write before force");
            });

            assertThrows(CompletionException.class, () -> store.prepare(placementIntent("place-1")).join());
        }

        assertEquals(1, calls.size());
        assertTrue(calls.getFirst().startsWith("ssa-persistence-"));
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            FileOperationIntentStore reopened = new FileOperationIntentStore(wal, executor);
            assertEquals(OperationStatus.PREPARED, reopened.readActive().orElseThrow().status());
        }
    }

    @Test
    void recordsTwoHundredDurableAcknowledgementLatencies() {
        Path wal = temporaryDirectory.resolve("latency.wal");
        long[] samples = new long[200];
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            FileOperationIntentStore store = new FileOperationIntentStore(wal, executor);
            for (int index = 0; index < samples.length; index++) {
                DurableAcknowledgement acknowledgement = store.prepare(placementIntent("profile-" + index)).join();
                samples[index] = acknowledgement.latencyNanos();
                store.clear("profile-" + index).join();
            }
        }

        Arrays.sort(samples);
        long p50Micros = samples[99] / 1_000;
        long p95Micros = samples[189] / 1_000;
        assertTrue(p50Micros >= 0);
        assertTrue(p95Micros >= p50Micros);
        System.out.printf(
                "SSA_S4_FSYNC samples=200 p50_us=%d p95_us=%d io_thread=ssa-persistence-%n",
                p50Micros,
                p95Micros);
    }

    @Test
    void codecRoundTripsMaterialAndWorldIntentsExactly() throws Exception {
        for (OperationIntent intent : List.of(materialIntent("transfer-1"), placementIntent("place-1"))) {
            assertEquals(intent, OperationIntentCodec.decode(OperationIntentCodec.encode(intent)));
        }
    }

    private static OperationIntent materialIntent(String id) {
        byte[] components = bytes("{minecraft:custom_name='Exact'}");
        return OperationIntent.prepared(id, "job-1", 4, OperationKind.MATERIAL_TRANSFER, List.of(
                new InventoryDelta("chest", 2, 0,
                        StackSnapshot.of("minecraft:oak_planks", 9, components),
                        StackSnapshot.of("minecraft:oak_planks", 5, components)),
                new InventoryDelta("builder", 7, 3,
                        StackSnapshot.empty(),
                        StackSnapshot.of("minecraft:oak_planks", 4, components))));
    }

    private static OperationIntent placementIntent(String id) {
        return OperationIntent.prepared(id, "job-1", 5, OperationKind.WORLD_MUTATION, List.of(
                new InventoryDelta("builder", 7, 3,
                        StackSnapshot.of("minecraft:oak_stairs", 1, bytes("{custom_name='Roof'}")),
                        StackSnapshot.empty()),
                new WorldDelta("minecraft:overworld", 4, 70, -2,
                        BlockStateSnapshot.of("minecraft:air", bytes("")),
                        BlockStateSnapshot.of("minecraft:oak_stairs", bytes("facing=east;half=top")),
                        DropPolicy.NOT_APPLICABLE)));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
