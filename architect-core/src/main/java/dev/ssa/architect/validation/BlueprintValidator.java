package dev.ssa.architect.validation;

import dev.ssa.architect.blueprint.BlockRole;
import dev.ssa.architect.blueprint.Blueprint;
import dev.ssa.architect.blueprint.BlueprintBlock;
import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.blueprint.Room;
import dev.ssa.architect.material.BlockCapability;
import dev.ssa.architect.material.BlockCapabilityRegistry;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.style.StylePack;
import dev.ssa.architect.terrain.TerrainBudget;
import dev.ssa.architect.terrain.TerrainPlan;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class BlueprintValidator {
    private static final NamespacedId AIR = NamespacedId.parse("minecraft:air");
    private static final Comparator<GridPos> POSITION_ORDER = Comparator
            .comparingInt(GridPos::y)
            .thenComparingInt(GridPos::x)
            .thenComparingInt(GridPos::z);

    private final StylePack style;
    private final BlockCapabilityRegistry capabilityRegistry;

    public BlueprintValidator(StylePack style, BlockCapabilityRegistry capabilityRegistry) {
        this.style = Objects.requireNonNull(style, "style");
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry");
    }

    public BlueprintValidation validate(Blueprint blueprint) {
        Objects.requireNonNull(blueprint, "blueprint");
        List<BlueprintValidation.Issue> issues = new ArrayList<>();
        if (!blueprint.styleId().equals(style.id())) {
            error(issues, "STYLE_MISMATCH", "Blueprint style does not match the validator style pack");
        }
        validateRooms(blueprint, issues);
        validateEnvelope(blueprint, issues);
        validateBlocks(blueprint, issues);
        validateDependencies(blueprint, issues);
        validateTerrain(blueprint.terrainPlan(), issues);
        issues.sort(Comparator.comparing(BlueprintValidation.Issue::code)
                .thenComparing(BlueprintValidation.Issue::message));
        return new BlueprintValidation(issues);
    }

    private static void validateRooms(
            Blueprint blueprint,
            List<BlueprintValidation.Issue> issues) {
        Map<String, Room> roomsById = new HashMap<>();
        blueprint.rooms().forEach(room -> roomsById.put(room.id(), room));
        Room entrance = blueprint.rooms().stream()
                .filter(room -> room.type().path().equals("entrance"))
                .findFirst()
                .orElse(null);
        if (entrance == null) {
            error(issues, "MISSING_ENTRANCE", "Blueprint must contain an entrance room");
            return;
        }

        for (Room room : blueprint.rooms()) {
            if (room.floor() >= blueprint.floors()) {
                error(issues, "INVALID_ROOM_FLOOR", "Room lies outside the declared floors: " + room.id());
            }
            for (String adjacentId : room.connectedRoomIds()) {
                Room adjacent = roomsById.get(adjacentId);
                if (adjacent == null) {
                    error(issues, "INVALID_ROOM_CONNECTION",
                            "Room connection references a missing room: " + adjacentId);
                } else if (!adjacent.connectedRoomIds().contains(room.id())) {
                    error(issues, "INVALID_ROOM_CONNECTION",
                            "Room connection is not symmetric: " + room.id() + " -> " + adjacentId);
                }
            }
        }

        Set<String> reachable = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        reachable.add(entrance.id());
        pending.add(entrance.id());
        while (!pending.isEmpty()) {
            Room current = roomsById.get(pending.removeFirst());
            for (String adjacentId : current.connectedRoomIds()) {
                if (roomsById.containsKey(adjacentId) && reachable.add(adjacentId)) {
                    pending.addLast(adjacentId);
                }
            }
        }
        blueprint.rooms().stream()
                .filter(room -> !reachable.contains(room.id()))
                .sorted(Comparator.comparing(Room::id))
                .forEach(room -> error(
                        issues,
                        "UNREACHABLE_ROOM",
                        "Room is unreachable from the entrance: " + room.id()));

        validateStairs(blueprint, roomsById, issues);
    }

    private static void validateStairs(
            Blueprint blueprint,
            Map<String, Room> roomsById,
            List<BlueprintValidation.Issue> issues) {
        for (Room room : blueprint.rooms()) {
            for (String adjacentId : room.connectedRoomIds()) {
                if (room.id().compareTo(adjacentId) >= 0 || !roomsById.containsKey(adjacentId)) {
                    continue;
                }
                Room adjacent = roomsById.get(adjacentId);
                int floorDelta = Math.abs(room.floor() - adjacent.floor());
                if (floorDelta > 0) {
                    if (floorDelta != 1 || (!isStair(room) && !isStair(adjacent))) {
                        error(issues, "INVALID_STAIRS",
                                "Cross-floor connection must use a stair room on consecutive floors: "
                                        + room.id() + " <-> " + adjacent.id());
                    } else {
                        Room stairRoom = isStair(room) ? room : adjacent;
                        Room lowerRoom = room.floor() < adjacent.floor() ? room : adjacent;
                        if (!validStairGeometry(blueprint, stairRoom, lowerRoom)) {
                            error(issues, "INVALID_STAIRS",
                                    "Cross-floor connection lacks a continuous traversable stair run: "
                                            + room.id() + " <-> " + adjacent.id());
                        }
                    }
                }
            }
        }

        for (int floor = 1; floor < blueprint.floors(); floor++) {
            int upperFloor = floor;
            boolean hasTransition = blueprint.rooms().stream()
                    .filter(room -> room.floor() == upperFloor && isStair(room))
                    .anyMatch(room -> room.connectedRoomIds().stream()
                            .map(roomsById::get)
                            .filter(Objects::nonNull)
                            .anyMatch(adjacent -> adjacent.floor() == upperFloor - 1));
            if (!hasTransition) {
                error(issues, "INVALID_STAIRS",
                        "Floor " + upperFloor + " lacks a valid stair transition from the floor below");
            }
        }
    }

    private static boolean isStair(Room room) {
        return room.type().path().equals("stairs");
    }

    private static boolean validStairGeometry(
            Blueprint blueprint,
            Room stairRoom,
            Room lowerRoom) {
        Set<Column> lowerColumns = new HashSet<>();
        lowerRoom.cells().forEach(cell -> lowerColumns.add(new Column(cell.x(), cell.z())));
        Set<Column> overlap = new HashSet<>();
        stairRoom.cells().stream()
                .map(cell -> new Column(cell.x(), cell.z()))
                .filter(lowerColumns::contains)
                .forEach(overlap::add);
        if (overlap.size() < 4) {
            return false;
        }

        int lowerY = lowerRoom.cells().stream().mapToInt(GridPos::y).min().orElseThrow();
        int upperY = stairRoom.cells().stream().mapToInt(GridPos::y).min().orElseThrow();
        if (upperY <= lowerY) {
            return false;
        }

        Set<GridPos> occupied = new HashSet<>();
        blueprint.blocks().forEach(block -> occupied.add(block.relativePosition()));
        Map<GridPos, BlueprintBlock> stairRun = new HashMap<>();
        blueprint.blocks().stream()
                .filter(block -> block.phase() == BuildPhase.STAIRS)
                .filter(block -> block.materialRole() == MaterialRole.STAIR)
                .filter(block -> overlap.contains(new Column(
                        block.relativePosition().x(),
                        block.relativePosition().z())))
                .filter(block -> block.relativePosition().y() >= lowerY
                        && block.relativePosition().y() < upperY)
                .filter(block -> hasHeadroom(block.relativePosition(), occupied))
                .forEach(block -> stairRun.put(block.relativePosition(), block));
        return hasContinuousStairRun(stairRun, lowerY, upperY - 1);
    }

    private static boolean hasContinuousStairRun(
            Map<GridPos, BlueprintBlock> stairRun,
            int startY,
            int endY) {
        ArrayDeque<GridPos> pending = new ArrayDeque<>();
        Set<GridPos> visited = new HashSet<>();
        stairRun.keySet().stream()
                .filter(position -> position.y() == startY)
                .sorted(POSITION_ORDER)
                .forEach(position -> {
                    pending.addLast(position);
                    visited.add(position);
                });
        while (!pending.isEmpty()) {
            GridPos current = pending.removeFirst();
            if (current.y() == endY) {
                return true;
            }
            stairRun.keySet().stream()
                    .filter(candidate -> !visited.contains(candidate))
                    .filter(candidate -> candidate.y() == current.y()
                            || candidate.y() == current.y() + 1)
                    .filter(candidate -> Math.abs(candidate.x() - current.x())
                                    + Math.abs(candidate.z() - current.z())
                            == 1)
                    .filter(candidate -> stairTransitionFaces(
                            stairRun.get(current),
                            stairRun.get(candidate)))
                    .sorted(POSITION_ORDER)
                    .forEach(candidate -> {
                        visited.add(candidate);
                        pending.addLast(candidate);
                    });
        }
        return false;
    }

    private static boolean stairTransitionFaces(
            BlueprintBlock current,
            BlueprintBlock next) {
        if (next.relativePosition().y() == current.relativePosition().y()) {
            return true;
        }
        String facing = current.placementState().properties().get("facing");
        return horizontalDirection(current.relativePosition(), next.relativePosition()).equals(facing);
    }

    private static boolean hasHeadroom(GridPos stairPosition, Set<GridPos> occupied) {
        return !occupied.contains(new GridPos(stairPosition.x(), stairPosition.y() + 1, stairPosition.z()))
                && !occupied.contains(new GridPos(stairPosition.x(), stairPosition.y() + 2, stairPosition.z()));
    }

    private static String horizontalDirection(GridPos from, GridPos to) {
        if (to.x() > from.x()) {
            return "east";
        }
        if (to.x() < from.x()) {
            return "west";
        }
        return to.z() > from.z() ? "south" : "north";
    }

    private static String opposite(String direction) {
        return switch (direction) {
            case "north" -> "south";
            case "east" -> "west";
            case "south" -> "north";
            case "west" -> "east";
            default -> throw new IllegalArgumentException("Unknown direction: " + direction);
        };
    }

    private static void validateEnvelope(
            Blueprint blueprint,
            List<BlueprintValidation.Issue> issues) {
        Set<GridPos> foundationPositions = new HashSet<>();
        Map<Column, List<BlueprintBlock>> roofByColumn = new HashMap<>();
        Map<Column, Integer> highestNonRoofByColumn = new HashMap<>();
        Map<GridPos, BlueprintBlock> blocksByPosition = new HashMap<>();
        for (BlueprintBlock block : blueprint.blocks()) {
            blocksByPosition.put(block.relativePosition(), block);
            Column column = new Column(block.relativePosition().x(), block.relativePosition().z());
            if (block.phase() == BuildPhase.FOUNDATION
                    && block.blockRole() == BlockRole.FOUNDATION
                    && (block.materialRole() == MaterialRole.FOUNDATION_STONE
                            || block.materialRole() == MaterialRole.FOUNDATION_FILL)) {
                foundationPositions.add(block.relativePosition());
            }
            if (block.phase() == BuildPhase.ROOF
                    && block.blockRole() == BlockRole.ENVELOPE
                    && (block.materialRole() == MaterialRole.ROOF_PRIMARY
                            || block.materialRole() == MaterialRole.ROOF_ACCENT)) {
                roofByColumn.computeIfAbsent(column, ignored -> new ArrayList<>()).add(block);
            } else {
                highestNonRoofByColumn.merge(column, block.relativePosition().y(), Math::max);
            }
        }
        blueprint.footprint().stream()
                .sorted(POSITION_ORDER)
                .forEach(cell -> {
                    Column column = new Column(cell.x(), cell.z());
                    if (!foundationPositions.contains(cell)) {
                        error(issues, "FOUNDATION_COVERAGE",
                                "Footprint column lacks foundation: " + column);
                    }
                    int highestNonRoof = highestNonRoofByColumn.getOrDefault(column, Integer.MIN_VALUE);
                    boolean covered = roofByColumn.getOrDefault(column, List.of()).stream()
                            .anyMatch(roof -> roof.relativePosition().y() > highestNonRoof);
                    if (!covered) {
                        error(issues, "ROOF_ENVELOPE",
                                "Footprint column lacks roof coverage: " + column);
                    }
                });
        roofByColumn.values().stream()
                .flatMap(List::stream)
                .sorted(Comparator.comparing(BlueprintBlock::relativePosition, POSITION_ORDER))
                .forEach(roof -> {
                    boolean supported = !roof.dependencies().isEmpty()
                            && roof.dependencies().stream()
                                    .map(blocksByPosition::get)
                                    .allMatch(dependency -> validRoofSupport(roof, dependency));
                    if (!supported) {
                        error(issues, "ROOF_SUPPORT",
                                "Roof block lacks resolved support dependencies: "
                                        + roof.relativePosition());
                    }
                });
        Map<Column, BlueprintBlock> roofSurface = new HashMap<>();
        roofByColumn.forEach((column, roofs) -> roofSurface.put(
                column,
                roofs.stream()
                        .max(Comparator.comparingInt(block -> block.relativePosition().y()))
                        .orElseThrow()));
        roofSurface.values().stream()
                .sorted(Comparator.comparing(BlueprintBlock::relativePosition, POSITION_ORDER))
                .filter(roof -> !validRoofOrientation(roof, roofSurface))
                .forEach(roof -> error(
                        issues,
                        "INVALID_ROOF_ORIENTATION",
                        "Roof placement state faces away from its pitch gradient: "
                                + roof.relativePosition()));
    }

    private static boolean validRoofSupport(
            BlueprintBlock roof,
            BlueprintBlock dependency) {
        if (dependency == null) {
            return false;
        }
        GridPos roofPosition = roof.relativePosition();
        GridPos dependencyPosition = dependency.relativePosition();
        if (dependency.blockRole() == BlockRole.STRUCTURAL) {
            return dependency.phase().ordinal() < BuildPhase.ROOF.ordinal()
                    && roofPosition.x() == dependencyPosition.x()
                    && roofPosition.z() == dependencyPosition.z()
                    && roofPosition.y() == dependencyPosition.y() + 1;
        }
        if (dependency.phase() != BuildPhase.ROOF || dependency.blockRole() != BlockRole.ENVELOPE) {
            return false;
        }
        int horizontalDistance = Math.abs(roofPosition.x() - dependencyPosition.x())
                + Math.abs(roofPosition.z() - dependencyPosition.z());
        return horizontalDistance == 1
                && Math.abs(roofPosition.y() - dependencyPosition.y()) <= 1;
    }

    private static boolean validRoofOrientation(
            BlueprintBlock roof,
            Map<Column, BlueprintBlock> roofSurface) {
        String facing = roof.placementState().properties().get("facing");
        if (facing == null) {
            return true;
        }
        GridPos position = roof.relativePosition();
        List<BlueprintBlock> neighbors = List.of(
                        new Column(position.x(), position.z() - 1),
                        new Column(position.x() + 1, position.z()),
                        new Column(position.x(), position.z() + 1),
                        new Column(position.x() - 1, position.z()))
                .stream()
                .map(roofSurface::get)
                .filter(Objects::nonNull)
                .toList();
        Set<String> uphill = new HashSet<>();
        neighbors.stream()
                .filter(neighbor -> neighbor.relativePosition().y() > position.y())
                .map(neighbor -> horizontalDirection(position, neighbor.relativePosition()))
                .forEach(uphill::add);
        if (!uphill.isEmpty()) {
            return uphill.contains(facing);
        }
        Set<String> awayFromDownhill = new HashSet<>();
        neighbors.stream()
                .filter(neighbor -> neighbor.relativePosition().y() < position.y())
                .map(neighbor -> opposite(horizontalDirection(position, neighbor.relativePosition())))
                .forEach(awayFromDownhill::add);
        return awayFromDownhill.isEmpty() || awayFromDownhill.contains(facing);
    }

    private void validateBlocks(
            Blueprint blueprint,
            List<BlueprintValidation.Issue> issues) {
        blueprint.blocks().stream()
                .sorted(Comparator.comparing(BlueprintBlock::relativePosition, POSITION_ORDER))
                .forEach(block -> {
                    if (!capabilityRegistry.supports(block.placementState())) {
                        error(issues, "UNSUPPORTED_BLOCK_STATE",
                                "Registry does not support placement state at " + block.relativePosition());
                        return;
                    }
                    Set<BlockCapability> actual = capabilityRegistry
                            .capabilities(block.placementState().blockId())
                            .orElse(Set.of());
                    Set<BlockCapability> required = style.requiredCapabilities(block.materialRole());
                    if (!actual.containsAll(required)) {
                        error(issues, "MISSING_MATERIAL_CAPABILITY",
                                "Resolved block lacks capabilities for " + block.materialRole()
                                        + " at " + block.relativePosition());
                    }
                });
    }

    private static void validateDependencies(
            Blueprint blueprint,
            List<BlueprintValidation.Issue> issues) {
        Map<GridPos, BlueprintBlock> blocksByPosition = new HashMap<>();
        blueprint.blocks().forEach(block -> blocksByPosition.put(block.relativePosition(), block));
        blueprint.blocks().stream()
                .sorted(Comparator.comparing(BlueprintBlock::relativePosition, POSITION_ORDER))
                .forEach(block -> block.dependencies().stream()
                        .filter(dependency -> !blocksByPosition.containsKey(dependency))
                        .sorted(POSITION_ORDER)
                        .forEach(dependency -> error(
                                issues,
                                "UNRESOLVED_DEPENDENCY",
                                "Block dependency does not resolve: " + dependency)));
        blueprint.blocks().stream()
                .sorted(Comparator.comparing(BlueprintBlock::relativePosition, POSITION_ORDER))
                .forEach(block -> block.dependencies().stream()
                        .map(blocksByPosition::get)
                        .filter(Objects::nonNull)
                        .forEach(dependency -> {
                            if (dependency.phase().ordinal() > block.phase().ordinal()) {
                                error(issues, "DEPENDENCY_PHASE_ORDER",
                                        "Block depends on a later build phase: "
                                                + block.relativePosition() + " -> "
                                                + dependency.relativePosition());
                            }
                            if (block.blockRole() == BlockRole.STRUCTURAL
                                    && dependency.blockRole() == BlockRole.DECORATION) {
                                error(issues, "INVALID_STRUCTURAL_DEPENDENCY",
                                        "Structural block cannot depend on decoration: "
                                                + block.relativePosition());
                            }
                        }));

        Map<GridPos, VisitState> states = new HashMap<>();
        boolean cycle = blocksByPosition.keySet().stream()
                .sorted(POSITION_ORDER)
                .anyMatch(position -> hasCycle(position, blocksByPosition, states));
        if (cycle) {
            error(issues, "DEPENDENCY_CYCLE", "Blueprint block dependencies contain a cycle");
        }
    }

    private static boolean hasCycle(
            GridPos position,
            Map<GridPos, BlueprintBlock> blocks,
            Map<GridPos, VisitState> states) {
        VisitState state = states.get(position);
        if (state == VisitState.VISITING) {
            return true;
        }
        if (state == VisitState.VISITED) {
            return false;
        }
        states.put(position, VisitState.VISITING);
        BlueprintBlock block = blocks.get(position);
        if (block != null) {
            for (GridPos dependency : block.dependencies().stream().sorted(POSITION_ORDER).toList()) {
                if (blocks.containsKey(dependency) && hasCycle(dependency, blocks, states)) {
                    return true;
                }
            }
        }
        states.put(position, VisitState.VISITED);
        return false;
    }

    private void validateTerrain(
            TerrainPlan plan,
            List<BlueprintValidation.Issue> issues) {
        TerrainBudget budget = TerrainBudget.light();
        if (plan.removedCount() > budget.maxRemovedBlocks()
                || plan.filledCount() > budget.maxFilledBlocks()
                || plan.maxVerticalCut() > budget.maxVerticalCut()
                || plan.maxVerticalFill() > budget.maxVerticalFill()) {
            error(issues, "TERRAIN_BUDGET", "Terrain plan exceeds the V1 light-adaptation budget");
        }
        if (plan.modifyWater()) {
            error(issues, "WATER_MODIFICATION", "V1 terrain planning cannot modify water");
        }
        if (plan.modifyLava()) {
            error(issues, "LAVA_MODIFICATION", "V1 terrain planning cannot modify lava");
        }
        if (plan.hasUnsafeRemoval()) {
            error(issues, "UNSAFE_TERRAIN_REMOVAL",
                    "Terrain plan removes a block outside the V1 natural-block allowlist");
        }
        Set<BlockCapability> requiredFillCapabilities = style.requiredCapabilities(MaterialRole.FOUNDATION_FILL);
        plan.changes().stream()
                .filter(change -> change.beforeState().blockId().equals(AIR))
                .forEach(change -> {
                    Set<BlockCapability> actual = capabilityRegistry
                            .capabilities(change.afterState().blockId())
                            .orElse(Set.of());
                    if (!capabilityRegistry.supports(change.afterState())
                            || !actual.containsAll(requiredFillCapabilities)) {
                        error(issues, "UNSUPPORTED_TERRAIN_FILL",
                                "Terrain fill state is unsupported at " + change.pos());
                    }
                });
    }

    private static void error(
            List<BlueprintValidation.Issue> issues,
            String code,
            String message) {
        issues.add(new BlueprintValidation.Issue(
                BlueprintValidation.Severity.ERROR,
                code,
                message));
    }

    private enum VisitState {
        VISITING,
        VISITED
    }

    private record Column(int x, int z) {
    }
}
