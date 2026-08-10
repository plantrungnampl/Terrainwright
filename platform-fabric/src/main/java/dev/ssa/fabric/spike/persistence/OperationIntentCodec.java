package dev.ssa.fabric.spike.persistence;

import dev.ssa.construction.spike.persistence.BlockStateSnapshot;
import dev.ssa.construction.spike.persistence.DropPolicy;
import dev.ssa.construction.spike.persistence.InventoryDelta;
import dev.ssa.construction.spike.persistence.OperationDelta;
import dev.ssa.construction.spike.persistence.OperationIntent;
import dev.ssa.construction.spike.persistence.OperationKind;
import dev.ssa.construction.spike.persistence.OperationStatus;
import dev.ssa.construction.spike.persistence.StackSnapshot;
import dev.ssa.construction.spike.persistence.WorldDelta;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class OperationIntentCodec {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_STRING_BYTES = 65_536;
    private static final int MAX_SNAPSHOT_BYTES = 1_048_576;
    private static final int MAX_ENCODED_BYTES = 4 * 1_048_576;

    private OperationIntentCodec() {
    }

    public static byte[] encode(OperationIntent intent) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(encodedSize(intent));
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(FORMAT_VERSION);
            writeString(output, intent.operationId());
            writeString(output, intent.jobId());
            output.writeLong(intent.jobRevision());
            output.writeByte(intent.kind().ordinal());
            output.writeByte(intent.status().ordinal());
            output.writeInt(intent.deltas().size());
            for (OperationDelta delta : intent.deltas()) {
                if (delta instanceof InventoryDelta inventory) {
                    output.writeByte(1);
                    writeString(output, inventory.inventoryId());
                    output.writeInt(inventory.bindingRevision());
                    output.writeInt(inventory.slot());
                    writeStack(output, inventory.before());
                    writeStack(output, inventory.after());
                } else if (delta instanceof WorldDelta world) {
                    output.writeByte(2);
                    writeString(output, world.worldId());
                    output.writeInt(world.x());
                    output.writeInt(world.y());
                    output.writeInt(world.z());
                    writeBlock(output, world.before());
                    writeBlock(output, world.after());
                    output.writeByte(world.dropPolicy().ordinal());
                }
            }
        }
        return bytes.toByteArray();
    }

    private static int encodedSize(OperationIntent intent) throws IOException {
        long size = Integer.BYTES
                + stringSize(intent.operationId())
                + stringSize(intent.jobId())
                + Long.BYTES
                + Byte.BYTES
                + Byte.BYTES
                + Integer.BYTES;
        for (OperationDelta delta : intent.deltas()) {
            size += Byte.BYTES;
            if (delta instanceof InventoryDelta inventory) {
                size += stringSize(inventory.inventoryId())
                        + Integer.BYTES
                        + Integer.BYTES
                        + stackSize(inventory.before())
                        + stackSize(inventory.after());
            } else if (delta instanceof WorldDelta world) {
                size += stringSize(world.worldId())
                        + 3L * Integer.BYTES
                        + blockSize(world.before())
                        + blockSize(world.after())
                        + Byte.BYTES;
            }
            if (size > MAX_ENCODED_BYTES) {
                throw new IOException("encoded operation intent exceeds 4 MiB");
            }
        }
        return (int) size;
    }

    private static long stackSize(StackSnapshot stack) throws IOException {
        return stringSize(stack.itemId()) + Integer.BYTES + Integer.BYTES + stack.componentsPayloadSize();
    }

    private static long blockSize(BlockStateSnapshot block) throws IOException {
        return stringSize(block.blockId()) + Integer.BYTES + block.propertiesPayloadSize();
    }

    private static int stringSize(String value) throws IOException {
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length > MAX_STRING_BYTES) {
            throw new IOException("string exceeds codec limit");
        }
        return Integer.BYTES + length;
    }

    public static OperationIntent decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int version = input.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("unsupported intent format version: " + version);
            }
            String operationId = readString(input);
            String jobId = readString(input);
            long jobRevision = input.readLong();
            OperationKind kind = readEnum(input, OperationKind.values(), "operation kind");
            OperationStatus status = readEnum(input, OperationStatus.values(), "operation status");
            int deltaCount = input.readInt();
            if (deltaCount < 1 || deltaCount > 320) {
                throw new IOException("invalid operation delta count: " + deltaCount);
            }
            List<OperationDelta> deltas = new ArrayList<>(deltaCount);
            for (int index = 0; index < deltaCount; index++) {
                int deltaType = input.readUnsignedByte();
                if (deltaType == 1) {
                    deltas.add(new InventoryDelta(
                            readString(input),
                            input.readInt(),
                            input.readInt(),
                            readStack(input),
                            readStack(input)));
                } else if (deltaType == 2) {
                    deltas.add(new WorldDelta(
                            readString(input),
                            input.readInt(),
                            input.readInt(),
                            input.readInt(),
                            readBlock(input),
                            readBlock(input),
                            readEnum(input, DropPolicy.values(), "drop policy")));
                } else {
                    throw new IOException("unknown operation delta type: " + deltaType);
                }
            }
            if (input.read() != -1) {
                throw new IOException("trailing bytes in operation intent payload");
            }
            return new OperationIntent(operationId, jobId, jobRevision, kind, status, deltas);
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid operation intent payload", exception);
        }
    }

    private static void writeStack(DataOutputStream output, StackSnapshot stack) throws IOException {
        writeString(output, stack.itemId());
        output.writeInt(stack.count());
        writeBytes(output, stack.componentsPayload());
    }

    private static StackSnapshot readStack(DataInputStream input) throws IOException {
        String itemId = readString(input);
        int count = input.readInt();
        byte[] components = readBytes(input);
        return StackSnapshot.of(itemId, count, components);
    }

    private static void writeBlock(DataOutputStream output, BlockStateSnapshot block) throws IOException {
        writeString(output, block.blockId());
        writeBytes(output, block.propertiesPayload());
    }

    private static BlockStateSnapshot readBlock(DataInputStream input) throws IOException {
        return BlockStateSnapshot.of(readString(input), readBytes(input));
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_STRING_BYTES) {
            throw new IOException("string exceeds codec limit");
        }
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("invalid string length: " + length);
        }
        byte[] encoded = input.readNBytes(length);
        if (encoded.length != length) {
            throw new EOFException("truncated string payload");
        }
        return new String(encoded, StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        if (value.length > MAX_SNAPSHOT_BYTES) {
            throw new IOException("snapshot exceeds codec limit");
        }
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_SNAPSHOT_BYTES) {
            throw new IOException("invalid snapshot length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("truncated snapshot payload");
        }
        return bytes;
    }

    private static <T> T readEnum(DataInputStream input, T[] values, String label) throws IOException {
        int ordinal = input.readUnsignedByte();
        if (ordinal >= values.length) {
            throw new IOException("unknown " + label + " ordinal: " + ordinal);
        }
        return values[ordinal];
    }
}
