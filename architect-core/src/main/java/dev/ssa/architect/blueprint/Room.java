package dev.ssa.architect.blueprint;

import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import java.util.Objects;
import java.util.Set;

public record Room(
        String id,
        NamespacedId type,
        int floor,
        Set<GridPos> cells,
        Set<String> connectedRoomIds) {
    public Room {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Room ID must not be blank");
        }
        Objects.requireNonNull(type, "type");
        if (floor < 0) {
            throw new IllegalArgumentException("Room floor must not be negative");
        }
        cells = Set.copyOf(Objects.requireNonNull(cells, "cells"));
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("A placed room must contain at least one cell");
        }
        connectedRoomIds = Set.copyOf(Objects.requireNonNull(connectedRoomIds, "connectedRoomIds"));
        for (String connectedRoomId : connectedRoomIds) {
            if (connectedRoomId.isBlank()) {
                throw new IllegalArgumentException("Connected room IDs must not be blank");
            }
        }
        if (connectedRoomIds.contains(id)) {
            throw new IllegalArgumentException("A room cannot connect to itself");
        }
    }
}
