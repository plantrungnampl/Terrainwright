package dev.ssa.fabric.network;

import dev.ssa.architect.blueprint.BlockRole;
import dev.ssa.architect.blueprint.Blueprint;
import dev.ssa.architect.blueprint.BlueprintBlock;
import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.blueprint.Room;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.StyleId;
import dev.ssa.architect.scoring.ScoreBreakdown;
import dev.ssa.architect.terrain.TerrainPlan;
import dev.ssa.architect.validation.BlueprintValidation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

final class BlueprintStreamCodec {
    static final StreamCodec<RegistryFriendlyByteBuf, Blueprint> CODEC = StreamCodec.of(
            BlueprintStreamCodec::encode,
            BlueprintStreamCodec::decode);

    private static final int MAX_TEXT = 256;
    private static final int MAX_ROOMS = 128;
    private static final int MAX_CELLS = 16_384;
    private static final int MAX_BLOCKS = 100_000;
    private static final int MAX_DEPENDENCIES = 128;
    private static final int MAX_PROPERTIES = 64;
    private static final int MAX_TERRAIN_CHANGES = 16_384;
    private static final int MAX_VALIDATION_ISSUES = 1_024;

    private BlueprintStreamCodec() {}

    private static void encode(RegistryFriendlyByteBuf buffer, Blueprint blueprint) {
        buffer.writeUUID(blueprint.id());
        buffer.writeLong(blueprint.seed());
        writeString(buffer, blueprint.styleId().toString());
        writePosition(buffer, blueprint.localBounds().minimum());
        writePosition(buffer, blueprint.localBounds().maximum());
        writePositions(buffer, blueprint.footprint(), MAX_CELLS);
        buffer.writeVarInt(blueprint.floors());

        writeSize(buffer, blueprint.rooms().size(), MAX_ROOMS);
        for (Room room : blueprint.rooms()) {
            writeString(buffer, room.id());
            writeString(buffer, room.type().toString());
            buffer.writeVarInt(room.floor());
            writePositions(buffer, room.cells(), MAX_CELLS);
            writeStrings(buffer, room.connectedRoomIds(), MAX_ROOMS);
        }

        writeSize(buffer, blueprint.blocks().size(), MAX_BLOCKS);
        for (BlueprintBlock block : blueprint.blocks()) {
            writePosition(buffer, block.relativePosition());
            writeEnum(buffer, block.blockRole());
            writeEnum(buffer, block.materialRole());
            writeBlockState(buffer, block.placementState());
            writeEnum(buffer, block.phase());
            writePositions(buffer, block.dependencies(), MAX_DEPENDENCIES);
        }

        writeTerrainPlan(buffer, blueprint.terrainPlan());
        writeScore(buffer, blueprint.scoreBreakdown());
        writeValidation(buffer, blueprint.validation());
        buffer.writeVarInt(blueprint.formatVersion());
    }

    private static Blueprint decode(RegistryFriendlyByteBuf buffer) {
        java.util.UUID id = buffer.readUUID();
        long seed = buffer.readLong();
        StyleId styleId = StyleId.parse(readString(buffer));
        Blueprint.LocalBounds bounds = new Blueprint.LocalBounds(
                readPosition(buffer), readPosition(buffer));
        Set<GridPos> footprint = readPositions(buffer, MAX_CELLS);
        int floors = buffer.readVarInt();

        int roomCount = readSize(buffer, MAX_ROOMS);
        List<Room> rooms = new ArrayList<>(roomCount);
        for (int index = 0; index < roomCount; index++) {
            rooms.add(new Room(
                    readString(buffer),
                    NamespacedId.parse(readString(buffer)),
                    buffer.readVarInt(),
                    readPositions(buffer, MAX_CELLS),
                    readStrings(buffer, MAX_ROOMS)));
        }

        int blockCount = readSize(buffer, MAX_BLOCKS);
        List<BlueprintBlock> blocks = new ArrayList<>(blockCount);
        for (int index = 0; index < blockCount; index++) {
            blocks.add(new BlueprintBlock(
                    readPosition(buffer),
                    readEnum(buffer, BlockRole.class),
                    readEnum(buffer, MaterialRole.class),
                    readBlockState(buffer),
                    readEnum(buffer, BuildPhase.class),
                    readPositions(buffer, MAX_DEPENDENCIES)));
        }

        return new Blueprint(
                id,
                seed,
                styleId,
                bounds,
                footprint,
                floors,
                rooms,
                blocks,
                BuildPhase.canonicalOrder(),
                readTerrainPlan(buffer),
                readScore(buffer),
                readValidation(buffer),
                buffer.readVarInt());
    }

