package dev.ssa.fabric.persistence;

import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.construction.plan.TaskGraph;
import dev.ssa.construction.schedule.WorkZone;
import dev.ssa.construction.task.BuildTask;
import dev.ssa.construction.task.TaskOperation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Compact deterministic persistence for the approved task graph, not a mutable Blueprint. */
final class BuilderPlanCodec {
    private static final int MAGIC = 0x53534150;
    private static final int VERSION = 1;
    private static final int MAX_TASKS = 30_000;
    private static final int MAX_PROPERTIES = 64;

    private BuilderPlanCodec() {
    }

    static String encode(TaskGraph graph) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(graph.tasks().size());
                for (BuildTask task : graph.tasks().values()) {
                    writeTask(output, task);
                }
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("could not encode Builder task graph", exception);
        }
    }

    static TaskGraph decode(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                    throw new IllegalArgumentException("unsupported Builder task graph format");
                }
                int taskCount = input.readInt();
                if (taskCount < 0 || taskCount > MAX_TASKS) {
                    throw new IllegalArgumentException("Builder task graph exceeds the V1 task bound");
                }
                List<BuildTask> tasks = new ArrayList<>(taskCount);
                for (int index = 0; index < taskCount; index++) {
                    tasks.add(readTask(input));
                }
                if (input.available() != 0) {
                    throw new IllegalArgumentException("Builder task graph contains trailing bytes");
                }
                return new TaskGraph(tasks);
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException | IllegalStateException exception) {
            throw new IllegalArgumentException("could not decode Builder task graph", exception);
        }
    }

    private static void writeTask(DataOutputStream output, BuildTask task) throws IOException {
        writeText(output, task.id());
        output.writeInt(task.position().x());
        output.writeInt(task.position().y());
        output.writeInt(task.position().z());
        writeText(output, task.operation().name());
        output.writeBoolean(task.materialRequirement().isPresent());
        task.materialRequirement().ifPresent(requirement -> writeMaterial(output, requirement));
        List<String> dependencies = task.dependencyIds().stream().sorted().toList();
        output.writeInt(dependencies.size());
        for (String dependency : dependencies) {
            writeText(output, dependency);
        }
        writeText(output, task.phase().name());
        output.writeInt(task.workZone().gridX());
        output.writeInt(task.workZone().gridZ());
        output.writeBoolean(task.optional());
        output.writeBoolean(task.atomicGroupId().isPresent());
        if (task.atomicGroupId().isPresent()) {
            writeText(output, task.atomicGroupId().orElseThrow());
        }
    }

    private static BuildTask readTask(DataInputStream input) throws IOException {
        String id = readText(input);
        GridPos position = new GridPos(input.readInt(), input.readInt(), input.readInt());
        TaskOperation operation = enumValue(TaskOperation.class, readText(input));
        Optional<BuildTask.MaterialRequirement> material = input.readBoolean()
                ? Optional.of(readMaterial(input))
                : Optional.empty();
        int dependencyCount = input.readInt();
        if (dependencyCount < 0 || dependencyCount > MAX_TASKS) {
            throw new IllegalArgumentException("Builder task dependency count is invalid");
        }
        List<String> dependencies = new ArrayList<>(dependencyCount);
        for (int index = 0; index < dependencyCount; index++) {
            dependencies.add(readText(input));
        }
        BuildPhase phase = enumValue(BuildPhase.class, readText(input));
        WorkZone zone = new WorkZone(input.readInt(), input.readInt());
        boolean optional = input.readBoolean();
        Optional<String> atomicGroup = input.readBoolean()
                ? Optional.of(readText(input))
                : Optional.empty();
        return new BuildTask(id, position, operation, material, java.util.Set.copyOf(dependencies),
                phase, zone, optional, atomicGroup);
    }

    private static void writeMaterial(DataOutputStream output, BuildTask.MaterialRequirement requirement) {
        try {
            writeText(output, requirement.materialRole().name());
            writeBlockState(output, requirement.state());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static BuildTask.MaterialRequirement readMaterial(DataInputStream input) throws IOException {
        return new BuildTask.MaterialRequirement(
                enumValue(MaterialRole.class, readText(input)),
                readBlockState(input));
    }

    private static void writeBlockState(DataOutputStream output, BlockStateSpec state) throws IOException {
        writeText(output, state.blockId().toString());
        if (state.properties().size() > MAX_PROPERTIES) {
            throw new IllegalArgumentException("Builder block state has too many properties");
        }
        output.writeInt(state.properties().size());
        for (Map.Entry<String, String> entry : state.properties().entrySet()) {
            writeText(output, entry.getKey());
            writeText(output, entry.getValue());
        }
    }

    private static BlockStateSpec readBlockState(DataInputStream input) throws IOException {
        NamespacedId blockId = NamespacedId.parse(readText(input));
        int propertyCount = input.readInt();
        if (propertyCount < 0 || propertyCount > MAX_PROPERTIES) {
            throw new IllegalArgumentException("Builder block state property count is invalid");
        }
        Map<String, String> properties = new java.util.TreeMap<>();
        for (int index = 0; index < propertyCount; index++) {
            properties.put(readText(input), readText(input));
        }
        return new BlockStateSpec(blockId, properties);
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > 16_384) {
            throw new IllegalArgumentException("Builder plan text field is out of bounds");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > 16_384) {
            throw new IllegalArgumentException("Builder plan text field is out of bounds");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("truncated Builder plan text field");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown Builder plan enum value: " + value, exception);
        }
    }
}
