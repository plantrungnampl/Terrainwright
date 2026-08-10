package dev.ssa.fabric.spike.persistence;

import dev.ssa.construction.spike.persistence.OperationIntent;
import dev.ssa.construction.spike.persistence.OperationStatus;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.zip.CRC32C;

public final class FileOperationIntentStore {
    private static final int MAGIC = 0x53534134;
    private static final short FRAME_VERSION = 1;
    private static final byte UPSERT = 1;
    private static final byte CLEAR = 2;
    private static final int FOOTER_MAGIC = 0x53414634;
    private static final int HEADER_BYTES = Integer.BYTES + Short.BYTES + Byte.BYTES
            + Integer.BYTES + Integer.BYTES + Integer.BYTES;
    private static final int FOOTER_BYTES = Integer.BYTES + Integer.BYTES;
    private static final int MAX_FRAME_BYTES = 4 * 1_048_576;

    private final Path walPath;
    private final PersistenceExecutor executor;
    private final AppendProbe appendProbe;

    public FileOperationIntentStore(Path walPath, PersistenceExecutor executor) {
        this(walPath, executor, AppendProbe.NONE);
    }

    FileOperationIntentStore(Path walPath, PersistenceExecutor executor, AppendProbe appendProbe) {
        this.walPath = Objects.requireNonNull(walPath, "walPath");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.appendProbe = Objects.requireNonNull(appendProbe, "appendProbe");
    }

    public CompletableFuture<DurableAcknowledgement> prepare(OperationIntent intent) {
        Objects.requireNonNull(intent, "intent");
        if (intent.status() != OperationStatus.PREPARED) {
            throw new IllegalArgumentException("only PREPARED intents may be prepared");
        }
        long submittedAt = System.nanoTime();
        return executor.submit(() -> {
            if (readActive().isPresent()) {
                throw new IllegalStateException("an operation intent is already active");
            }
            return append(UPSERT, OperationIntentCodec.encode(intent), OperationStatus.PREPARED, submittedAt);
        });
    }

