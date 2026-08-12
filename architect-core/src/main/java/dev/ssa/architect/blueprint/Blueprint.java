package dev.ssa.architect.blueprint;

import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.StyleId;
import dev.ssa.architect.scoring.ScoreBreakdown;
import dev.ssa.architect.terrain.TerrainPlan;
import dev.ssa.architect.validation.BlueprintValidation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record Blueprint(
        UUID id,
        long seed,
        StyleId styleId,
        LocalBounds localBounds,
        Set<GridPos> footprint,
        int floors,
        List<Room> rooms,
        List<BlueprintBlock> blocks,
        List<BuildPhase> buildPhases,
        TerrainPlan terrainPlan,
        ScoreBreakdown scoreBreakdown,
        BlueprintValidation validation,
        int formatVersion) {
    public static final int CURRENT_FORMAT_VERSION = 1;

    public Blueprint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(styleId, "styleId");
        Objects.requireNonNull(localBounds, "localBounds");
        Objects.requireNonNull(terrainPlan, "terrainPlan");
        Objects.requireNonNull(scoreBreakdown, "scoreBreakdown");
        Objects.requireNonNull(validation, "validation");
        if (floors < HouseRequirements.MIN_FLOORS || floors > HouseRequirements.MAX_FLOORS) {
            throw new IllegalArgumentException("Blueprint floors must be within the V1 house bounds");
        }
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported Blueprint format version: " + formatVersion);
        }

        footprint = Set.copyOf(Objects.requireNonNull(footprint, "footprint"));
        rooms = List.copyOf(Objects.requireNonNull(rooms, "rooms"));
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        buildPhases = List.copyOf(Objects.requireNonNull(buildPhases, "buildPhases"));
        if (!buildPhases.equals(BuildPhase.canonicalOrder())) {
            throw new IllegalArgumentException("Blueprint phases must follow the complete canonical R2 order");
        }

        requirePositionsWithinBounds(footprint, localBounds, "Footprint");
        Set<String> roomIds = new HashSet<>();
        for (Room room : rooms) {
            if (!roomIds.add(room.id())) {
                throw new IllegalArgumentException("Duplicate room ID: " + room.id());
            }
            requirePositionsWithinBounds(room.cells(), localBounds, "Room " + room.id());
        }

        Set<GridPos> blockPositions = new HashSet<>();
        for (BlueprintBlock block : blocks) {
            if (!localBounds.contains(block.relativePosition())) {
                throw new IllegalArgumentException(
                        "Blueprint block is outside local bounds: " + block.relativePosition());
            }
            if (!blockPositions.add(block.relativePosition())) {
                throw new IllegalArgumentException(
                        "Duplicate Blueprint block position: " + block.relativePosition());
            }
            requirePositionsWithinBounds(block.dependencies(), localBounds, "Block dependencies");
        }
    }

    public String hash() {
        StringBuilder canonical = new StringBuilder();
        token(canonical, Integer.toString(formatVersion));
        token(canonical, id.toString());
        token(canonical, Long.toString(seed));
        token(canonical, styleId.toString());
        position(canonical, localBounds.minimum());
        position(canonical, localBounds.maximum());
        token(canonical, Integer.toString(floors));
        token(canonical, Integer.toString(buildPhases.size()));
        buildPhases.forEach(phase -> token(canonical, phase.name()));
        token(canonical, Integer.toString(footprint.size()));
        footprint.stream().sorted(POSITION_ORDER).forEach(cell -> position(canonical, cell));
        token(canonical, Integer.toString(rooms.size()));
        rooms.stream().sorted(Comparator.comparing(Room::id)).forEach(room -> {
            token(canonical, room.id());
            token(canonical, room.type().toString());
            token(canonical, Integer.toString(room.floor()));
            token(canonical, Integer.toString(room.cells().size()));
            room.cells().stream().sorted(POSITION_ORDER).forEach(cell -> position(canonical, cell));
            token(canonical, Integer.toString(room.connectedRoomIds().size()));
            room.connectedRoomIds().stream().sorted().forEach(id -> token(canonical, id));
        });
        token(canonical, Integer.toString(blocks.size()));
        blocks.stream()
                .sorted(Comparator.comparing(BlueprintBlock::relativePosition, POSITION_ORDER))
                .forEach(block -> {
                    position(canonical, block.relativePosition());
                    token(canonical, block.blockRole().name());
                    token(canonical, block.materialRole().name());
                    blockState(canonical, block.placementState());
                    token(canonical, block.phase().name());
                    token(canonical, Integer.toString(block.dependencies().size()));
                    block.dependencies().stream().sorted(POSITION_ORDER)
                            .forEach(dependency -> position(canonical, dependency));
                });
        token(canonical, terrainPlan.strategy().name());
        token(canonical, Integer.toString(terrainPlan.removedBlockCount()));
        token(canonical, Integer.toString(terrainPlan.filledBlockCount()));
        token(canonical, Integer.toString(terrainPlan.maxVerticalCut()));
        token(canonical, Integer.toString(terrainPlan.maxVerticalFill()));
        token(canonical, Boolean.toString(terrainPlan.modifyWater()));
        token(canonical, Boolean.toString(terrainPlan.modifyLava()));
        token(canonical, terrainPlan.salvagePolicy().name());
        token(canonical, Integer.toString(terrainPlan.changes().size()));
        terrainPlan.changes().stream()
                .sorted(Comparator.comparing(TerrainPlan.TerrainCellChange::pos, POSITION_ORDER))
                .forEach(change -> {
                    position(canonical, change.pos());
                    blockState(canonical, change.beforeState());
                    blockState(canonical, change.afterState());
                    token(canonical, change.dropPolicy().name());
                    token(canonical, change.xpPolicy().name());
                });
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final Comparator<GridPos> POSITION_ORDER = Comparator.comparingInt(GridPos::x)
            .thenComparingInt(GridPos::y)
            .thenComparingInt(GridPos::z);

    private static void blockState(
            StringBuilder canonical,
            dev.ssa.architect.model.BlockStateSpec state) {
        token(canonical, state.blockId().toString());
        token(canonical, Integer.toString(state.properties().size()));
        state.properties().forEach((name, value) -> {
            token(canonical, name);
            token(canonical, value);
        });
    }

    private static void position(StringBuilder canonical, GridPos position) {
        token(canonical, Integer.toString(position.x()));
        token(canonical, Integer.toString(position.y()));
        token(canonical, Integer.toString(position.z()));
    }

    private static void token(StringBuilder canonical, String value) {
        canonical.append(value.length()).append(':').append(value).append(';');
    }

    private static void requirePositionsWithinBounds(
            Set<GridPos> positions,
            LocalBounds bounds,
            String label) {
        for (GridPos position : positions) {
            if (!bounds.contains(position)) {
                throw new IllegalArgumentException(label + " contains an out-of-bounds position: " + position);
            }
        }
    }

    public record LocalBounds(GridPos minimum, GridPos maximum) {
        public LocalBounds {
            Objects.requireNonNull(minimum, "minimum");
            Objects.requireNonNull(maximum, "maximum");
            if (minimum.x() > maximum.x()
                    || minimum.y() > maximum.y()
                    || minimum.z() > maximum.z()) {
                throw new IllegalArgumentException("Local bounds minimum must not exceed maximum");
            }
        }

        public boolean contains(GridPos position) {
            Objects.requireNonNull(position, "position");
            return position.x() >= minimum.x() && position.x() <= maximum.x()
                    && position.y() >= minimum.y() && position.y() <= maximum.y()
                    && position.z() >= minimum.z() && position.z() <= maximum.z();
        }
    }
}
