package dev.ssa.architect.layout;

import dev.ssa.architect.room.RoomGraph;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record FloorLayout(
        Map<String, PlacedRoom> rooms,
        Set<RoomGraph.Edge> connections,
        String entranceRoomId) {
    public FloorLayout {
        Objects.requireNonNull(rooms, "rooms");
        Map<String, PlacedRoom> roomCopy = new LinkedHashMap<>();
        rooms.forEach((id, room) -> {
            if (!id.equals(room.roomId())) {
                throw new IllegalArgumentException("Placed room map key must match its room ID");
            }
            roomCopy.put(id, room);
        });
        rooms = Collections.unmodifiableMap(roomCopy);
        connections = Set.copyOf(Objects.requireNonNull(connections, "connections"));
        if (entranceRoomId == null || !rooms.containsKey(entranceRoomId)) {
            throw new IllegalArgumentException("Floor layout must contain its entrance room");
        }
        for (RoomGraph.Edge connection : connections) {
            if (!rooms.containsKey(connection.firstRoomId()) || !rooms.containsKey(connection.secondRoomId())) {
                throw new IllegalArgumentException("Floor layout connection references a missing room: " + connection);
            }
        }
    }

    public boolean hasOverlaps() {
        Map<Integer, Set<Footprint.Cell>> occupiedByFloor = new HashMap<>();
        for (PlacedRoom room : rooms.values()) {
            Set<Footprint.Cell> occupied = occupiedByFloor.computeIfAbsent(room.floor(), ignored -> new HashSet<>());
            for (Footprint.Cell cell : room.cells()) {
                if (!occupied.add(cell)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean allRoomsReachableFromEntrance() {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        visited.add(entranceRoomId);
        pending.add(entranceRoomId);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            for (RoomGraph.Edge connection : connections) {
                String adjacent = null;
                if (connection.firstRoomId().equals(current)) {
                    adjacent = connection.secondRoomId();
                } else if (connection.secondRoomId().equals(current)) {
                    adjacent = connection.firstRoomId();
                }
                if (adjacent != null && visited.add(adjacent)) {
                    pending.addLast(adjacent);
                }
            }
        }
        return visited.equals(rooms.keySet());
    }

    public boolean realizes(RoomGraph graph) {
        Objects.requireNonNull(graph, "graph");
        Set<String> graphRoomIds = new HashSet<>();
        graph.nodes().forEach(node -> graphRoomIds.add(node.id()));
        if (!rooms.keySet().equals(graphRoomIds) || !connections.containsAll(graph.edges())) {
            return false;
        }
        for (RoomGraph.Node node : graph.nodes()) {
            PlacedRoom room = rooms.get(node.id());
            if (room.floor() != node.floor() || room.cells().size() < node.minimumArea()) {
                return false;
            }
        }
        for (RoomGraph.Edge edge : graph.edges()) {
            if (!physicallyConnected(
                    rooms.get(edge.firstRoomId()),
                    rooms.get(edge.secondRoomId()),
                    edge.transition())) {
                return false;
            }
        }
        return !hasOverlaps() && allRoomsReachableFromEntrance();
    }

    static boolean physicallyConnected(
            PlacedRoom first,
            PlacedRoom second,
            RoomGraph.Transition transition) {
        if (transition == RoomGraph.Transition.DOOR) {
            if (first.floor() != second.floor()) {
                return false;
            }
            for (Footprint.Cell cell : first.cells()) {
                if (second.cells().contains(new Footprint.Cell(cell.x() - 1, cell.z()))
                        || second.cells().contains(new Footprint.Cell(cell.x() + 1, cell.z()))
                        || second.cells().contains(new Footprint.Cell(cell.x(), cell.z() - 1))
                        || second.cells().contains(new Footprint.Cell(cell.x(), cell.z() + 1))) {
                    return true;
                }
            }
            return false;
        }
        if (transition != RoomGraph.Transition.STAIR
                || Math.abs(first.floor() - second.floor()) != 1) {
            return false;
        }
        int overlap = 0;
        Set<Footprint.Cell> smaller = first.cells().size() <= second.cells().size()
                ? first.cells()
                : second.cells();
        Set<Footprint.Cell> larger = smaller == first.cells() ? second.cells() : first.cells();
        for (Footprint.Cell cell : smaller) {
            if (larger.contains(cell) && ++overlap >= 4) {
                return true;
            }
        }
        return false;
    }

    public record PlacedRoom(String roomId, int floor, Set<Footprint.Cell> cells) {
        public PlacedRoom {
            if (roomId == null || roomId.isBlank()) {
                throw new IllegalArgumentException("Placed room ID must not be blank");
            }
            if (floor < 0) {
                throw new IllegalArgumentException("Placed room floor must not be negative");
            }
            cells = Set.copyOf(Objects.requireNonNull(cells, "cells"));
            if (cells.isEmpty()) {
                throw new IllegalArgumentException("Placed room must contain at least one cell");
            }
        }
    }
}