    public CompletableFuture<DurableAcknowledgement> transition(String operationId, OperationStatus nextStatus) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(nextStatus, "nextStatus");
        if (nextStatus == OperationStatus.PREPARED) {
            throw new IllegalArgumentException("transition target must be terminal");
        }
        long submittedAt = System.nanoTime();
        return executor.submit(() -> {
            OperationIntent active = requireActive(operationId);
            if (!isAllowedTransition(active.status(), nextStatus)) {
                throw new IllegalStateException(
                        "illegal operation status transition: " + active.status() + " -> " + nextStatus);
            }
            return append(
                    UPSERT,
                    OperationIntentCodec.encode(active.withStatus(nextStatus)),
                    nextStatus,
                    submittedAt);
        });
    }

    public CompletableFuture<DurableAcknowledgement> clear(String operationId) {
        Objects.requireNonNull(operationId, "operationId");
        long submittedAt = System.nanoTime();
        return executor.submit(() -> {
            OperationIntent active = requireActive(operationId);
            if (active.status() == OperationStatus.PREPARED) {
                throw new IllegalStateException("a PREPARED intent must transition before clear");
            }
            return append(CLEAR, encodeClear(operationId), active.status(), submittedAt);
        });
    }

    public CompletableFuture<Optional<OperationIntent>> loadActive() {
        return executor.submit(this::readActive);
    }

    private Optional<OperationIntent> readActive() {
        return scanWal().active();
    }

    private WalScan scanWal() {
        if (!Files.exists(walPath)) {
            return new WalScan(Optional.empty(), 0);
        }
        try {
            byte[] wal = Files.readAllBytes(walPath);
            int offset = 0;
            OperationIntent active = null;
            while (wal.length - offset >= HEADER_BYTES) {
                ByteBuffer header = ByteBuffer.wrap(wal, offset, HEADER_BYTES);
                int magic = header.getInt();
                short version = header.getShort();
                byte recordType = header.get();
                int payloadLength = header.getInt();
                int expectedMetadataChecksum = header.getInt();
                int expectedPayloadChecksum = header.getInt();
                if (magic != MAGIC || version != FRAME_VERSION) {
                    throw new WalCorruptionException("invalid WAL frame header at offset " + offset);
                }
                if (metadataChecksum(recordType, payloadLength) != expectedMetadataChecksum) {
                    throw new WalCorruptionException("WAL metadata checksum mismatch at offset " + offset);
                }
                if ((recordType != UPSERT && recordType != CLEAR)
                        || payloadLength < 0
                        || payloadLength > MAX_FRAME_BYTES) {
                    throw new WalCorruptionException("invalid WAL frame metadata at offset " + offset);
                }
                int frameLength = HEADER_BYTES + payloadLength + FOOTER_BYTES;
                if (wal.length - offset < frameLength) {
                    break;
                }
                byte[] payload = new byte[payloadLength];
                System.arraycopy(wal, offset + HEADER_BYTES, payload, 0, payloadLength);
                if (payloadChecksum(recordType, payloadLength, payload) != expectedPayloadChecksum) {
                    throw new WalCorruptionException("WAL payload checksum mismatch at offset " + offset);
                }
                ByteBuffer footer = ByteBuffer.wrap(wal, offset + HEADER_BYTES + payloadLength, FOOTER_BYTES);
                int repeatedPayloadLength = footer.getInt();
                int footerMagic = footer.getInt();
                if (repeatedPayloadLength != payloadLength || footerMagic != FOOTER_MAGIC) {
                    throw new WalCorruptionException("invalid WAL frame footer at offset " + offset);
                }
                if (recordType == UPSERT) {
                    OperationIntent decoded = OperationIntentCodec.decode(payload);
                    if (active != null && !active.operationId().equals(decoded.operationId())) {
                        throw new WalCorruptionException("multiple active operation IDs in WAL");
                    }
                    active = decoded;
                } else {
                    String clearedId = decodeClear(payload);
                    if (active == null) {
                        throw new WalCorruptionException("clear record has no active operation");
                    }
                    if (!active.operationId().equals(clearedId)) {
                        throw new WalCorruptionException("clear record does not match active operation");
                    }
                    if (active.status() == OperationStatus.PREPARED) {
                        throw new WalCorruptionException("clear record follows a PREPARED operation");
                    }
                    active = null;
                }
                offset += frameLength;
            }
            return new WalScan(Optional.ofNullable(active), offset);
        } catch (IOException exception) {
            throw new WalCorruptionException("could not read operation intent WAL", exception);
        }
    }

    private OperationIntent requireActive(String operationId) {
        OperationIntent active = readActive().orElseThrow(() -> new IllegalStateException("no active operation intent"));
        if (!active.operationId().equals(operationId)) {
            throw new IllegalStateException("active operation ID does not match " + operationId);
        }
        return active;
    }

    private static boolean isAllowedTransition(OperationStatus currentStatus, OperationStatus nextStatus) {
        if (currentStatus == OperationStatus.PREPARED) {
            return true;
        }
        return nextStatus == OperationStatus.QUARANTINED
                && (currentStatus == OperationStatus.ABORTED || currentStatus == OperationStatus.COMMITTED);
    }

    private DurableAcknowledgement append(
            byte recordType,
            byte[] payload,
            OperationStatus status,
            long submittedAt) throws IOException {
        if (payload.length > MAX_FRAME_BYTES) {
            throw new IOException("WAL frame payload exceeds 4 MiB");
        }
        Path parent = walPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ByteBuffer frame = ByteBuffer.allocate(HEADER_BYTES + payload.length + FOOTER_BYTES);
        frame.putInt(MAGIC);
        frame.putShort(FRAME_VERSION);
        frame.put(recordType);
        frame.putInt(payload.length);
        frame.putInt(metadataChecksum(recordType, payload.length));
        frame.putInt(payloadChecksum(recordType, payload.length, payload));
        frame.put(payload);
        frame.putInt(payload.length);
        frame.putInt(FOOTER_MAGIC);
        frame.flip();

        int validLength = scanWal().validLength();
        try (FileChannel channel = FileChannel.open(
                walPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            channel.truncate(validLength);
            channel.position(validLength);
            while (frame.hasRemaining()) {
                channel.write(frame);
            }
            appendProbe.afterWriteBeforeForce(walPath);
            channel.force(true);
        }
        return new DurableAcknowledgement(status, System.nanoTime() - submittedAt, Thread.currentThread().getName());
    }

    private static int metadataChecksum(byte recordType, int payloadLength) {
        ByteBuffer metadata = ByteBuffer.allocate(Integer.BYTES + Short.BYTES + Byte.BYTES + Integer.BYTES);
        metadata.putInt(MAGIC);
        metadata.putShort(FRAME_VERSION);
        metadata.put(recordType);
        metadata.putInt(payloadLength);
        CRC32C checksum = new CRC32C();
        checksum.update(metadata.array(), 0, metadata.capacity());
        return (int) checksum.getValue();
    }

    private static int payloadChecksum(byte recordType, int payloadLength, byte[] payload) {
        CRC32C checksum = new CRC32C();
        ByteBuffer metadata = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES);
        metadata.put(recordType);
        metadata.putInt(payloadLength);
        checksum.update(metadata.array(), 0, metadata.capacity());
        checksum.update(payload, 0, payload.length);
        return (int) checksum.getValue();
    }

    private static byte[] encodeClear(String operationId) throws IOException {
        byte[] id = operationId.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Integer.BYTES + id.length);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(id.length);
            output.write(id);
        }
        return bytes.toByteArray();
    }

    private static String decodeClear(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int length = input.readInt();
            if (length < 1 || length > 160 || length != input.available()) {
                throw new IOException("invalid clear record");
            }
            return new String(input.readNBytes(length), StandardCharsets.UTF_8);
        }
    }

    private record WalScan(Optional<OperationIntent> active, int validLength) {
    }
}
