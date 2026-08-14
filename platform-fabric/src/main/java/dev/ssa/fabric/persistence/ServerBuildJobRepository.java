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
import dev.ssa.construction.plan.TaskGraph;
import dev.ssa.construction.scaffold.ScaffoldProvenance;
import dev.ssa.construction.scaffold.ScaffoldProvenance.Cell;
import dev.ssa.fabric.TerrainwrightMod;
import dev.ssa.fabric.lifecycle.BuilderLifecycleTombstone;
import dev.ssa.fabric.lifecycle.BuilderLifecycleTombstone.Cause;
import dev.ssa.fabric.lifecycle.BuilderLifecycleTombstone.Observation;
import dev.ssa.fabric.lifecycle.BuilderLifecycleTombstone.Status;
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
    private static final Codec<Status> LIFECYCLE_STATUS_CODEC = enumCodec(Status.class);
    private static final Codec<LifecyclePayload> LIFECYCLE_PAYLOAD_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUID_CODEC.fieldOf("builder_id").forGetter(LifecyclePayload::builderId),
                    LIFECYCLE_STATUS_CODEC.optionalFieldOf("status")
                            .forGetter(LifecyclePayload::status),
                    OBSERVATION_CODEC.optionalFieldOf("tombstone").forGetter(LifecyclePayload::tombstone),
                    Codec.LONG.fieldOf("revision").forGetter(LifecyclePayload::revision),
                    Codec.INT.fieldOf("format_version").forGetter(LifecyclePayload::formatVersion))
                    .apply(instance, LifecyclePayload::new));
    private static final Codec<BuilderLifecycleTombstone> LIFECYCLE_CODEC = LIFECYCLE_PAYLOAD_CODEC.comapFlatMap(
            ServerBuildJobRepository::decodeLifecycle,
            lifecycle -> new LifecyclePayload(
                    lifecycle.builderId(),
                    Optional.of(lifecycle.status()),
                    lifecycle.tombstone(),
                    lifecycle.revision(),
                    lifecycle.formatVersion()));
    private static final Codec<HutState> HUT_STATE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUID_CODEC.fieldOf("hut_id").forGetter(HutState::hutId),
            UUID_CODEC.fieldOf("owner_id").forGetter(HutState::ownerId),
            Codec.STRING.optionalFieldOf("active_job_id").forGetter(HutState::activeJobId),
            CONTAINER_BINDING_CODEC.optionalFieldOf("container_binding").forGetter(HutState::containerBinding),
            LIFECYCLE_CODEC.optionalFieldOf("builder_lifecycle").forGetter(HutState::builderLifecycle),
            Codec.LONG.fieldOf("revision").forGetter(HutState::revision))
            .apply(instance, HutState::new));
    private static final Codec<Cell> SCAFFOLD_CELL_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            GRID_POS_CODEC.fieldOf("position").forGetter(Cell::position),
            BLOCK_STATE_CODEC.fieldOf("state").forGetter(Cell::state),
            Codec.STRING.optionalFieldOf("placement_operation_id").forGetter(Cell::placementOperationId),
            Codec.STRING.optionalFieldOf("removal_operation_id").forGetter(Cell::removalOperationId))
            .apply(instance, Cell::new));
    private static final Codec<ScaffoldProvenance> SCAFFOLD_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("job_id").forGetter(ScaffoldProvenance::jobId),
                    Codec.STRING.fieldOf("plan_id").forGetter(ScaffoldProvenance::planId),
                    Codec.STRING.fieldOf("task_id").forGetter(ScaffoldProvenance::taskId),
                    SCAFFOLD_CELL_CODEC.listOf().fieldOf("cells").forGetter(ScaffoldProvenance::cells),
                    Codec.LONG.fieldOf("revision").forGetter(ScaffoldProvenance::revision))
                    .apply(instance, ScaffoldProvenance::new));
    private static final Codec<Payload> PAYLOAD_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("format_version").forGetter(Payload::formatVersion),
            BUILD_JOB_CODEC.listOf().fieldOf("jobs").forGetter(Payload::jobs),
            HUT_STATE_CODEC.listOf().fieldOf("huts").forGetter(Payload::huts),
            Codec.unboundedMap(Codec.STRING, Codec.STRING)
                    .optionalFieldOf("builder_plans", Map.of())
                    .forGetter(Payload::encodedPlans),
            SCAFFOLD_CODEC.listOf().optionalFieldOf("scaffolds", List.of()).forGetter(Payload::scaffolds))
            .apply(instance, Payload::new));

    public static final Codec<ServerBuildJobRepository> CODEC = PAYLOAD_CODEC.comapFlatMap(
            ServerBuildJobRepository::decode,
            ServerBuildJobRepository::payload);
    public static final SavedDataType<ServerBuildJobRepository> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(TerrainwrightMod.MOD_ID, "build_jobs"),
            ServerBuildJobRepository::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<String, BuildJob> jobs;
    private final Map<UUID, HutState> huts;
    private final Map<String, TaskGraph> plans;
    private final Map<String, ScaffoldProvenance> scaffolds;

    public ServerBuildJobRepository() {
        this(Map.of(), Map.of(), Map.of(), Map.of());
    }

    private ServerBuildJobRepository(
            Map<String, BuildJob> jobs,
            Map<UUID, HutState> huts,
            Map<String, TaskGraph> plans,
            Map<String, ScaffoldProvenance> scaffolds) {
        this.jobs = new LinkedHashMap<>(jobs);
        this.huts = new LinkedHashMap<>(huts);
        this.plans = new LinkedHashMap<>(plans);
        this.scaffolds = new LinkedHashMap<>(scaffolds);
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

    public Map<String, TaskGraph> plans() {
        return Map.copyOf(plans);
    }

    public Map<String, ScaffoldProvenance> scaffolds() {
        return Map.copyOf(scaffolds);
    }

    public Optional<BuildJob> findJob(String jobId) {
        return Optional.ofNullable(jobs.get(Objects.requireNonNull(jobId, "jobId")));
    }

    public Optional<HutState> findHut(UUID hutId) {
        return Optional.ofNullable(huts.get(Objects.requireNonNull(hutId, "hutId")));
    }

    public Optional<TaskGraph> findPlan(String jobId) {
        return Optional.ofNullable(plans.get(Objects.requireNonNull(jobId, "jobId")));
    }

    public Optional<ScaffoldProvenance> findScaffold(String planId) {
        return Optional.ofNullable(scaffolds.get(Objects.requireNonNull(planId, "planId")));
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

    public void savePlan(String jobId, TaskGraph plan) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(plan, "plan");
        BuildJob job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalStateException("Builder plan references an unknown BuildJob: " + jobId);
        }
        if (!plan.tasks().keySet().containsAll(job.completedTaskIds())) {
            throw new IllegalStateException("Builder plan omits completed BuildJob tasks: " + jobId);
        }
        TaskGraph current = plans.get(jobId);
        if (current != null && !BuilderPlanCodec.encode(current).equals(BuilderPlanCodec.encode(plan))) {
            throw new IllegalStateException("Builder plan cannot change after confirmation: " + jobId);
        }
        if (current == null) {
            plans.put(jobId, plan);
            setDirty();
        }
    }

    public void saveScaffold(ScaffoldProvenance scaffold) {
        Objects.requireNonNull(scaffold, "scaffold");
        ScaffoldProvenance current = scaffolds.get(scaffold.planId());
        rejectStaleOrConflicting(
                "Scaffold provenance", current, scaffold, ScaffoldProvenance::revision);
        Map<String, ScaffoldProvenance> proposed = new LinkedHashMap<>(scaffolds);
        proposed.put(scaffold.planId(), scaffold);
        String validationError = scaffoldValidationError(scaffold, jobs, plans, proposed);
        if (validationError != null) {
            throw new IllegalStateException(validationError);
        }
        if (!scaffold.equals(current)) {
            scaffolds.put(scaffold.planId(), scaffold);
            setDirty();
        }
    }

    public void removeHut(UUID hutId) {
        HutState hut = huts.get(Objects.requireNonNull(hutId, "hutId"));
        if (hut == null) {
            return;
        }
        if (hut.activeJobId().isEmpty()) {
            huts.remove(hutId);
            setDirty();
            return;
        }
        hut.activeJobId().flatMap(this::findJob).ifPresent(job -> {
            if (job.state() == BuildJobState.IDLE) {
                job = job.transitionTo(BuildJobState.PREPARING);
            }
            if (job.state().canTransitionTo(BuildJobState.ORPHANED)) {
                saveJob(job.transitionTo(BuildJobState.ORPHANED));
            }
        });
        // Keep the last Hut association as durable recovery evidence. The ORPHANED
        // job state makes the physical Hut loss authoritative while retaining the
        // Builder identity, binding, and WAL lookup key needed after restart.
        setDirty();
    }

    private static DataResult<BuilderLifecycleTombstone> decodeLifecycle(LifecyclePayload payload) {
        Status status;
        if (payload.formatVersion() == 1) {
            if (payload.status().isPresent()) {
                return DataResult.error(() -> "Legacy Builder lifecycle must not contain a status");
            }
            status = payload.tombstone().isPresent() ? Status.TOMBSTONED : Status.ACTIVE;
        } else if (payload.formatVersion() == BuilderLifecycleTombstone.CURRENT_FORMAT_VERSION) {
            if (payload.status().isEmpty()) {
                return DataResult.error(() -> "Current Builder lifecycle is missing its status");
            }
            status = payload.status().orElseThrow();
        } else {
            return DataResult.error(() ->
                    "Unsupported Builder lifecycle format version: " + payload.formatVersion());
        }
        try {
            return DataResult.success(new BuilderLifecycleTombstone(
                    payload.builderId(),
                    status,
                    payload.tombstone(),
                    payload.revision(),
                    BuilderLifecycleTombstone.CURRENT_FORMAT_VERSION));
        } catch (IllegalArgumentException failure) {
            return DataResult.error(failure::getMessage);
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
        Map<String, String> encodedPlans = new java.util.TreeMap<>();
        plans.forEach((jobId, plan) -> encodedPlans.put(jobId, BuilderPlanCodec.encode(plan)));
        List<ScaffoldProvenance> orderedScaffolds = scaffolds.values().stream()
                .sorted(Comparator.comparing(ScaffoldProvenance::planId))
                .toList();
        return new Payload(
                CURRENT_FORMAT_VERSION,
                orderedJobs,
                orderedHuts,
                Map.copyOf(encodedPlans),
                orderedScaffolds);
    }

    private static DataResult<ServerBuildJobRepository> decode(Payload payload) {
        if (payload.formatVersion() != CURRENT_FORMAT_VERSION) {
            return DataResult.error(() -> "Unsupported repository format version: " + payload.formatVersion());
        }
        try {
            Map<String, BuildJob> jobs = uniqueMap(payload.jobs(), BuildJob::jobId, "BuildJob");
            Map<UUID, HutState> huts = uniqueMap(payload.huts(), HutState::hutId, "Hut");
            Map<String, TaskGraph> plans = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : new java.util.TreeMap<>(payload.encodedPlans()).entrySet()) {
                if (!jobs.containsKey(entry.getKey())) {
                    return DataResult.error(() -> "Builder plan references an unknown BuildJob");
                }
                TaskGraph plan = BuilderPlanCodec.decode(entry.getValue());
                if (!plan.tasks().keySet().containsAll(jobs.get(entry.getKey()).completedTaskIds())) {
                    return DataResult.error(() -> "Builder plan omits completed BuildJob tasks");
                }
                plans.put(entry.getKey(), plan);
            }
            Map<String, ScaffoldProvenance> scaffolds = uniqueMap(
                    payload.scaffolds(), ScaffoldProvenance::planId, "Scaffold plan");
            for (ScaffoldProvenance scaffold : scaffolds.values()) {
                String validationError = scaffoldValidationError(scaffold, jobs, plans, scaffolds);
                if (validationError != null) {
                    return DataResult.error(() -> validationError);
                }
            }
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
            return DataResult.success(new ServerBuildJobRepository(jobs, huts, plans, scaffolds));
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

    private static String scaffoldValidationError(
            ScaffoldProvenance scaffold,
            Map<String, BuildJob> jobs,
            Map<String, TaskGraph> plans,
            Map<String, ScaffoldProvenance> scaffolds) {
        if (!jobs.containsKey(scaffold.jobId())) {
            return "Scaffold references an unknown BuildJob: " + scaffold.jobId();
        }
        TaskGraph plan = plans.get(scaffold.jobId());
        if (plan == null || !plan.tasks().containsKey(scaffold.taskId())) {
            return "Scaffold references a task outside its durable Builder plan: " + scaffold.taskId();
        }
        long activeForJob = scaffolds.values().stream()
                .filter(candidate -> candidate.jobId().equals(scaffold.jobId()))
                .filter(candidate -> !candidate.isCleaned())
                .count();
        if (!scaffold.isCleaned() && activeForJob != 1) {
            return "BuildJob must have exactly one active scaffold plan: " + scaffold.jobId();
        }
        return null;
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

    private record LifecyclePayload(
            UUID builderId,
            Optional<Status> status,
            Optional<Observation> tombstone,
            long revision,
            int formatVersion) {
    }

    private record Payload(
            int formatVersion,
            List<BuildJob> jobs,
            List<HutState> huts,
            Map<String, String> encodedPlans,
            List<ScaffoldProvenance> scaffolds) {
        private Payload {
            jobs = List.copyOf(Objects.requireNonNull(jobs, "jobs"));
            huts = List.copyOf(Objects.requireNonNull(huts, "huts"));
            encodedPlans = Map.copyOf(Objects.requireNonNull(encodedPlans, "encodedPlans"));
            scaffolds = List.copyOf(Objects.requireNonNull(scaffolds, "scaffolds"));
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
