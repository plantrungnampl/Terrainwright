package dev.ssa.fabric.spike.restart;

import dev.ssa.construction.operation.BlockStateSnapshot;
import dev.ssa.construction.operation.EvidenceObservation;
import dev.ssa.construction.operation.EvidenceSnapshot;
import dev.ssa.construction.operation.OperationDelta;
import dev.ssa.construction.operation.OperationIntent;
import dev.ssa.construction.operation.ObservedEvidence;
import dev.ssa.construction.operation.StackSnapshot;
import dev.ssa.fabric.construction.OperationEvidencePort;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

public final class RestartFixtureRepository implements OperationEvidencePort {
    private static final String FORMAT_VERSION = "1";

    private final Path path;

    private RestartFixtureRepository(Path path) {
        this.path = path;
    }

    public static RestartFixtureRepository create(
            Path path,
            RestartFixture fixture,
            OperationIntent intent) {
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(intent, "intent");
        if (!fixture.buildJobId().equals(intent.jobId()) || fixture.jobRevision() != intent.jobRevision()) {
            throw new IllegalArgumentException("fixture job identity does not match operation intent");
        }
        RestartFixtureRepository repository = new RestartFixtureRepository(Objects.requireNonNull(path, "path"));
        if (Files.exists(path)) {
            throw new IllegalStateException("restart fixture already exists: " + path);
        }
        List<String> keys = intent.deltas().stream().map(OperationDelta::evidenceKey).toList();
        List<EvidenceSnapshot> evidence = intent.deltas().stream().map(OperationDelta::before).toList();
        repository.write(new State(fixture, keys, evidence, 0, 0, 0, false, "PENDING"));
        return repository;
    }

    public static RestartFixtureRepository open(Path path) {
        RestartFixtureRepository repository = new RestartFixtureRepository(Objects.requireNonNull(path, "path"));
        repository.read();
        return repository;
    }

    public Path path() {
        return path;
    }

    public synchronized RestartFixture fixture() {
        return read().fixture;
    }

    @Override
    public synchronized ObservedEvidence observe(OperationIntent intent) {
        State state = read();
        requireMatchingEvidence(intent, state);
        List<EvidenceObservation> observations = new ArrayList<>(state.evidence.size());
        for (int index = 0; index < state.evidence.size(); index++) {
            observations.add(new EvidenceObservation(state.keys.get(index), state.evidence.get(index)));
        }
        return new ObservedEvidence(observations);
    }

    @Override
    public synchronized void apply(OperationDelta delta) {
        State state = read();
        int index = state.keys.indexOf(delta.evidenceKey());
        if (index < 0) {
            throw new IllegalStateException("operation delta is not part of the restart fixture");
        }
        if (!state.evidence.get(index).equals(delta.before())) {
            throw new IllegalStateException("restart fixture evidence is not the exact before state");
        }
        List<EvidenceSnapshot> evidence = new ArrayList<>(state.evidence);
        evidence.set(index, delta.after());
        write(state.withEvidence(evidence, state.applyCount + 1));
    }

    @Override
    public synchronized boolean isCommitted(String operationId) {
        return read().commitCount > 0;
    }

    @Override
    public synchronized CompletableFuture<Void> commit(OperationIntent intent) {
        State state = read();
        requireMatchingEvidence(intent, state);
        if (state.commitCount == 0) {
            write(state.withCommitCount(1));
        }
        return CompletableFuture.completedFuture(null);
    }

    public synchronized void setForeign(int evidenceIndex) {
        State state = read();
        if (evidenceIndex < 0 || evidenceIndex >= state.evidence.size()) {
            throw new IllegalArgumentException("foreign evidence index is out of bounds");
        }
        List<EvidenceSnapshot> evidence = new ArrayList<>(state.evidence);
        EvidenceSnapshot current = evidence.get(evidenceIndex);
        if (current instanceof StackSnapshot stack) {
            byte[] foreign = appendForeignByte(stack.componentsPayload());
            evidence.set(evidenceIndex, stack.count() == 0
                    ? StackSnapshot.of("minecraft:barrier", 1, foreign)
                    : StackSnapshot.of(stack.itemId(), stack.count(), foreign));
        } else if (current instanceof BlockStateSnapshot block) {
            evidence.set(evidenceIndex,
                    BlockStateSnapshot.of(block.blockId(), appendForeignByte(block.propertiesPayload())));
        }
        write(state.withEvidence(evidence, state.applyCount));
    }

    public synchronized void markRecoveryComplete(String diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        State state = read();
        write(state.withRecovery(true, diagnostic));
    }

