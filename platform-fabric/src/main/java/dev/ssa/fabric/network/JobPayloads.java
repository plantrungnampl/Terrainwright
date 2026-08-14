package dev.ssa.fabric.network;

import dev.ssa.architect.model.GridPos;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.fabric.TerrainwrightMod;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class JobPayloads {
    private static final int MAX_MISSING_MATERIALS = 128;
    private static final int MAX_CONFLICTS = 512;
    private static final int MAX_DIAGNOSTICS = 64;

    private JobPayloads() {}

    public record RequestJobSnapshot(UUID hutId) implements CustomPacketPayload {
        public static final Type<RequestJobSnapshot> TYPE = JobPayloads.type("request_job_snapshot");
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestJobSnapshot> CODEC = StreamCodec.of(
                (buffer, payload) -> buffer.writeUUID(payload.hutId()),
                buffer -> new RequestJobSnapshot(buffer.readUUID()));

        public RequestJobSnapshot {
            Objects.requireNonNull(hutId, "hutId");
        }

        @Override
        public Type<RequestJobSnapshot> type() {
            return TYPE;
        }
    }

    public record HutSnapshot(
            UUID hutId,
            long revision,
            boolean chestLinked,
            boolean activeJob) implements CustomPacketPayload {
        public static final Type<HutSnapshot> TYPE = JobPayloads.type("hut_snapshot");
        public static final StreamCodec<RegistryFriendlyByteBuf, HutSnapshot> CODEC = StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeUUID(payload.hutId());
                    buffer.writeVarLong(payload.revision());
                    buffer.writeBoolean(payload.chestLinked());
                    buffer.writeBoolean(payload.activeJob());
                },
                buffer -> new HutSnapshot(
                        buffer.readUUID(),
                        buffer.readVarLong(),
                        buffer.readBoolean(),
                        buffer.readBoolean()));

        public HutSnapshot {
            Objects.requireNonNull(hutId, "hutId");
            if (revision < 0) {
                throw new IllegalArgumentException("Hut revision must not be negative");
            }
        }

        @Override
        public Type<HutSnapshot> type() {
            return TYPE;
        }
    }

    public record LinkBuilderChest(
            UUID hutId,
            BlockPos hutPos,
            BlockPos chestPos) implements CustomPacketPayload {
        public static final Type<LinkBuilderChest> TYPE = JobPayloads.type("link_builder_chest");
        public static final StreamCodec<RegistryFriendlyByteBuf, LinkBuilderChest> CODEC = StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeUUID(payload.hutId());
                    writeBlockPos(buffer, payload.hutPos());
                    writeBlockPos(buffer, payload.chestPos());
                },
                buffer -> new LinkBuilderChest(
                        buffer.readUUID(), readBlockPos(buffer), readBlockPos(buffer)));

        public LinkBuilderChest {
            Objects.requireNonNull(hutId, "hutId");
            Objects.requireNonNull(hutPos, "hutPos");
            Objects.requireNonNull(chestPos, "chestPos");
        }

        @Override
        public Type<LinkBuilderChest> type() {
            return TYPE;
        }
    }

    public record BuilderChestLinkResult(
            UUID hutId,
            boolean accepted,
            Failure failure) implements CustomPacketPayload {
        public static final Type<BuilderChestLinkResult> TYPE = JobPayloads.type("builder_chest_link_result");
        public static final StreamCodec<RegistryFriendlyByteBuf, BuilderChestLinkResult> CODEC = StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeUUID(payload.hutId());
                    buffer.writeBoolean(payload.accepted());
                    buffer.writeVarInt(payload.failure().ordinal());
                },
                buffer -> new BuilderChestLinkResult(
                        buffer.readUUID(),
                        buffer.readBoolean(),
                        readEnum(buffer, Failure.values(), "Builder Chest link failure")));

        public BuilderChestLinkResult {
            Objects.requireNonNull(hutId, "hutId");
            Objects.requireNonNull(failure, "failure");
            if (accepted != (failure == Failure.NONE)) {
                throw new IllegalArgumentException("Chest link acceptance and failure disagree");
            }
        }

        @Override
        public Type<BuilderChestLinkResult> type() {
            return TYPE;
        }

        public enum Failure {
            NONE,
            HUT_UNAVAILABLE,
            NOT_OWNER,
            OUT_OF_REACH,
            ACTIVE_JOB_RUNNING,
            NOT_VANILLA_CHEST,
            TOO_FAR,
            PERMISSION_DENIED,
            CHUNK_UNLOADED
        }
    }

    public record JobCommand(
            String jobId,
            long expectedRevision,
            Action action) implements CustomPacketPayload {
        public static final Type<JobCommand> TYPE = JobPayloads.type("job_command");
        public static final StreamCodec<RegistryFriendlyByteBuf, JobCommand> CODEC = StreamCodec.of(
                JobCommand::encode,
                JobCommand::decode);

        public JobCommand {
            requireIdentifier(jobId, "jobId");
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("expectedRevision must not be negative");
            }
            Objects.requireNonNull(action, "action");
        }

        @Override
        public Type<JobCommand> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, JobCommand payload) {
            buffer.writeUtf(payload.jobId(), 160);
            buffer.writeVarLong(payload.expectedRevision());
            buffer.writeVarInt(payload.action().ordinal());
        }

        private static JobCommand decode(RegistryFriendlyByteBuf buffer) {
            return new JobCommand(
                    buffer.readUtf(160),
                    buffer.readVarLong(),
                    readEnum(buffer, Action.values(), "job command action"));
        }

        public enum Action {
            PAUSE,
            RESUME,
            STOP,
            UNDO
        }
    }

    public record JobSnapshot(
            String jobId,
            UUID hutId,
            UUID ownerId,
            long revision,
            BuildJobState state,
            int completedTasks,
            int totalTasks,
            Map<String, Integer> missingMaterials,
            List<GridPos> conflicts,
            List<DiagnosticView> diagnostics) implements CustomPacketPayload, JobView {
        public static final Type<JobSnapshot> TYPE = JobPayloads.type("job_snapshot");
        public static final StreamCodec<RegistryFriendlyByteBuf, JobSnapshot> CODEC = StreamCodec.of(
                (buffer, payload) -> writeView(buffer, payload),
                buffer -> readView(buffer).snapshot());

        public JobSnapshot {
            ViewData trusted = validateView(
                    jobId,
                    hutId,
                    ownerId,
                    revision,
                    state,
                    completedTasks,
                    totalTasks,
                    missingMaterials,
                    conflicts,
                    diagnostics);
            missingMaterials = trusted.missingMaterials();
            conflicts = trusted.conflicts();
            diagnostics = trusted.diagnostics();
        }

        @Override
        public Type<JobSnapshot> type() {
            return TYPE;
        }
    }

    public record JobDelta(
            String jobId,
            UUID hutId,
            UUID ownerId,
            long revision,
            BuildJobState state,
            int completedTasks,
            int totalTasks,
            Map<String, Integer> missingMaterials,
            List<GridPos> conflicts,
            List<DiagnosticView> diagnostics) implements CustomPacketPayload, JobView {
        public static final Type<JobDelta> TYPE = JobPayloads.type("job_delta");
        public static final StreamCodec<RegistryFriendlyByteBuf, JobDelta> CODEC = StreamCodec.of(
                (buffer, payload) -> writeView(buffer, payload),
                buffer -> readView(buffer).delta());

        public JobDelta {
            ViewData trusted = validateView(
                    jobId,
                    hutId,
                    ownerId,
                    revision,
                    state,
                    completedTasks,
                    totalTasks,
                    missingMaterials,
                    conflicts,
                    diagnostics);
            missingMaterials = trusted.missingMaterials();
            conflicts = trusted.conflicts();
            diagnostics = trusted.diagnostics();
        }

        public JobSnapshot snapshot() {
            return new JobSnapshot(
                    jobId,
                    hutId,
                    ownerId,
                    revision,
                    state,
                    completedTasks,
                    totalTasks,
                    missingMaterials,
                    conflicts,
                    diagnostics);
        }

        @Override
        public Type<JobDelta> type() {
            return TYPE;
        }
    }

    public record JobCommandResult(
            String jobId,
            boolean accepted,
            JobReplicationService.Rejection rejection,
            long currentRevision) implements CustomPacketPayload {
        public static final Type<JobCommandResult> TYPE = JobPayloads.type("job_command_result");
        public static final StreamCodec<RegistryFriendlyByteBuf, JobCommandResult> CODEC = StreamCodec.of(
                JobCommandResult::encode,
                JobCommandResult::decode);

        public JobCommandResult {
            requireIdentifier(jobId, "jobId");
            Objects.requireNonNull(rejection, "rejection");
            if (currentRevision < -1) {
                throw new IllegalArgumentException("currentRevision must be -1 or non-negative");
            }
            if (accepted != (rejection == JobReplicationService.Rejection.NONE)) {
                throw new IllegalArgumentException("Command acceptance and rejection disagree");
            }
        }

        @Override
        public Type<JobCommandResult> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, JobCommandResult payload) {
            buffer.writeUtf(payload.jobId(), 160);
            buffer.writeBoolean(payload.accepted());
            buffer.writeVarInt(payload.rejection().ordinal());
            buffer.writeLong(payload.currentRevision());
        }

        private static JobCommandResult decode(RegistryFriendlyByteBuf buffer) {
            return new JobCommandResult(
                    buffer.readUtf(160),
                    buffer.readBoolean(),
                    readEnum(buffer, JobReplicationService.Rejection.values(), "job command rejection"),
                    buffer.readLong());
        }
    }

    public record DiagnosticView(
            String code,
            String message,
            boolean recoverable,
            Optional<GridPos> position) {
        public DiagnosticView {
            requireText(code, "code", 80);
            requireText(message, "message", 500);
            position = Objects.requireNonNull(position, "position");
        }
    }

    private interface JobView {
        String jobId();

        UUID hutId();

        UUID ownerId();

        long revision();

        BuildJobState state();

        int completedTasks();

        int totalTasks();

        Map<String, Integer> missingMaterials();

        List<GridPos> conflicts();

        List<DiagnosticView> diagnostics();
    }

    private static void writeView(RegistryFriendlyByteBuf buffer, JobView view) {
        buffer.writeUtf(view.jobId(), 160);
        buffer.writeUUID(view.hutId());
        buffer.writeUUID(view.ownerId());
        buffer.writeVarLong(view.revision());
        buffer.writeVarInt(view.state().ordinal());
        buffer.writeVarInt(view.completedTasks());
        buffer.writeVarInt(view.totalTasks());
        buffer.writeVarInt(view.missingMaterials().size());
        view.missingMaterials().forEach((material, count) -> {
            buffer.writeUtf(material, 160);
            buffer.writeVarInt(count);
        });
        buffer.writeVarInt(view.conflicts().size());
        view.conflicts().forEach(position -> writeGridPos(buffer, position));
        buffer.writeVarInt(view.diagnostics().size());
        view.diagnostics().forEach(diagnostic -> {
            buffer.writeUtf(diagnostic.code(), 80);
            buffer.writeUtf(diagnostic.message(), 500);
            buffer.writeBoolean(diagnostic.recoverable());
            buffer.writeBoolean(diagnostic.position().isPresent());
            diagnostic.position().ifPresent(position -> writeGridPos(buffer, position));
        });
    }

    private static ViewData readView(RegistryFriendlyByteBuf buffer) {
        String jobId = buffer.readUtf(160);
        UUID hutId = buffer.readUUID();
        UUID ownerId = buffer.readUUID();
        long revision = buffer.readVarLong();
        BuildJobState state = readEnum(buffer, BuildJobState.values(), "BuildJob state");
        int completedTasks = buffer.readVarInt();
        int totalTasks = buffer.readVarInt();
        int missingCount = readSize(buffer, MAX_MISSING_MATERIALS, "missing material");
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (int index = 0; index < missingCount; index++) {
            String material = buffer.readUtf(160);
            int count = buffer.readVarInt();
            if (missing.putIfAbsent(material, count) != null) {
                throw new IllegalArgumentException("Duplicate missing material: " + material);
            }
        }
        int conflictCount = readSize(buffer, MAX_CONFLICTS, "conflict");
        List<GridPos> conflicts = new ArrayList<>(conflictCount);
        for (int index = 0; index < conflictCount; index++) {
            conflicts.add(readGridPos(buffer));
        }
        int diagnosticCount = readSize(buffer, MAX_DIAGNOSTICS, "diagnostic");
        List<DiagnosticView> diagnostics = new ArrayList<>(diagnosticCount);
        for (int index = 0; index < diagnosticCount; index++) {
            String code = buffer.readUtf(80);
            String message = buffer.readUtf(500);
            boolean recoverable = buffer.readBoolean();
            Optional<GridPos> position = buffer.readBoolean()
                    ? Optional.of(readGridPos(buffer))
                    : Optional.empty();
            diagnostics.add(new DiagnosticView(code, message, recoverable, position));
        }
        return validateView(
                jobId,
                hutId,
                ownerId,
                revision,
                state,
                completedTasks,
                totalTasks,
                missing,
                conflicts,
                diagnostics);
    }

    private static ViewData validateView(
            String jobId,
            UUID hutId,
            UUID ownerId,
            long revision,
            BuildJobState state,
            int completedTasks,
            int totalTasks,
            Map<String, Integer> missingMaterials,
            List<GridPos> conflicts,
            List<DiagnosticView> diagnostics) {
        requireIdentifier(jobId, "jobId");
        Objects.requireNonNull(hutId, "hutId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(state, "state");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (completedTasks < 0 || totalTasks < completedTasks || totalTasks > 30_000) {
            throw new IllegalArgumentException("invalid BuildJob progress");
        }
        Map<String, Integer> trustedMissing = Map.copyOf(Objects.requireNonNull(
                missingMaterials, "missingMaterials"));
        if (trustedMissing.size() > MAX_MISSING_MATERIALS) {
            throw new IllegalArgumentException("missing material list exceeds bound");
        }
        trustedMissing.forEach((material, count) -> {
            requireIdentifier(material, "material");
            if (count == null || count < 1 || count > 30_000) {
                throw new IllegalArgumentException("missing material count is outside bound");
            }
        });
        List<GridPos> trustedConflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
        List<DiagnosticView> trustedDiagnostics = List.copyOf(Objects.requireNonNull(
                diagnostics, "diagnostics"));
        if (trustedConflicts.size() > MAX_CONFLICTS || trustedDiagnostics.size() > MAX_DIAGNOSTICS) {
            throw new IllegalArgumentException("job replication collection exceeds bound");
        }
        return new ViewData(
                jobId,
                hutId,
                ownerId,
                revision,
                state,
                completedTasks,
                totalTasks,
                trustedMissing,
                trustedConflicts,
                trustedDiagnostics);
    }

    private static int readSize(RegistryFriendlyByteBuf buffer, int maximum, String name) {
        int size = buffer.readVarInt();
        if (size < 0 || size > maximum) {
            throw new IllegalArgumentException(name + " list exceeds bound");
        }
        return size;
    }

    private static void writeGridPos(RegistryFriendlyByteBuf buffer, GridPos position) {
        buffer.writeInt(position.x());
        buffer.writeInt(position.y());
        buffer.writeInt(position.z());
    }

    private static GridPos readGridPos(RegistryFriendlyByteBuf buffer) {
        return new GridPos(buffer.readInt(), buffer.readInt(), buffer.readInt());
    }

    private static void writeBlockPos(RegistryFriendlyByteBuf buffer, BlockPos position) {
        buffer.writeInt(position.getX());
        buffer.writeInt(position.getY());
        buffer.writeInt(position.getZ());
    }

    private static BlockPos readBlockPos(RegistryFriendlyByteBuf buffer) {
        return new BlockPos(buffer.readInt(), buffer.readInt(), buffer.readInt());
    }

    private static <T> T readEnum(RegistryFriendlyByteBuf buffer, T[] values, String name) {
        int ordinal = buffer.readVarInt();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("Unknown " + name);
        }
        return values[ordinal];
    }

    private static void requireIdentifier(String value, String name) {
        requireText(value, name, 160);
    }

    private static void requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maximumLength + " characters");
        }
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(
                TerrainwrightMod.MOD_ID, path));
    }

    private record ViewData(
            String jobId,
            UUID hutId,
            UUID ownerId,
            long revision,
            BuildJobState state,
            int completedTasks,
            int totalTasks,
            Map<String, Integer> missingMaterials,
            List<GridPos> conflicts,
            List<DiagnosticView> diagnostics) {
        private JobSnapshot snapshot() {
            return new JobSnapshot(
                    jobId,
                    hutId,
                    ownerId,
                    revision,
                    state,
                    completedTasks,
                    totalTasks,
                    missingMaterials,
                    conflicts,
                    diagnostics);
        }

        private JobDelta delta() {
            return new JobDelta(
                    jobId,
                    hutId,
                    ownerId,
                    revision,
                    state,
                    completedTasks,
                    totalTasks,
                    missingMaterials,
                    conflicts,
                    diagnostics);
        }
    }
}
