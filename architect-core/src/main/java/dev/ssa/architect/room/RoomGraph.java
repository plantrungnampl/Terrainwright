package dev.ssa.architect.room;

import dev.ssa.architect.model.NamespacedId;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record RoomGraph(List<Node> nodes, Set<Edge> edges, String entranceRoomId) {
    public RoomGraph {
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = Set.copyOf(Objects.requireNonNull(edges, "edges"));
        if (entranceRoomId == null || entranceRoomId.isBlank()) {
            throw new IllegalArgumentException("Entrance room ID must not be blank");
        }

        Map<String, Node> nodesById = new HashMap<>();
        for (Node node : nodes) {
            if (nodesById.put(node.id(), node) != null) {
                throw new IllegalArgumentException("Duplicate room node ID: " + node.id());
            }
        }
        if (!nodesById.containsKey(entranceRoomId)) {
            throw new IllegalArgumentException("Entrance room does not exist: " + entranceRoomId);
        }
        Set<String> endpointPairs = new HashSet<>();
        for (Edge edge : edges) {
            if (!nodesById.containsKey(edge.firstRoomId()) || !nodesById.containsKey(edge.secondRoomId())) {
                throw new IllegalArgumentException("Room edge references a missing node: " + edge);
            }
            if (!endpointPairs.add(edge.firstRoomId() + "\u0000" + edge.secondRoomId())) {
                throw new IllegalArgumentException("Room nodes cannot have multiple transition edges: " + edge);
            }
            validateTransition(nodesById.get(edge.firstRoomId()), nodesById.get(edge.secondRoomId()), edge);
        }
        if (!isConnected(nodesById.keySet(), edges, entranceRoomId)) {
            throw new IllegalArgumentException("Room graph must be connected from the entrance");
        }
    }

    public Node node(String id) {
        return nodes.stream()
                .filter(node -> node.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown room node: " + id));
    }

    public boolean isConnected() {
        Set<String> nodeIds = new HashSet<>();
        nodes.forEach(node -> nodeIds.add(node.id()));
        return isConnected(nodeIds, edges, entranceRoomId);
    }

    public Set<String> adjacentRoomIds(String roomId) {
        if (nodes.stream().noneMatch(node -> node.id().equals(roomId))) {
            throw new IllegalArgumentException("Unknown room node: " + roomId);
        }
        Set<String> adjacent = new HashSet<>();
        for (Edge edge : edges) {
            if (edge.firstRoomId().equals(roomId)) {
                adjacent.add(edge.secondRoomId());
            } else if (edge.secondRoomId().equals(roomId)) {
                adjacent.add(edge.firstRoomId());
            }
        }
        return Set.copyOf(adjacent);
    }

    public Edge edgeBetween(String firstRoomId, String secondRoomId) {
        return edges.stream()
                .filter(edge -> edge.connects(firstRoomId, secondRoomId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Rooms do not share an edge: " + firstRoomId + ", " + secondRoomId));
    }

    private static void validateTransition(Node first, Node second, Edge edge) {
        int floorDelta = Math.abs(first.floor() - second.floor());
        if (edge.transition() == Transition.DOOR && floorDelta != 0) {
            throw new IllegalArgumentException("Door transitions must remain on one floor: " + edge);
        }
        if (edge.transition() == Transition.STAIR) {
            if (floorDelta != 1) {
                throw new IllegalArgumentException("Stair transitions must connect consecutive floors: " + edge);
            }
            if (!isStair(first) && !isStair(second)) {
                throw new IllegalArgumentException("A stair transition must reference a stair room: " + edge);
            }
        }
    }

    private static boolean isStair(Node node) {
        return node.type().path().equals("stairs");
    }

    private static boolean isConnected(Set<String> nodeIds, Set<Edge> edges, String entranceRoomId) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        visited.add(entranceRoomId);
        pending.add(entranceRoomId);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            for (Edge edge : edges) {
                String adjacent = null;
                if (edge.firstRoomId().equals(current)) {
                    adjacent = edge.secondRoomId();
                } else if (edge.secondRoomId().equals(current)) {
                    adjacent = edge.firstRoomId();
                }
                if (adjacent != null && visited.add(adjacent)) {
                    pending.addLast(adjacent);
                }
            }
        }
        return visited.equals(nodeIds);
    }

    public enum ExteriorPreference {
        NONE,
        PREFERRED,
        REQUIRED
    }

    public enum Transition {
        DOOR,
        STAIR
    }

    public record Node(
            String id,
            NamespacedId type,
            int floor,
            int minimumArea,
            ExteriorPreference exteriorPreference) {
        public Node {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Room node ID must not be blank");
            }
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(exteriorPreference, "exteriorPreference");
            if (floor < 0) {
                throw new IllegalArgumentException("Room node floor must not be negative");
            }
            if (minimumArea <= 0) {
                throw new IllegalArgumentException("Room node minimum area must be positive");
            }
        }
    }

    public record Edge(String firstRoomId, String secondRoomId, Transition transition) {
        public Edge(String firstRoomId, String secondRoomId) {
            this(firstRoomId, secondRoomId, Transition.DOOR);
        }

        public Edge {
            if (firstRoomId == null || firstRoomId.isBlank()
                    || secondRoomId == null || secondRoomId.isBlank()) {
                throw new IllegalArgumentException("Room edge IDs must not be blank");
            }
            if (firstRoomId.equals(secondRoomId)) {
                throw new IllegalArgumentException("A room edge cannot reference one room twice");
            }
            Objects.requireNonNull(transition, "transition");
            if (firstRoomId.compareTo(secondRoomId) > 0) {
                String swap = firstRoomId;
                firstRoomId = secondRoomId;
                secondRoomId = swap;
            }
        }

        public boolean connects(String first, String second) {
            return (firstRoomId.equals(first) && secondRoomId.equals(second))
                    || (firstRoomId.equals(second) && secondRoomId.equals(first));
        }
    }
}
