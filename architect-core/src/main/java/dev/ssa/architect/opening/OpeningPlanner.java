package dev.ssa.architect.opening;

import dev.ssa.architect.layout.FloorLayout;
import dev.ssa.architect.layout.Footprint;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.room.RoomGraph;
import dev.ssa.architect.style.StylePack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class OpeningPlanner {
    public OpeningPlan plan(
            RoomGraph graph,
            FloorLayout layout,
            Footprint footprint,
            int floorHeight,
            StylePack style,
            long seed) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(footprint, "footprint");
        Objects.requireNonNull(style, "style");
        if (floorHeight < 3) {
            throw new IllegalArgumentException("Floor height must leave room for doors and windows");
        }
        if (!layout.realizes(graph)) {
            throw new IllegalArgumentException("Opening planner requires a layout that realizes the room graph");
        }

        List<Opening> openings = new ArrayList<>();
        Set<GridPos> occupied = new HashSet<>();
        addEntrance(graph, layout, footprint, floorHeight, openings, occupied, seed);
        addInteriorDoors(graph, layout, floorHeight, openings, occupied);
        addWindows(graph, layout, footprint, floorHeight, style, openings, occupied, seed);
        return new OpeningPlan(openings);
    }

    private static void addEntrance(
            RoomGraph graph,
            FloorLayout layout,
            Footprint footprint,
            int floorHeight,
            List<Opening> openings,
            Set<GridPos> occupied,
            long seed) {
        FloorLayout.PlacedRoom room = layout.rooms().get(graph.entranceRoomId());
        List<ExteriorCandidate> candidates = exteriorCandidates(room, footprint);
        ExteriorCandidate selected = seededOrder(candidates, seed, graph.entranceRoomId()).getFirst();
        GridPos position = position(selected.cell(), room.floor(), floorHeight, 1);
        reserveDoor(occupied, position);
        openings.add(new Opening(
                graph.entranceRoomId(),
                Optional.empty(),
                OpeningType.ENTRANCE_DOOR,
                position,
                selected.direction()));
    }

    private static void addInteriorDoors(
            RoomGraph graph,
            FloorLayout layout,
            int floorHeight,
            List<Opening> openings,
            Set<GridPos> occupied) {
        graph.edges().stream()
                .filter(edge -> edge.transition() == RoomGraph.Transition.DOOR)
                .sorted(Comparator.comparing(RoomGraph.Edge::firstRoomId)
                        .thenComparing(RoomGraph.Edge::secondRoomId))
                .forEach(edge -> {
                    FloorLayout.PlacedRoom first = layout.rooms().get(edge.firstRoomId());
                    FloorLayout.PlacedRoom second = layout.rooms().get(edge.secondRoomId());
                    DoorCandidate selected = doorCandidates(first, second).stream()
                            .filter(candidate -> {
                                GridPos position = position(
                                        candidate.firstCell(),
                                        first.floor(),
                                        floorHeight,
                                        1);
                                return doorClear(occupied, position)
                                        && openingBayClear(openings, position);
                            })
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "No unoccupied shared wall for room transition: " + edge));
                    GridPos position = position(selected.firstCell(), first.floor(), floorHeight, 1);
                    reserveDoor(occupied, position);
                    openings.add(new Opening(
                            edge.firstRoomId(),
                            Optional.of(edge.secondRoomId()),
                            OpeningType.INTERIOR_DOOR,
                            position,
                            selected.direction()));
                });
    }

    private static void addWindows(
            RoomGraph graph,
            FloorLayout layout,
            Footprint footprint,
            int floorHeight,
            StylePack style,
            List<Opening> openings,
            Set<GridPos> occupied,
            long seed) {
        graph.nodes().stream()
                .sorted(Comparator.comparing(RoomGraph.Node::id))
                .forEach(node -> {
                    int requested = requestedWindows(node.type().path(), style.openingRules().glazingRatio());
                    if (requested == 0) {
                        return;
                    }
                    FloorLayout.PlacedRoom room = layout.rooms().get(node.id());
                    List<ExteriorCandidate> candidates = seededOrder(
                            exteriorCandidates(room, footprint),
                            seed,
                            node.id());
                    int added = 0;
                    for (ExteriorCandidate candidate : candidates) {
                        GridPos position = position(candidate.cell(), room.floor(), floorHeight, 2);
                        if (!occupied.contains(position)
                                && openingBayClear(openings, position)
                                && occupied.add(position)) {
                            openings.add(new Opening(
                                    node.id(),
                                    Optional.empty(),
                                    OpeningType.WINDOW,
                                    position,
                                    candidate.direction()));
                            if (++added == requested) {
                                break;
                            }
                        }
                    }
                    if (added != requested) {
                        throw new IllegalStateException(
                                "Room does not expose enough exterior wall for windows: " + node.id());
                    }
                });
    }

    private static List<DoorCandidate> doorCandidates(
            FloorLayout.PlacedRoom first,
            FloorLayout.PlacedRoom second) {
        List<DoorCandidate> candidates = new ArrayList<>();
        first.cells().stream()
                .sorted(CELL_ORDER)
                .forEach(cell -> {
                    for (Direction direction : Direction.values()) {
                        if (second.cells().contains(step(cell, direction))) {
                            candidates.add(new DoorCandidate(cell, direction));
                        }
                    }
                });
        return List.copyOf(candidates);
    }

    private static List<ExteriorCandidate> exteriorCandidates(
            FloorLayout.PlacedRoom room,
            Footprint footprint) {
        List<ExteriorCandidate> candidates = new ArrayList<>();
        room.cells().stream()
                .sorted(CELL_ORDER)
                .forEach(cell -> {
                    List<Direction> exteriorDirections = new ArrayList<>();
                    for (Direction direction : Direction.values()) {
                        if (!footprint.cells().contains(step(cell, direction))) {
                            exteriorDirections.add(direction);
                        }
                    }
                    boolean corner = exteriorDirections.size() > 1;
                    exteriorDirections.forEach(direction -> candidates.add(
                            new ExteriorCandidate(cell, direction, corner)));
                });
        candidates.sort(Comparator.comparing(ExteriorCandidate::corner)
                .thenComparing(candidate -> candidate.cell().x())
                .thenComparing(candidate -> candidate.cell().z())
                .thenComparing(candidate -> candidate.direction().ordinal()));
        if (candidates.isEmpty()) {
            throw new IllegalStateException("Room does not touch the exterior: " + room.roomId());
        }
        List<ExteriorCandidate> clearOfCorners = candidates.stream()
                .filter(candidate -> !candidate.corner())
                .toList();
        if (clearOfCorners.isEmpty()) {
            throw new IllegalStateException(
                    "Room exposes only corner opening positions: " + room.roomId());
        }
        return clearOfCorners;
    }

    private static List<ExteriorCandidate> seededOrder(
            List<ExteriorCandidate> candidates,
            long seed,
            String roomId) {
        int offset = Math.floorMod(Long.hashCode(seed) ^ roomId.hashCode(), candidates.size());
        List<ExteriorCandidate> ordered = new ArrayList<>(candidates.size());
        ordered.addAll(candidates.subList(offset, candidates.size()));
        ordered.addAll(candidates.subList(0, offset));
        return List.copyOf(ordered);
    }

    private static int requestedWindows(String roomType, double glazingRatio) {
        IntRange range = switch (roomType) {
            case "bedroom" -> new IntRange(1, 2);
            case "living" -> new IntRange(2, 4);
            case "kitchen" -> new IntRange(1, 2);
            case "storage" -> new IntRange(0, 1);
            case "stairs" -> new IntRange(0, 2);
            default -> new IntRange(0, 0);
        };
        return glazingRatio >= 0.6 ? range.maximum() : range.minimum();
    }

    private static GridPos position(
            Footprint.Cell cell,
            int floor,
            int floorHeight,
            int heightAboveFloor) {
        return new GridPos(cell.x(), floor * floorHeight + heightAboveFloor, cell.z());
    }

    private static boolean doorClear(Set<GridPos> occupied, GridPos base) {
        return !occupied.contains(base)
                && !occupied.contains(new GridPos(base.x(), base.y() + 1, base.z()));
    }

    private static void reserveDoor(Set<GridPos> occupied, GridPos base) {
        occupied.add(base);
        occupied.add(new GridPos(base.x(), base.y() + 1, base.z()));
    }

    private static boolean openingBayClear(
            List<Opening> openings,
            GridPos candidate) {
        return openings.stream()
                .map(Opening::relativePosition)
                .allMatch(existing -> Math.abs(existing.y() - candidate.y()) > 1
                        || Math.abs(existing.x() - candidate.x())
                                        + Math.abs(existing.z() - candidate.z())
                                > 1);
    }

    private static Footprint.Cell step(Footprint.Cell cell, Direction direction) {
        return new Footprint.Cell(cell.x() + direction.deltaX(), cell.z() + direction.deltaZ());
    }

    private static final Comparator<Footprint.Cell> CELL_ORDER =
            Comparator.comparingInt(Footprint.Cell::x).thenComparingInt(Footprint.Cell::z);

    public enum OpeningType {
        ENTRANCE_DOOR,
        INTERIOR_DOOR,
        WINDOW
    }

    public enum Direction {
        NORTH(0, -1),
        EAST(1, 0),
        SOUTH(0, 1),
        WEST(-1, 0);

        private final int deltaX;
        private final int deltaZ;

        Direction(int deltaX, int deltaZ) {
            this.deltaX = deltaX;
            this.deltaZ = deltaZ;
        }

        int deltaX() {
            return deltaX;
        }

        int deltaZ() {
            return deltaZ;
        }
    }

    public record Opening(
            String roomId,
            Optional<String> connectedRoomId,
            OpeningType type,
            GridPos relativePosition,
            Direction facing) {
        public Opening {
            if (roomId == null || roomId.isBlank()) {
                throw new IllegalArgumentException("Opening room ID must not be blank");
            }
            connectedRoomId = Objects.requireNonNull(connectedRoomId, "connectedRoomId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(relativePosition, "relativePosition");
            Objects.requireNonNull(facing, "facing");
        }
    }

    public record OpeningPlan(List<Opening> openings) {
        public OpeningPlan {
            openings = List.copyOf(Objects.requireNonNull(openings, "openings"));
            long distinctPositions = openings.stream().map(Opening::relativePosition).distinct().count();
            if (distinctPositions != openings.size()) {
                throw new IllegalArgumentException("Opening plan positions must be unique");
            }
            Set<GridPos> occupied = new HashSet<>();
            for (Opening opening : openings) {
                if (!occupied.add(opening.relativePosition())) {
                    throw new IllegalArgumentException("Opening volumes must not overlap");
                }
                if (opening.type() != OpeningType.WINDOW
                        && !occupied.add(new GridPos(
                                opening.relativePosition().x(),
                                opening.relativePosition().y() + 1,
                                opening.relativePosition().z()))) {
                    throw new IllegalArgumentException("Two-block door volumes must not overlap openings");
                }
            }
            for (int first = 0; first < openings.size(); first++) {
                for (int second = first + 1; second < openings.size(); second++) {
                    GridPos firstPosition = openings.get(first).relativePosition();
                    GridPos secondPosition = openings.get(second).relativePosition();
                    int horizontalDistance = Math.abs(firstPosition.x() - secondPosition.x())
                            + Math.abs(firstPosition.z() - secondPosition.z());
                    if (Math.abs(firstPosition.y() - secondPosition.y()) <= 1
                            && horizontalDistance <= 1) {
                        throw new IllegalArgumentException(
                                "Opening bays must retain structural frame spacing");
                    }
                }
            }
        }

        public long count(OpeningType type) {
            Objects.requireNonNull(type, "type");
            return openings.stream().filter(opening -> opening.type() == type).count();
        }
    }

    private record ExteriorCandidate(Footprint.Cell cell, Direction direction, boolean corner) {
    }

    private record DoorCandidate(Footprint.Cell firstCell, Direction direction) {
    }

    private record IntRange(int minimum, int maximum) {
    }
}