    public synchronized boolean scheduleOnce() {
        State state = read();
        if (!state.recoveryComplete) {
            throw new IllegalStateException("scheduling is closed until restart reconciliation completes");
        }
        if (state.scheduleCount > 0) {
            return false;
        }
        write(state.withScheduleCount(1));
        return true;
    }

    public synchronized int applyCount() {
        return read().applyCount;
    }

    public synchronized int commitCount() {
        return read().commitCount;
    }

    public synchronized int scheduleCount() {
        return read().scheduleCount;
    }

    public synchronized boolean recoveryComplete() {
        return read().recoveryComplete;
    }

    public synchronized String recoveryDiagnostic() {
        return read().recoveryDiagnostic;
    }

    public synchronized String evidenceSha256() {
        State state = read();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int index = 0; index < state.evidence.size(); index++) {
                digest.update(state.keys.get(index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(canonicalEvidence(state.evidence.get(index)));
            }
            return HexFormat.of().formatHex(digest.digest()).toUpperCase();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private synchronized State read() {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (IOException exception) {
            throw new UncheckedIOException("could not read restart fixture " + path, exception);
        }
        if (!FORMAT_VERSION.equals(properties.getProperty("format"))) {
            throw new IllegalStateException("unsupported restart fixture format");
        }
        RestartFixture fixture = new RestartFixture(
                required(properties, "fixture.id"),
                required(properties, "world.identity"),
                required(properties, "job.id"),
                longValue(properties, "job.revision"),
                required(properties, "job.state"),
                required(properties, "blueprint.hash"),
                required(properties, "container.binding_id"),
                intValue(properties, "container.binding_revision"),
                required(properties, "builder.id"),
                required(properties, "builder.lifecycle"),
                booleanValue(properties, "builder.tombstone"),
                required(properties, "scaffold.key"),
                BlockStateSnapshot.of(
                        required(properties, "scaffold.block_id"),
                        decode(required(properties, "scaffold.properties"))));
        int count = intValue(properties, "evidence.count");
        List<String> keys = new ArrayList<>(count);
        List<EvidenceSnapshot> evidence = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String prefix = "evidence." + index + ".";
            keys.add(required(properties, prefix + "key"));
            evidence.add(readEvidence(properties, prefix));
        }
        return new State(
                fixture,
                keys,
                evidence,
                intValue(properties, "state.apply_count"),
                intValue(properties, "state.commit_count"),
                intValue(properties, "state.schedule_count"),
                booleanValue(properties, "state.recovery_complete"),
                required(properties, "state.recovery_diagnostic"));
    }

    private synchronized void write(State state) {
        Properties properties = new Properties();
        properties.setProperty("format", FORMAT_VERSION);
        RestartFixture fixture = state.fixture;
        properties.setProperty("fixture.id", fixture.fixtureId());
        properties.setProperty("world.identity", fixture.worldIdentity());
        properties.setProperty("job.id", fixture.buildJobId());
        properties.setProperty("job.revision", Long.toString(fixture.jobRevision()));
        properties.setProperty("job.state", fixture.jobState());
        properties.setProperty("blueprint.hash", fixture.blueprintHash());
        properties.setProperty("container.binding_id", fixture.containerBindingId());
        properties.setProperty("container.binding_revision", Integer.toString(fixture.containerBindingRevision()));
        properties.setProperty("builder.id", fixture.builderId());
        properties.setProperty("builder.lifecycle", fixture.builderLifecycle());
        properties.setProperty("builder.tombstone", Boolean.toString(fixture.builderTombstone()));
        properties.setProperty("scaffold.key", fixture.temporaryScaffoldKey());
        properties.setProperty("scaffold.block_id", fixture.temporaryScaffoldState().blockId());
        properties.setProperty("scaffold.properties", encode(fixture.temporaryScaffoldState().propertiesPayload()));
        properties.setProperty("evidence.count", Integer.toString(state.evidence.size()));
        for (int index = 0; index < state.evidence.size(); index++) {
            String prefix = "evidence." + index + ".";
            properties.setProperty(prefix + "key", state.keys.get(index));
            writeEvidence(properties, prefix, state.evidence.get(index));
        }
        properties.setProperty("state.apply_count", Integer.toString(state.applyCount));
        properties.setProperty("state.commit_count", Integer.toString(state.commitCount));
        properties.setProperty("state.schedule_count", Integer.toString(state.scheduleCount));
        properties.setProperty("state.recovery_complete", Boolean.toString(state.recoveryComplete));
        properties.setProperty("state.recovery_diagnostic", state.recoveryDiagnostic);

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            properties.store(bytes, "Smart Survival Architect S5 restart fixture");
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("could not persist restart fixture " + path, exception);
        }
    }

