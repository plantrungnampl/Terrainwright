package dev.ssa.fabric.persistence;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.job.BuildJob.Diagnostic;
import dev.ssa.construction.job.BuildJob.Severity;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.construction.journal.JournalEntry;
import dev.ssa.fabric.SmartSurvivalArchitectMod;
import dev.ssa.fabric.lifecycle.BuilderLifecycleTombstone;
import dev.ssa.fabric.lifecycle.BuilderLifecycleTombstone.Cause;
import dev.ssa.fabric.lifecycle.BuilderLifecycleTombstone.Observation;
import dev.ssa.fabric.link.ContainerBinding;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class ServerBuildJobRepository extends SavedData {
    public static final int CURRENT_FORMAT_VERSION = 1;

    private static final Codec<UUID> UUID_CODEC = stringCodec("UUID", UUID::fromString, UUID::toString);
    private static final Codec<NamespacedId> NAMESPACED_ID_CODEC =
            stringCodec("namespaced ID", NamespacedId::parse, NamespacedId::toString);
    private static final Codec<BuildPhase> BUILD_PHASE_CODEC = enumCodec(BuildPhase.class);
    private static final Codec<BuildJobState> BUILD_JOB_STATE_CODEC = enumCodec(BuildJobState.class);
    private static final Codec<Severity> SEVERITY_CODEC = enumCodec(Severity.class);
    private static final Codec<Cause> CAUSE_CODEC = enumCodec(Cause.class);
    private static final Codec<GridPos> GRID_POS_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("x").forGetter(GridPos::x),
            Codec.INT.fieldOf("y").forGetter(GridPos::y),
            Codec.INT.fieldOf("z").forGetter(GridPos::z))
            .apply(instance, GridPos::new));
    private static final Codec<BlockPos> BLOCK_POS_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("x").forGetter(pos -> pos.getX()),
            Codec.INT.fieldOf("y").forGetter(pos -> pos.getY()),
            Codec.INT.fieldOf("z").forGetter(pos -> pos.getZ()))
            .apply(instance, BlockPos::new));
    private static final Codec<BlockStateSpec> BLOCK_STATE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            NAMESPACED_ID_CODEC.fieldOf("block_id").forGetter(BlockStateSpec::blockId),
            Codec.unboundedMap(Codec.STRING, Codec.STRING)
                    .fieldOf("properties")
                    .forGetter(BlockStateSpec::properties))
            .apply(instance, BlockStateSpec::new));
    private static final Codec<JournalEntry> JOURNAL_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("sequence").forGetter(JournalEntry::sequence),
            Codec.STRING.fieldOf("entry_id").forGetter(JournalEntry::entryId),
            Codec.STRING.fieldOf("operation_id").forGetter(JournalEntry::operationId),
            Codec.STRING.fieldOf("task_id").forGetter(JournalEntry::taskId),
            GRID_POS_CODEC.fieldOf("position").forGetter(JournalEntry::position),
            BLOCK_STATE_CODEC.fieldOf("previous_state").forGetter(JournalEntry::previousState),
            BLOCK_STATE_CODEC.fieldOf("written_state").forGetter(JournalEntry::writtenState),
            Codec.LONG.fieldOf("committed_revision").forGetter(JournalEntry::committedRevision))
            .apply(instance, JournalEntry::new));
    private static final Codec<Diagnostic> DIAGNOSTIC_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("code").forGetter(Diagnostic::code),
            SEVERITY_CODEC.fieldOf("severity").forGetter(Diagnostic::severity),
            Codec.STRING.fieldOf("message").forGetter(Diagnostic::message),
            Codec.BOOL.fieldOf("recoverable").forGetter(Diagnostic::recoverable),
            Codec.LONG.fieldOf("revision").forGetter(Diagnostic::revision),
            GRID_POS_CODEC.optionalFieldOf("position").forGetter(Diagnostic::position))
            .apply(instance, Diagnostic::new));
    private static final Codec<BuildJob> BUILD_JOB_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("job_id").forGetter(BuildJob::jobId),
            Codec.STRING.fieldOf("owner_id").forGetter(BuildJob::ownerId),
            Codec.STRING.fieldOf("hut_id").forGetter(BuildJob::hutId),
            Codec.STRING.fieldOf("blueprint_id").forGetter(BuildJob::blueprintId),
            Codec.STRING.fieldOf("blueprint_hash").forGetter(BuildJob::blueprintHash),
            NAMESPACED_ID_CODEC.fieldOf("world_id").forGetter(BuildJob::worldId),
            GRID_POS_CODEC.fieldOf("origin").forGetter(BuildJob::origin),
            Codec.INT.fieldOf("rotation").forGetter(BuildJob::rotation),
            BUILD_JOB_STATE_CODEC.fieldOf("state").forGetter(BuildJob::state),
            BUILD_PHASE_CODEC.optionalFieldOf("current_phase").forGetter(BuildJob::currentPhase),
            Codec.STRING.listOf().fieldOf("completed_task_ids")
                    .forGetter(job -> job.completedTaskIds().stream().sorted().toList()),
            DIAGNOSTIC_CODEC.listOf().fieldOf("diagnostics").forGetter(BuildJob::failedTaskDiagnostics),
            JOURNAL_CODEC.listOf().fieldOf("block_journal").forGetter(BuildJob::blockJournal),
            Codec.LONG.fieldOf("revision").forGetter(BuildJob::revision),
            Codec.INT.fieldOf("format_version").forGetter(BuildJob::formatVersion))
            .apply(instance, (jobId, ownerId, hutId, blueprintId, blueprintHash, worldId, origin,
                    rotation, state, currentPhase, completedTaskIds, diagnostics, journal, revision, formatVersion) ->
                    new BuildJob(
                            jobId,
                            ownerId,
                            hutId,
                            blueprintId,
                            blueprintHash,
                            worldId,
                            origin,
                            rotation,
                            state,
                            currentPhase,
                            Set.copyOf(completedTaskIds),
                            diagnostics,
                            journal,
                            revision,
                            formatVersion)));
    private static final Codec<ContainerBinding> CONTAINER_BINDING_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Identifier.CODEC.fieldOf("dimension_id").forGetter(ContainerBinding::dimensionId),
                    BLOCK_POS_CODEC.fieldOf("primary_pos").forGetter(ContainerBinding::primaryPos),
                    BLOCK_POS_CODEC.optionalFieldOf("partner_pos").forGetter(ContainerBinding::partnerPos),
                    UUID_CODEC.fieldOf("inventory_id").forGetter(ContainerBinding::inventoryId),
                    Codec.LONG.fieldOf("revision").forGetter(ContainerBinding::revision),
                    Codec.INT.fieldOf("format_version").forGetter(ContainerBinding::formatVersion))
                    .apply(instance, ContainerBinding::new));
    private static final Codec<Observation> OBSERVATION_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            CAUSE_CODEC.fieldOf("cause").forGetter(Observation::cause),
            Codec.LONG.fieldOf("observed_game_time").forGetter(Observation::observedGameTime))
            .apply(instance, Observation::new));
    private static final Codec<BuilderLifecycleTombstone> LIFECYCLE_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUID_CODEC.fieldOf("builder_id").forGetter(BuilderLifecycleTombstone::builderId),
                    OBSERVATION_CODEC.optionalFieldOf("tombstone").forGetter(BuilderLifecycleTombstone::tombstone),
                    Codec.LONG.fieldOf("revision").forGetter(BuilderLifecycleTombstone::revision),
                    Codec.INT.fieldOf("format_version").forGetter(BuilderLifecycleTombstone::formatVersion))
                    .apply(instance, BuilderLifecycleTombstone::new));
    private static final Codec<HutState> HUT_STATE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUID_CODEC.fieldOf("hut_id").forGetter(HutState::hutId),
            UUID_CODEC.fieldOf("owner_id").forGetter(HutState::ownerId),
            Codec.STRING.optionalFieldOf("active_job_id").forGetter(HutState::activeJobId),
            CONTAINER_BINDING_CODEC.optionalFieldOf("container_binding").forGetter(HutState::containerBinding),
            LIFECYCLE_CODEC.optionalFieldOf("builder_lifecycle").forGetter(HutState::builderLifecycle),
            Codec.LONG.fieldOf("revision").forGetter(HutState::revision))
            .apply(instance, HutState::new));
    private static final Codec<Payload> PAYLOAD_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("format_version").forGetter(Payload::formatVersion),
            BUILD_JOB_CODEC.listOf().fieldOf("jobs").forGetter(Payload::jobs),
            HUT_STATE_CODEC.listOf().fieldOf("huts").forGetter(Payload::huts))
            .apply(instance, Payload::new));

    public static final Codec<ServerBuildJobRepository> CODEC = PAYLOAD_CODEC.comapFlatMap(
            ServerBuildJobRepository::decode,
            ServerBuildJobRepository::payload);
    public static final SavedDataType<ServerBuildJobRepository> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SmartSurvivalArchitectMod.MOD_ID, "build_jobs"),
            ServerBuildJobRepository::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<String, BuildJob> jobs;
    private final Map<UUID, HutState> huts;

    public ServerBuildJobRepository() {
        this(Map.of(), Map.of());
    }

    private ServerBuildJobRepository(Map<String, BuildJob> jobs, Map<UUID, HutState> huts) {
        this.jobs = new LinkedHashMap<>(jobs);
        this.huts = new LinkedHashMap<>(huts);
    }

    public static ServerBuildJobRepository get(ServerLevel level) {
        return Objects.requireNonNull(level, "level").getDataStorage().computeIfAbsent(TYPE);
    }

    public Map<String, BuildJob> jobs() {
        return Map.copyOf(jobs);
    }

    public Map<UUID, HutState> huts() {
        return Map.copyOf(huts);
    }

    public Optional<BuildJob> findJob(String jobId) {
        return Optional.ofNullable(jobs.get(Objects.requireNonNull(jobId, "jobId")));
    }

    public Optional<HutState> findHut(UUID hutId) {
        return Optional.ofNullable(huts.get(Objects.requireNonNull(hutId, "hutId")));
    }

    public void saveJob(BuildJob job) {
        Objects.requireNonNull(job, "job");
        BuildJob current = jobs.get(job.jobId());
        rejectStaleOrConflicting("BuildJob", current, job, BuildJob::revision);
        if (!job.equals(current)) {
            jobs.put(job.jobId(), job);
            setDirty();
        }
    }

    public void saveHutState(HutState hut) {
        Objects.requireNonNull(hut, "hut");
        hut.activeJobId().ifPresent(jobId -> {
            BuildJob job = jobs.get(jobId);
            if (job == null) {
                throw new IllegalStateException("Hut references an unknown BuildJob: " + jobId);
            }
            if (!job.hutId().equals(hut.hutId().toString())
                    || !job.ownerId().equals(hut.ownerId().toString())) {
                throw new IllegalStateException("Hut and BuildJob ownership do not match");
            }
        });
        HutState current = huts.get(hut.hutId());
        rejectStaleOrConflicting("Hut state", current, hut, HutState::revision);
        if (!hut.equals(current)) {
            huts.put(hut.hutId(), hut);
            setDirty();
        }
    }

    public void removeHut(UUID hutId) {
        if (huts.remove(Objects.requireNonNull(hutId, "hutId")) != null) {
            setDirty();
        }
    }

    private static <T> void rejectStaleOrConflicting(
            String label,
            T current,
            T replacement,
            Function<T, Long> revision) {
        if (current == null) {
            return;
        }
        long currentRevision = revision.apply(current);
        long replacementRevision = revision.apply(replacement);
        if (replacementRevision < currentRevision) {
            throw new IllegalStateException(label + " write is stale");
        }
        if (replacementRevision == currentRevision && !current.equals(replacement)) {
            throw new IllegalStateException(label + " revision has conflicting content");
        }
    }

    private Payload payload() {
        List<BuildJob> orderedJobs = jobs.values().stream()
                .sorted(Comparator.comparing(BuildJob::jobId))
                .toList();
        List<HutState> orderedHuts = huts.values().stream()
                .sorted(Comparator.comparing(state -> state.hutId().toString()))
                .toList();
        return new Payload(CURRENT_FORMAT_VERSION, orderedJobs, orderedHuts);
    }

    private static DataResult<ServerBuildJobRepository> decode(Payload payload) {
        if (payload.formatVersion() != CURRENT_FORMAT_VERSION) {
            return DataResult.error(() -> "Unsupported repository format version: " + payload.formatVersion());
        }
        try {
            Map<String, BuildJob> jobs = uniqueMap(payload.jobs(), BuildJob::jobId, "BuildJob");
            Map<UUID, HutState> huts = uniqueMap(payload.huts(), HutState::hutId, "Hut");
            for (HutState hut : huts.values()) {
                if (hut.activeJobId().isPresent()) {
                    BuildJob job = jobs.get(hut.activeJobId().orElseThrow());
                    if (job == null) {
                        return DataResult.error(() -> "Hut references an unknown BuildJob");
                    }
                    if (!job.hutId().equals(hut.hutId().toString())
                            || !job.ownerId().equals(hut.ownerId().toString())) {
                        return DataResult.error(() -> "Hut and BuildJob ownership do not match");
                    }
                }
            }
            return DataResult.success(new ServerBuildJobRepository(jobs, huts));
        } catch (IllegalArgumentException exception) {
            return DataResult.error(exception::getMessage);
        }
    }

    private static <K, V> Map<K, V> uniqueMap(
            List<V> values,
            Function<V, K> key,
            String label) {
        Map<K, V> result = new LinkedHashMap<>();
        for (V value : values) {
            if (result.putIfAbsent(key.apply(value), value) != null) {
                throw new IllegalArgumentException("Duplicate " + label + " identity");
            }
        }
        return result;
    }

    private static <T> Codec<T> stringCodec(
            String label,
            Function<String, T> parser,
            Function<T, String> formatter) {
        return Codec.STRING.comapFlatMap(value -> {
            try {
                return DataResult.success(parser.apply(value));
            } catch (IllegalArgumentException exception) {
                return DataResult.error(() -> "Invalid " + label + ": " + value);
            }
        }, formatter);
    }

    private static <E extends Enum<E>> Codec<E> enumCodec(Class<E> type) {
        return stringCodec(type.getSimpleName(), value -> Enum.valueOf(type, value), Enum::name);
    }

    private record Payload(int formatVersion, List<BuildJob> jobs, List<HutState> huts) {
        private Payload {
            jobs = List.copyOf(Objects.requireNonNull(jobs, "jobs"));
            huts = List.copyOf(Objects.requireNonNull(huts, "huts"));
        }
    }

    public record HutState(
            UUID hutId,
            UUID ownerId,
            Optional<String> activeJobId,
            Optional<ContainerBinding> containerBinding,
            Optional<BuilderLifecycleTombstone> builderLifecycle,
            long revision) {
        public HutState {
            Objects.requireNonNull(hutId, "hutId");
            Objects.requireNonNull(ownerId, "ownerId");
            activeJobId = Objects.requireNonNull(activeJobId, "activeJobId");
            containerBinding = Objects.requireNonNull(containerBinding, "containerBinding");
            builderLifecycle = Objects.requireNonNull(builderLifecycle, "builderLifecycle");
            if (revision < 0) {
                throw new IllegalArgumentException("Hut state revision must not be negative");
            }
            activeJobId.ifPresent(value -> {
                if (value.isBlank() || value.length() > 160) {
                    throw new IllegalArgumentException("activeJobId must contain 1 to 160 characters");
                }
            });
        }
    }
}
