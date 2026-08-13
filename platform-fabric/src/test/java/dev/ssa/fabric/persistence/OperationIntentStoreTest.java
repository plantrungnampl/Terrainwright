package dev.ssa.fabric.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.construction.operation.BlockStateSnapshot;
import dev.ssa.construction.operation.DropPolicy;
import dev.ssa.construction.operation.InventoryDelta;
import dev.ssa.construction.operation.OperationIntent;
import dev.ssa.construction.operation.OperationDelta;
import dev.ssa.construction.operation.OperationKind;
import dev.ssa.construction.operation.OperationStatus;
import dev.ssa.construction.operation.StackSnapshot;
import dev.ssa.construction.operation.WorldDelta;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OperationIntentStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void durableStatusesAndClearSurviveReopen() throws Exception {
        Path wal = temporaryDirectory.resolve("operation-intent.wal");
        OperationIntent intent = placementIntent("place-1");

        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            OperationIntentStore store = new OperationIntentStore(wal, executor);
            DurableAcknowledgement prepared = store.prepare(intent).join();
            assertEquals(OperationStatus.PREPARED, prepared.status());
            assertTrue(prepared.ioThread().startsWith("ssa-persistence-"));
        }
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            OperationIntentStore reopened = new OperationIntentStore(wal, executor);
            assertEquals(OperationStatus.PREPARED, reopened.loadActive().join().orElseThrow().status());
            reopened.transition("place-1", OperationStatus.COMMITTED).join();
        }
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            OperationIntentStore reopened = new OperationIntentStore(wal, executor);
            assertEquals(OperationStatus.COMMITTED, reopened.loadActive().join().orElseThrow().status());
            reopened.clear("place-1").join();
        }
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            assertTrue(new OperationIntentStore(wal, executor).loadActive().join().isEmpty());
        }
    }

    @Test
    void incompleteFinalFrameIsIgnored() throws Exception {
        Path wal = temporaryDirectory.resolve("truncated.wal");
        OperationIntent intent = placementIntent("place-1");
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            new OperationIntentStore(wal, executor).prepare(intent).join();
        }
        Files.write(wal, new byte[] {0x53, 0x53, 0x41, 0x34, 0, 1}, StandardOpenOption.APPEND);

        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            OperationIntent recovered = new OperationIntentStore(wal, executor).loadActive().join().orElseThrow();
            assertEquals(intent, recovered);
        }
    }

    @Test
    void transitionAfterIncompleteTailReplacesTheUnacknowledgedBytes() throws Exception {
        Path wal = temporaryDirectory.resolve("truncated-then-append.wal");
        OperationIntent intent = placementIntent("place-1");
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            new OperationIntentStore(wal, executor).prepare(intent).join();
        }
        Files.write(wal, new byte[] {0x53, 0x53, 0x41, 0x34, 0, 1}, StandardOpenOption.APPEND);

        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            OperationIntentStore recovered = new OperationIntentStore(wal, executor);
            recovered.transition("place-1", OperationStatus.COMMITTED).join();
        }
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            OperationIntentStore reopened = new OperationIntentStore(wal, executor);
            assertEquals(OperationStatus.COMMITTED, reopened.loadActive().join().orElseThrow().status());
            reopened.clear("place-1").join();
            assertTrue(reopened.loadActive().join().isEmpty());
        }
    }

    @Test
    void truncationInsideHeaderPayloadOrFooterIsReplacedAtTheLastValidFrame() throws Exception {
        Path source = temporaryDirectory.resolve("truncation-source.wal");
        OperationIntent intent = placementIntent("place-1");
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            OperationIntentStore store = new OperationIntentStore(source, executor);
            store.prepare(intent).join();
            store.transition(intent.operationId(), OperationStatus.COMMITTED).join();
        }
        byte[] complete = Files.readAllBytes(source);
        int[] starts = frameStarts(complete);
        int secondFrame = starts[1];
        int secondPayloadLength = ByteBuffer.wrap(complete, secondFrame + 7, Integer.BYTES).getInt();
        int[] cutPoints = {
                secondFrame + 6,
                secondFrame + 19 + secondPayloadLength / 2,
                secondFrame + 19 + secondPayloadLength + Integer.BYTES
        };

        for (int cutPoint : cutPoints) {
            Path wal = temporaryDirectory.resolve("truncated-at-" + cutPoint + ".wal");
            Files.write(wal, Arrays.copyOf(complete, cutPoint));
            try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
                OperationIntentStore store = new OperationIntentStore(wal, executor);
                store.transition(intent.operationId(), OperationStatus.COMMITTED).join();
            }
            try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
                assertEquals(
                        OperationStatus.COMMITTED,
                        new OperationIntentStore(wal, executor).loadActive().join().orElseThrow().status());
            }
        }
    }

    @Test
    void checksumInvalidCompleteFrameFailsClosed() throws Exception {
        Path wal = temporaryDirectory.resolve("corrupt.wal");
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            new OperationIntentStore(wal, executor).prepare(placementIntent("place-1")).join();
        }
        byte[] bytes = Files.readAllBytes(wal);
        bytes[19] ^= 0x5A;
        Files.write(wal, bytes);

        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            OperationIntentStore reopened = new OperationIntentStore(wal, executor);
            CompletionException failure = assertThrows(CompletionException.class, () -> reopened.loadActive().join());
            assertTrue(failure.getCause() instanceof WalCorruptionException);
        }
    }

    @Test
    void oneActiveIntentRejectsASecondOperation() {
        Path wal = temporaryDirectory.resolve("single-active.wal");
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            OperationIntentStore store = new OperationIntentStore(wal, executor);
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
            OperationIntentStore store = new OperationIntentStore(wal, executor, path -> {
                calls.add(Thread.currentThread().getName());
                throw new IOException("simulated crash after write before force");
            });

            assertThrows(CompletionException.class, () -> store.prepare(placementIntent("place-1")).join());
        }

        assertEquals(1, calls.size());
        assertTrue(calls.getFirst().startsWith("ssa-persistence-"));
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            OperationIntentStore reopened = new OperationIntentStore(wal, executor);
            assertEquals(OperationStatus.PREPARED, reopened.loadActive().join().orElseThrow().status());
        }
    }

    @Test
    void abortedAndQuarantinedStatusesSurviveReopen() {
        for (OperationStatus status : List.of(OperationStatus.ABORTED, OperationStatus.QUARANTINED)) {
            Path wal = temporaryDirectory.resolve(status.name().toLowerCase() + ".wal");
            try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
                OperationIntentStore store = new OperationIntentStore(wal, executor);
                store.prepare(placementIntent("place-1")).join();
                store.transition("place-1", status).join();
            }
            try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
                OperationIntentStore reopened = new OperationIntentStore(wal, executor);
                assertEquals(status, reopened.loadActive().join().orElseThrow().status());
                reopened.clear("place-1").join();
            }
        }
    }

    @Test
    void clearRequiresTerminalStatusAndReplayRejectsInvalidClearSequences() throws Exception {
        Path source = temporaryDirectory.resolve("clear-source.wal");
        OperationIntent intent = placementIntent("place-1");
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            OperationIntentStore store = new OperationIntentStore(source, executor);
            store.prepare(intent).join();
            long preparedLength = Files.size(source);
            assertThrows(CompletionException.class, () -> store.clear(intent.operationId()).join());
            assertEquals(preparedLength, Files.size(source));
            store.transition(intent.operationId(), OperationStatus.ABORTED).join();
            store.clear(intent.operationId()).join();
        }

        byte[] valid = Files.readAllBytes(source);
        int[] starts = frameStarts(valid);
        byte[] preparedThenClear = new byte[starts[1] + valid.length - starts[2]];
        System.arraycopy(valid, 0, preparedThenClear, 0, starts[1]);
        System.arraycopy(valid, starts[2], preparedThenClear, starts[1], valid.length - starts[2]);
        byte[] clearOnly = Arrays.copyOfRange(valid, starts[2], valid.length);

        for (byte[] invalid : List.of(preparedThenClear, clearOnly)) {
            Path wal = temporaryDirectory.resolve("invalid-clear-" + invalid.length + ".wal");
            Files.write(wal, invalid);
            try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
                CompletionException failure = assertThrows(
                        CompletionException.class,
                        () -> new OperationIntentStore(wal, executor).loadActive().join());
                assertTrue(failure.getCause() instanceof WalCorruptionException);
            }
        }
    }

    @Test
    void corruptionOfEveryHeaderFieldFailsClosedInFirstMiddleAndFinalFrames() throws Exception {
        Path source = temporaryDirectory.resolve("header-source.wal");
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            OperationIntentStore store = new OperationIntentStore(source, executor);
            store.prepare(placementIntent("place-1")).join();
            store.transition("place-1", OperationStatus.COMMITTED).join();
            store.clear("place-1").join();
        }
        byte[] valid = Files.readAllBytes(source);
        int[] frameStarts = frameStarts(valid);
        int[] headerFieldOffsets = {0, 4, 6, 7, 11, 15};
        assertEquals(3, frameStarts.length);

        for (int frame = 0; frame < frameStarts.length; frame++) {
            for (int fieldOffset : headerFieldOffsets) {
                byte[] corrupted = valid.clone();
                corrupted[frameStarts[frame] + fieldOffset] ^= 0x01;
                Path path = temporaryDirectory.resolve("header-" + frame + "-" + fieldOffset + ".wal");
                Files.write(path, corrupted);
                try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
                    OperationIntentStore store = new OperationIntentStore(path, executor);
                    CompletionException failure = assertThrows(
                            CompletionException.class, () -> store.loadActive().join());
                    assertTrue(failure.getCause() instanceof WalCorruptionException,
                            "header corruption did not fail closed at frame " + frame + " offset " + fieldOffset);
                }
            }
        }
    }

    @Test
    void corruptionOfRedundantFooterLengthOrMagicFailsClosed() throws Exception {
        Path source = temporaryDirectory.resolve("footer-source.wal");
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            OperationIntentStore store = new OperationIntentStore(source, executor);
            store.prepare(placementIntent("place-1")).join();
            store.transition("place-1", OperationStatus.COMMITTED).join();
            store.clear("place-1").join();
        }
        byte[] valid = Files.readAllBytes(source);
        for (int frameStart : frameStarts(valid)) {
            int payloadLength = ByteBuffer.wrap(valid, frameStart + 7, Integer.BYTES).getInt();
            int footerStart = frameStart + 19 + payloadLength;
            for (int footerOffset : new int[] {0, Integer.BYTES}) {
                byte[] corrupted = valid.clone();
                corrupted[footerStart + footerOffset] ^= 0x01;
                Path path = temporaryDirectory.resolve("footer-" + frameStart + "-" + footerOffset + ".wal");
                Files.write(path, corrupted);
                try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
                    CompletionException failure = assertThrows(
                            CompletionException.class,
                            () -> new OperationIntentStore(path, executor).loadActive().join());
                    assertTrue(failure.getCause() instanceof WalCorruptionException);
                }
            }
        }
    }

    @Test
    void oversizedIntentIsRejectedBeforeWalBufferAllocation() {
        byte[] oneMiB = new byte[1_048_576];
        List<OperationDelta> deltas = new ArrayList<>();
        for (int slot = 0; slot < 3; slot++) {
            deltas.add(new InventoryDelta(
                    "builder", 1, slot,
                    StackSnapshot.of("minecraft:stone", 2, oneMiB),
                    StackSnapshot.of("minecraft:stone", 1, oneMiB)));
        }
        deltas.add(new WorldDelta(
                "minecraft:overworld", 0, 64, 0,
                BlockStateSnapshot.of("minecraft:air", new byte[0]),
                BlockStateSnapshot.of("minecraft:stone", new byte[0]),
                DropPolicy.NOT_APPLICABLE));
        OperationIntent oversized = OperationIntent.prepared(
                "oversized", "job-1", 1, OperationKind.WORLD_MUTATION, deltas);

        Path wal = temporaryDirectory.resolve("oversized.wal");
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            OperationIntentStore store = new OperationIntentStore(wal, executor);
            CompletionException failure = assertThrows(CompletionException.class, () -> store.prepare(oversized).join());
            assertTrue(failure.getCause() instanceof IOException);
            assertFalse(Files.exists(wal));
        }
    }

    @Test
    void recordsTwoHundredDurableAcknowledgementLatencies() {
        Path wal = temporaryDirectory.resolve("latency.wal");
        long[] samples = new long[200];
        try (PersistenceExecutor executor = new PersistenceExecutor("ssa-persistence")) {
            OperationIntentStore store = new OperationIntentStore(wal, executor);
            for (int index = 0; index < samples.length; index++) {
                DurableAcknowledgement acknowledgement = store.prepare(placementIntent("profile-" + index)).join();
                samples[index] = acknowledgement.latencyNanos();
                store.transition("profile-" + index, OperationStatus.ABORTED).join();
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

    @Test
    void codecDecodesVersionOneIntentWithoutTaskIdentity() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(1);
            writeTestString(output, "legacy-transfer");
            writeTestString(output, "legacy-job");
            output.writeLong(3);
            output.writeByte(OperationKind.MATERIAL_TRANSFER.ordinal());
            output.writeByte(OperationStatus.PREPARED.ordinal());
            output.writeInt(2);
            writeTestInventoryDelta(output, "source", 2, 0, "minecraft:stone", 2, "minecraft:stone", 1);
            writeTestInventoryDelta(output, "destination", 4, 1, "", 0, "minecraft:stone", 1);
        }

        OperationIntent decoded = OperationIntentCodec.decode(bytes.toByteArray());

        assertEquals("legacy-transfer", decoded.operationId());
        assertEquals("legacy-job", decoded.jobId());
        assertEquals(3, decoded.jobRevision());
        assertTrue(decoded.taskId().isEmpty());
        assertTrue(decoded.atomicGroupId().isEmpty());
        assertEquals(2, decoded.deltas().size());
    }

    @Test
    void codecRejectsComponentBearingEmptyStack() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(1);
            writeTestString(output, "invalid-empty");
            writeTestString(output, "job-1");
            output.writeLong(1);
            output.writeByte(OperationKind.MATERIAL_TRANSFER.ordinal());
            output.writeByte(OperationStatus.PREPARED.ordinal());
            output.writeInt(2);
            output.writeByte(1);
            writeTestString(output, "builder");
            output.writeInt(1);
            output.writeInt(0);
            writeTestStack(output, "", 0, new byte[] {1});
            writeTestStack(output, "minecraft:stone", 1, new byte[0]);
            output.writeByte(1);
            writeTestString(output, "chest");
            output.writeInt(1);
            output.writeInt(0);
            writeTestStack(output, "minecraft:stone", 1, new byte[0]);
            writeTestStack(output, "", 0, new byte[0]);
        }

        assertThrows(IOException.class, () -> OperationIntentCodec.decode(bytes.toByteArray()));
    }

    private static void writeTestStack(
            DataOutputStream output,
            String itemId,
            int count,
            byte[] components) throws IOException {
        writeTestString(output, itemId);
        output.writeInt(count);
        output.writeInt(components.length);
        output.write(components);
    }

    private static void writeTestInventoryDelta(
            DataOutputStream output,
            String inventoryId,
            int bindingRevision,
            int slot,
            String beforeItemId,
            int beforeCount,
            String afterItemId,
            int afterCount) throws IOException {
        output.writeByte(1);
        writeTestString(output, inventoryId);
        output.writeInt(bindingRevision);
        output.writeInt(slot);
        writeTestStack(output, beforeItemId, beforeCount, new byte[0]);
        writeTestStack(output, afterItemId, afterCount, new byte[0]);
    }

    private static void writeTestString(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
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
        return OperationIntent.prepared(
                id,
                "job-1",
                Optional.of("task-roof"),
                Optional.of("atomic-roof-stairs"),
                5,
                OperationKind.WORLD_MUTATION,
                List.of(
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

    private static int[] frameStarts(byte[] wal) {
        final int frameOverheadBytes = 19 + 8;
        List<Integer> starts = new ArrayList<>();
        int offset = 0;
        while (offset < wal.length) {
            starts.add(offset);
            int payloadLength = ByteBuffer.wrap(wal, offset + 7, Integer.BYTES).getInt();
            offset += frameOverheadBytes + payloadLength;
        }
        assertEquals(wal.length, offset);
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }
}
