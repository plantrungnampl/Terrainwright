package dev.ssa.architect.layout;

import dev.ssa.architect.model.EntrancePreference;
import dev.ssa.architect.room.RoomGraph;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

public final class FloorLayoutSolver {
    private static final int MAX_CANDIDATES_PER_DECISION = 128;
    private static final int MAX_SEARCH_ATTEMPTS = 250_000;

    public Optional<FloorLayout> solve(RoomGraph graph, Footprint footprint, long seed) {
        return solve(graph, footprint, EntrancePreference.AUTO, seed);
    }

    public Optional<FloorLayout> solve(
            RoomGraph graph,
            Footprint footprint,
            EntrancePreference entrancePreference,
            long seed) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(footprint, "footprint");
        Objects.requireNonNull(entrancePreference, "entrancePreference");
        checkCancelled();
        if (!hasEnoughAreaPerFloor(graph, footprint)) {
            return Optional.empty();
        }

        List<RoomGraph.Node> order = breadthFirstOrder(graph);
        Map<String, List<Candidate>> candidatesByRoom = new HashMap<>();
        for (RoomGraph.Node node : order) {
            List<Candidate> candidates = candidatesFor(
                    node,
                    footprint,
                    node.id().equals(graph.entranceRoomId())
                            ? entrancePreference
                            : EntrancePreference.AUTO);
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            candidatesByRoom.put(node.id(), candidates);
        }