    private static void writeTerrainPlan(RegistryFriendlyByteBuf buffer, TerrainPlan plan) {
        writeEnum(buffer, plan.strategy());
        buffer.writeVarInt(plan.removedBlockCount());
        buffer.writeVarInt(plan.filledBlockCount());
        buffer.writeVarInt(plan.maxVerticalCut());
        buffer.writeVarInt(plan.maxVerticalFill());
        buffer.writeBoolean(plan.modifyWater());
        buffer.writeBoolean(plan.modifyLava());
        writeEnum(buffer, plan.salvagePolicy());
        writeSize(buffer, plan.changes().size(), MAX_TERRAIN_CHANGES);
        for (TerrainPlan.TerrainCellChange change : plan.changes()) {
            writePosition(buffer, change.pos());
            writeBlockState(buffer, change.beforeState());
            writeBlockState(buffer, change.afterState());
            writeEnum(buffer, change.dropPolicy());
            writeEnum(buffer, change.xpPolicy());
        }
    }

    private static TerrainPlan readTerrainPlan(RegistryFriendlyByteBuf buffer) {
        TerrainPlan.Strategy strategy = readEnum(buffer, TerrainPlan.Strategy.class);
        int removed = buffer.readVarInt();
        int filled = buffer.readVarInt();
        int maxCut = buffer.readVarInt();
        int maxFill = buffer.readVarInt();
        boolean modifyWater = buffer.readBoolean();
        boolean modifyLava = buffer.readBoolean();
        TerrainPlan.SalvagePolicy salvage = readEnum(buffer, TerrainPlan.SalvagePolicy.class);
        int count = readSize(buffer, MAX_TERRAIN_CHANGES);
        List<TerrainPlan.TerrainCellChange> changes = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            changes.add(new TerrainPlan.TerrainCellChange(
                    readPosition(buffer),
                    readBlockState(buffer),
                    readBlockState(buffer),
                    readEnum(buffer, TerrainPlan.DropPolicy.class),
                    readEnum(buffer, TerrainPlan.XpPolicy.class)));
        }
        return new TerrainPlan(
                strategy,
                removed,
                filled,
                maxCut,
                maxFill,
                modifyWater,
                modifyLava,
                salvage,
                changes);
    }

    private static void writeScore(RegistryFriendlyByteBuf buffer, ScoreBreakdown score) {
        buffer.writeDouble(score.layoutQuality());
        buffer.writeDouble(score.terrainFit());
        buffer.writeDouble(score.styleConsistency());
        buffer.writeDouble(score.accessibility());
        buffer.writeDouble(score.materialEfficiency());
        buffer.writeDouble(score.scenicOrientation());
        buffer.writeDouble(score.total());
    }

    private static ScoreBreakdown readScore(RegistryFriendlyByteBuf buffer) {
        return new ScoreBreakdown(
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble());
    }

    private static void writeValidation(
            RegistryFriendlyByteBuf buffer,
            BlueprintValidation validation) {
        writeSize(buffer, validation.issues().size(), MAX_VALIDATION_ISSUES);
        for (BlueprintValidation.Issue issue : validation.issues()) {
            writeEnum(buffer, issue.severity());
            writeString(buffer, issue.code());
            writeString(buffer, issue.message());
        }
    }

    private static BlueprintValidation readValidation(RegistryFriendlyByteBuf buffer) {
        int count = readSize(buffer, MAX_VALIDATION_ISSUES);
        List<BlueprintValidation.Issue> issues = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            issues.add(new BlueprintValidation.Issue(
                    readEnum(buffer, BlueprintValidation.Severity.class),
                    readString(buffer),
                    readString(buffer)));
        }
        return new BlueprintValidation(issues);
    }

    private static void writeBlockState(RegistryFriendlyByteBuf buffer, BlockStateSpec state) {
        writeString(buffer, state.blockId().toString());
        writeSize(buffer, state.properties().size(), MAX_PROPERTIES);
        state.properties().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    writeString(buffer, entry.getKey());
                    writeString(buffer, entry.getValue());
                });
    }

    private static BlockStateSpec readBlockState(RegistryFriendlyByteBuf buffer) {
        NamespacedId blockId = NamespacedId.parse(readString(buffer));
        int count = readSize(buffer, MAX_PROPERTIES);
        Map<String, String> properties = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String previous = properties.put(readString(buffer), readString(buffer));
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate block-state property in preview payload");
            }
        }
        return new BlockStateSpec(blockId, properties);
    }

    private static void writePositions(
            RegistryFriendlyByteBuf buffer,
            Set<GridPos> positions,
            int maximum) {
        writeSize(buffer, positions.size(), maximum);
        positions.stream()
                .sorted(java.util.Comparator.comparingInt(GridPos::x)
                        .thenComparingInt(GridPos::y)
                        .thenComparingInt(GridPos::z))
                .forEach(position -> writePosition(buffer, position));
    }

    private static Set<GridPos> readPositions(RegistryFriendlyByteBuf buffer, int maximum) {
        int count = readSize(buffer, maximum);
        Set<GridPos> positions = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            if (!positions.add(readPosition(buffer))) {
                throw new IllegalArgumentException("Duplicate position in preview payload");
            }
        }
        return Set.copyOf(positions);
    }

    private static void writeStrings(
            RegistryFriendlyByteBuf buffer,
            Set<String> strings,
            int maximum) {
        writeSize(buffer, strings.size(), maximum);
        strings.stream().sorted().forEach(value -> writeString(buffer, value));
    }

    private static Set<String> readStrings(RegistryFriendlyByteBuf buffer, int maximum) {
        int count = readSize(buffer, maximum);
        Set<String> strings = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            if (!strings.add(readString(buffer))) {
                throw new IllegalArgumentException("Duplicate string in preview payload");
            }
        }
        return Set.copyOf(strings);
    }

    private static void writePosition(RegistryFriendlyByteBuf buffer, GridPos position) {
        buffer.writeInt(position.x());
        buffer.writeInt(position.y());
        buffer.writeInt(position.z());
    }

    private static GridPos readPosition(RegistryFriendlyByteBuf buffer) {
        return new GridPos(buffer.readInt(), buffer.readInt(), buffer.readInt());
    }

    private static void writeString(RegistryFriendlyByteBuf buffer, String value) {
        buffer.writeUtf(value, MAX_TEXT);
    }

    private static String readString(RegistryFriendlyByteBuf buffer) {
        return buffer.readUtf(MAX_TEXT);
    }

    private static <E extends Enum<E>> void writeEnum(RegistryFriendlyByteBuf buffer, E value) {
        buffer.writeVarInt(value.ordinal());
    }

    private static <E extends Enum<E>> E readEnum(RegistryFriendlyByteBuf buffer, Class<E> type) {
        E[] values = type.getEnumConstants();
        int ordinal = buffer.readVarInt();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " ordinal in preview payload");
        }
        return values[ordinal];
    }

    private static void writeSize(RegistryFriendlyByteBuf buffer, int size, int maximum) {
        if (size < 0 || size > maximum) {
            throw new IllegalArgumentException("Preview collection exceeds its wire bound");
        }
        buffer.writeVarInt(size);
    }

    private static int readSize(RegistryFriendlyByteBuf buffer, int maximum) {
        int size = buffer.readVarInt();
        if (size < 0 || size > maximum) {
            throw new IllegalArgumentException("Preview collection exceeds its wire bound");
        }
        return size;
    }
}
