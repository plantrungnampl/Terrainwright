package dev.ssa.architect.blueprint;

import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.StyleId;
import dev.ssa.architect.terrain.TerrainPlan;
import dev.ssa.architect.validation.BlueprintValidation;
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
        BlueprintValidation validation,
        int formatVersion) {
    public static final int CURRENT_FORMAT_VERSION = 1;

    public Blueprint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(styleId, "styleId");
        Objects.requireNonNull(localBounds, "localBounds");
        Objects.requireNonNull(terrainPlan, "terrainPlan");
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