    private static void requireMatchingEvidence(OperationIntent intent, State state) {
        List<String> keys = intent.deltas().stream().map(OperationDelta::evidenceKey).toList();
        if (!keys.equals(state.keys)) {
            throw new IllegalStateException("operation intent does not match restart fixture evidence keys");
        }
    }

    private static EvidenceSnapshot readEvidence(Properties properties, String prefix) {
        String type = required(properties, prefix + "type");
        if ("stack".equals(type)) {
            return StackSnapshot.of(
                    required(properties, prefix + "item_id"),
                    intValue(properties, prefix + "count"),
                    decode(required(properties, prefix + "components")));
        }
        if ("block".equals(type)) {
            return BlockStateSnapshot.of(
                    required(properties, prefix + "block_id"),
                    decode(required(properties, prefix + "properties")));
        }
        throw new IllegalStateException("unknown restart fixture evidence type: " + type);
    }

    private static void writeEvidence(Properties properties, String prefix, EvidenceSnapshot evidence) {
        if (evidence instanceof StackSnapshot stack) {
            properties.setProperty(prefix + "type", "stack");
            properties.setProperty(prefix + "item_id", stack.itemId());
            properties.setProperty(prefix + "count", Integer.toString(stack.count()));
            properties.setProperty(prefix + "components", encode(stack.componentsPayload()));
            return;
        }
        if (evidence instanceof BlockStateSnapshot block) {
            properties.setProperty(prefix + "type", "block");
            properties.setProperty(prefix + "block_id", block.blockId());
            properties.setProperty(prefix + "properties", encode(block.propertiesPayload()));
            return;
        }
        throw new IllegalArgumentException("unsupported restart fixture evidence type");
    }

    private static byte[] canonicalEvidence(EvidenceSnapshot evidence) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                if (evidence instanceof StackSnapshot stack) {
                    output.writeByte(1);
                    output.writeUTF(stack.itemId());
                    output.writeInt(stack.count());
                    output.writeInt(stack.componentsPayloadSize());
                    output.write(stack.componentsPayload());
                } else if (evidence instanceof BlockStateSnapshot block) {
                    output.writeByte(2);
                    output.writeUTF(block.blockId());
                    output.writeInt(block.propertiesPayloadSize());
                    output.write(block.propertiesPayload());
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] appendForeignByte(byte[] value) {
        byte[] foreign = java.util.Arrays.copyOf(value, value.length + 1);
        foreign[value.length] = 0x7F;
        return foreign;
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("restart fixture is missing " + key);
        }
        return value;
    }

    private static int intValue(Properties properties, String key) {
        return Integer.parseInt(required(properties, key));
    }

    private static long longValue(Properties properties, String key) {
        return Long.parseLong(required(properties, key));
    }

    private static boolean booleanValue(Properties properties, String key) {
        String value = required(properties, key);
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalStateException("restart fixture has invalid boolean " + key);
        }
        return Boolean.parseBoolean(value);
    }

    private static final class State {
        private final RestartFixture fixture;
        private final List<String> keys;
        private final List<EvidenceSnapshot> evidence;
        private final int applyCount;
        private final int commitCount;
        private final int scheduleCount;
        private final boolean recoveryComplete;
        private final String recoveryDiagnostic;

        private State(
                RestartFixture fixture,
                List<String> keys,
                List<EvidenceSnapshot> evidence,
                int applyCount,
                int commitCount,
                int scheduleCount,
                boolean recoveryComplete,
                String recoveryDiagnostic) {
            this.fixture = fixture;
            this.keys = List.copyOf(keys);
            this.evidence = List.copyOf(evidence);
            this.applyCount = applyCount;
            this.commitCount = commitCount;
            this.scheduleCount = scheduleCount;
            this.recoveryComplete = recoveryComplete;
            this.recoveryDiagnostic = recoveryDiagnostic;
        }

        private State withEvidence(List<EvidenceSnapshot> nextEvidence, int nextApplyCount) {
            return new State(fixture, keys, nextEvidence, nextApplyCount, commitCount, scheduleCount,
                    recoveryComplete, recoveryDiagnostic);
        }

        private State withCommitCount(int nextCommitCount) {
            return new State(fixture, keys, evidence, applyCount, nextCommitCount, scheduleCount,
                    recoveryComplete, recoveryDiagnostic);
        }

        private State withRecovery(boolean complete, String diagnostic) {
            return new State(fixture, keys, evidence, applyCount, commitCount, scheduleCount, complete, diagnostic);
        }

        private State withScheduleCount(int nextScheduleCount) {
            return new State(fixture, keys, evidence, applyCount, commitCount, nextScheduleCount,
                    recoveryComplete, recoveryDiagnostic);
        }
    }
}