        Map<String, FloorLayout.PlacedRoom> placed = new LinkedHashMap<>();
        SearchBudget budget = new SearchBudget();
        if (!placeNext(graph, order, candidatesByRoom, placed, 0, seed, budget)) {
            return Optional.empty();
        }
        return Optional.of(new FloorLayout(placed, graph.edges(), graph.entranceRoomId()));
    }

    private static boolean hasEnoughAreaPerFloor(RoomGraph graph, Footprint footprint) {
        Map<Integer, Integer> requiredArea = new HashMap<>();
        for (RoomGraph.Node node : graph.nodes()) {
            requiredArea.merge(node.floor(), node.minimumArea(), Integer::sum);
        }
        return requiredArea.values().stream().allMatch(area -> area <= footprint.cells().size());
    }

    private static List<RoomGraph.Node> breadthFirstOrder(RoomGraph graph) {
        Map<String, Integer> nodeIndexes = new HashMap<>();
        for (int index = 0; index < graph.nodes().size(); index++) {
            nodeIndexes.put(graph.nodes().get(index).id(), index);
        }
        List<RoomGraph.Node> order = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        visited.add(graph.entranceRoomId());
        pending.add(graph.entranceRoomId());
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            order.add(graph.node(current));
            graph.adjacentRoomIds(current).stream()
                    .filter(visited::add)
                    .sorted(Comparator.comparingInt(nodeIndexes::get))
                    .forEach(pending::addLast);
        }
        return order;
    }

    private static List<Candidate> candidatesFor(
            RoomGraph.Node node,
            Footprint footprint,
            EntrancePreference entrancePreference) {
        List<Candidate> candidates = new ArrayList<>();
        int minimumDimension = node.minimumArea() >= 4 ? 2 : 1;
        int maximumArea = Math.min(
                footprint.cells().size(),
                node.minimumArea() + Math.max(4, node.minimumArea() / 2));
        for (int width = minimumDimension; width <= footprint.width(); width++) {
            for (int depth = minimumDimension; depth <= footprint.depth(); depth++) {
                int area = width * depth;
                if (area < node.minimumArea() || area > maximumArea) {
                    continue;
                }
                for (int x = 0; x <= footprint.width() - width; x++) {
                    for (int z = 0; z <= footprint.depth() - depth; z++) {
                        Set<Footprint.Cell> cells = rectangleCells(x, z, width, depth);
                        if (!footprint.cells().containsAll(cells)) {
                            continue;
                        }
                        boolean boundary = footprint.touchesBoundary(cells);
                        if (node.exteriorPreference() == RoomGraph.ExteriorPreference.REQUIRED && !boundary) {
                            continue;
                        }
                        int exteriorBayCount = exteriorBayCount(cells, footprint);
                        if (exteriorBayCount < minimumExteriorBays(node)) {
                            continue;
                        }
                        if (entrancePreference != EntrancePreference.AUTO
                                && !hasExteriorBayFacing(cells, footprint, entrancePreference)) {
                            continue;
                        }
                        candidates.add(new Candidate(
                                cells,
                                x,
                                z,
                                width,
                                depth,
                                boundary));
                    }
                }
            }
        }
        return List.copyOf(candidates);
    }

    private static boolean hasExteriorBayFacing(
            Set<Footprint.Cell> cells,
            Footprint footprint,
            EntrancePreference direction) {
        for (Footprint.Cell cell : cells) {
            List<Footprint.Cell> neighbors = List.of(
                    new Footprint.Cell(cell.x(), cell.z() - 1),
                    new Footprint.Cell(cell.x() + 1, cell.z()),
                    new Footprint.Cell(cell.x(), cell.z() + 1),
                    new Footprint.Cell(cell.x() - 1, cell.z()));
            List<Footprint.Cell> exterior = neighbors.stream()
                    .filter(neighbor -> !footprint.cells().contains(neighbor))
                    .toList();
            if (exterior.size() == 1
                    && exterior.getFirst().equals(step(cell, direction))) {
                return true;
            }
        }
        return false;
    }

    private static Footprint.Cell step(
            Footprint.Cell cell,
            EntrancePreference direction) {
        return switch (direction) {
            case NORTH -> new Footprint.Cell(cell.x(), cell.z() - 1);
            case EAST -> new Footprint.Cell(cell.x() + 1, cell.z());
            case SOUTH -> new Footprint.Cell(cell.x(), cell.z() + 1);
            case WEST -> new Footprint.Cell(cell.x() - 1, cell.z());
            case AUTO -> throw new IllegalArgumentException("AUTO has no fixed direction");
        };
    }

    private static int minimumExteriorBays(RoomGraph.Node node) {
        return switch (node.type().path()) {
            case "living" -> 2;
            case "entrance", "kitchen", "bedroom" -> 1;
            default -> 0;
        };
    }

    private static int exteriorBayCount(Set<Footprint.Cell> cells, Footprint footprint) {
        int bays = 0;
        for (Footprint.Cell cell : cells) {
            int exteriorDirections = 0;
            for (Footprint.Cell neighbor : Set.of(
                    new Footprint.Cell(cell.x() - 1, cell.z()),
                    new Footprint.Cell(cell.x() + 1, cell.z()),
                    new Footprint.Cell(cell.x(), cell.z() - 1),
                    new Footprint.Cell(cell.x(), cell.z() + 1))) {
                if (!footprint.cells().contains(neighbor)) {
                    exteriorDirections++;
                }
            }
            if (exteriorDirections == 1) {
                bays++;
            }
        }
        return bays;
    }

    private static Set<Footprint.Cell> rectangleCells(int startX, int startZ, int width, int depth) {
        Set<Footprint.Cell> cells = new HashSet<>();
        for (int x = startX; x < startX + width; x++) {
            for (int z = startZ; z < startZ + depth; z++) {
                cells.add(new Footprint.Cell(x, z));
            }
        }
        return Set.copyOf(cells);
    }

    private static boolean placeNext(
            RoomGraph graph,
            List<RoomGraph.Node> order,
            Map<String, List<Candidate>> candidatesByRoom,
            Map<String, FloorLayout.PlacedRoom> placed,
            int index,
            long seed,
            SearchBudget budget) {
        if (index == order.size()) {
            return true;
        }
        checkCancelled();
        if (budget.attempts >= MAX_SEARCH_ATTEMPTS) {
            return false;
        }

        RoomGraph.Node node = order.get(index);
        List<Candidate> eligible = new ArrayList<>();
        for (Candidate candidate : candidatesByRoom.get(node.id())) {
            if (isEligible(graph, node, candidate, placed)) {
                eligible.add(candidate);
            }
        }
        eligible.sort(candidateComparator(graph, node, placed, seed));

        int limit = Math.min(MAX_CANDIDATES_PER_DECISION, eligible.size());
        for (int candidateIndex = 0; candidateIndex < limit; candidateIndex++) {
            checkCancelled();
            if (++budget.attempts > MAX_SEARCH_ATTEMPTS) {
                return false;
            }
            Candidate candidate = eligible.get(candidateIndex);
            placed.put(node.id(), new FloorLayout.PlacedRoom(node.id(), node.floor(), candidate.cells()));
            if (placeNext(graph, order, candidatesByRoom, placed, index + 1, seed, budget)) {
                return true;
            }
            placed.remove(node.id());
        }
        return false;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Floor layout solving was cancelled");
        }
    }

    private static boolean isEligible(
            RoomGraph graph,
            RoomGraph.Node node,
            Candidate candidate,
            Map<String, FloorLayout.PlacedRoom> placed) {
        boolean hasPlacedNeighbor = node.id().equals(graph.entranceRoomId());
        FloorLayout.PlacedRoom proposed = new FloorLayout.PlacedRoom(
                node.id(), node.floor(), candidate.cells());
        Set<String> adjacentRoomIds = graph.adjacentRoomIds(node.id());
        for (FloorLayout.PlacedRoom existing : placed.values()) {
            if (existing.floor() == node.floor() && intersects(existing.cells(), candidate.cells())) {
                return false;
            }
            if (adjacentRoomIds.contains(existing.roomId())) {
                hasPlacedNeighbor = true;
                RoomGraph.Edge edge = graph.edgeBetween(node.id(), existing.roomId());
                if (!FloorLayout.physicallyConnected(proposed, existing, edge.transition())) {
                    return false;
                }
            }
        }
        return hasPlacedNeighbor;
    }

    private static Comparator<Candidate> candidateComparator(
            RoomGraph graph,
            RoomGraph.Node node,
            Map<String, FloorLayout.PlacedRoom> placed,
            long seed) {
        return (first, second) -> {
            if (node.exteriorPreference() == RoomGraph.ExteriorPreference.PREFERRED) {
                int boundaryComparison = Boolean.compare(second.touchesBoundary(), first.touchesBoundary());
                if (boundaryComparison != 0) {
                    return boundaryComparison;
                }
            }
            int areaComparison = Integer.compare(first.cells().size(), second.cells().size());
            if (areaComparison != 0) {
                return areaComparison;
            }
            int strengthComparison = Integer.compare(
                    connectionStrength(graph, node, second, placed),
                    connectionStrength(graph, node, first, placed));
            if (strengthComparison != 0) {
                return strengthComparison;
            }
            int rankComparison = Long.compareUnsigned(
                    seededRank(seed, node.id(), first),
                    seededRank(seed, node.id(), second));
            if (rankComparison != 0) {
                return rankComparison;
            }
            int zComparison = Integer.compare(first.startZ(), second.startZ());
            return zComparison != 0 ? zComparison : Integer.compare(first.startX(), second.startX());
        };
    }

    private static int connectionStrength(
            RoomGraph graph,
            RoomGraph.Node node,
            Candidate candidate,
            Map<String, FloorLayout.PlacedRoom> placed) {
        int strength = 0;
        Set<String> adjacent = graph.adjacentRoomIds(node.id());
        for (FloorLayout.PlacedRoom room : placed.values()) {
            if (!adjacent.contains(room.roomId())) {
                continue;
            }
            if (room.floor() == node.floor()) {
                for (Footprint.Cell cell : candidate.cells()) {
                    if (room.cells().contains(new Footprint.Cell(cell.x() - 1, cell.z()))) {
                        strength++;
                    }
                    if (room.cells().contains(new Footprint.Cell(cell.x() + 1, cell.z()))) {
                        strength++;
                    }
                    if (room.cells().contains(new Footprint.Cell(cell.x(), cell.z() - 1))) {
                        strength++;
                    }
                    if (room.cells().contains(new Footprint.Cell(cell.x(), cell.z() + 1))) {
                        strength++;
                    }
                }
            } else {
                for (Footprint.Cell cell : candidate.cells()) {
                    if (room.cells().contains(cell)) {
                        strength++;
                    }
                }
            }
        }
        return strength;
    }

    private static boolean intersects(Set<Footprint.Cell> first, Set<Footprint.Cell> second) {
        Set<Footprint.Cell> smaller = first.size() <= second.size() ? first : second;
        Set<Footprint.Cell> larger = smaller == first ? second : first;
        return smaller.stream().anyMatch(larger::contains);
    }

    private static long seededRank(long seed, String roomId, Candidate candidate) {
        long value = seed
                ^ ((long) roomId.hashCode() << 32)
                ^ ((long) candidate.startX() << 48)
                ^ ((long) candidate.startZ() << 32)
                ^ ((long) candidate.width() << 16)
                ^ candidate.depth();
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private record Candidate(
            Set<Footprint.Cell> cells,
            int startX,
            int startZ,
            int width,
            int depth,
            boolean touchesBoundary) {
    }

    private static final class SearchBudget {
        private int attempts;
    }
}
