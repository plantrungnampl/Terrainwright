package dev.ssa.architect.layout;

import dev.ssa.architect.model.HouseRequirements;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public record Footprint(int width, int depth, Set<Cell> cells) {
    public Footprint {
        if (width <= 0 || depth <= 0) {
            throw new IllegalArgumentException("Footprint dimensions must be positive");
        }
        if (width > HouseRequirements.MAX_TARGET_SIZE || depth > HouseRequirements.MAX_TARGET_SIZE) {
            throw new IllegalArgumentException("Footprint dimensions exceed the V1 house bounds");
        }
        cells = Set.copyOf(Objects.requireNonNull(cells, "cells"));
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("Footprint must contain at least one cell");
        }
        for (Cell cell : cells) {
            if (cell.x() < 0 || cell.z() < 0 || cell.x() >= width || cell.z() >= depth) {
                throw new IllegalArgumentException("Footprint cell is outside its dimensions: " + cell);
            }
        }
        if (!isConnected(cells)) {
            throw new IllegalArgumentException("Footprint cells must form one connected region");
        }
    }

    public static Footprint rectangle(int width, int depth) {
        return new Footprint(width, depth, rectangleCells(0, 0, width, depth));
    }

    public static Footprint lShape(int width, int depth, int removedWidth, int removedDepth) {
        if (removedWidth <= 0 || removedWidth >= width || removedDepth <= 0 || removedDepth >= depth) {
            throw new IllegalArgumentException("L-shape cutout must be smaller than the footprint");
        }
        Set<Cell> cells = new HashSet<>(rectangleCells(0, 0, width, depth));
        cells.removeAll(rectangleCells(width - removedWidth, depth - removedDepth, removedWidth, removedDepth));
        return new Footprint(width, depth, cells);
    }

    public static Footprint tShape(int width, int depth, int stemWidth, int capDepth) {
        if (stemWidth <= 0 || stemWidth > width || capDepth <= 0 || capDepth >= depth) {
            throw new IllegalArgumentException("T-shape stem and cap must fit inside the footprint");
        }
        int stemX = (width - stemWidth) / 2;
        Set<Cell> cells = new HashSet<>(rectangleCells(0, 0, width, capDepth));
        cells.addAll(rectangleCells(stemX, capDepth, stemWidth, depth - capDepth));
        return new Footprint(width, depth, cells);
    }

    public boolean isConnected() {
        return isConnected(cells);
    }

    public boolean touchesBoundary(Set<Cell> candidateCells) {
        Objects.requireNonNull(candidateCells, "candidateCells");
        if (!cells.containsAll(candidateCells)) {
            throw new IllegalArgumentException("Candidate cells must belong to the footprint");
        }
        for (Cell cell : candidateCells) {
            for (Cell neighbor : neighbors(cell)) {
                if (!cells.contains(neighbor)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<Cell> rectangleCells(int startX, int startZ, int width, int depth) {
        Set<Cell> cells = new HashSet<>();
        for (int x = startX; x < startX + width; x++) {
            for (int z = startZ; z < startZ + depth; z++) {
                cells.add(new Cell(x, z));
            }
        }
        return cells;
    }

    private static boolean isConnected(Set<Cell> cells) {
        Set<Cell> visited = new HashSet<>();
        ArrayDeque<Cell> pending = new ArrayDeque<>();
        Cell first = cells.iterator().next();
        visited.add(first);
        pending.add(first);
        while (!pending.isEmpty()) {
            for (Cell neighbor : neighbors(pending.removeFirst())) {
                if (cells.contains(neighbor) && visited.add(neighbor)) {
                    pending.addLast(neighbor);
                }
            }
        }
        return visited.size() == cells.size();
    }

    private static Set<Cell> neighbors(Cell cell) {
        return Set.of(
                new Cell(cell.x() - 1, cell.z()),
                new Cell(cell.x() + 1, cell.z()),
                new Cell(cell.x(), cell.z() - 1),
                new Cell(cell.x(), cell.z() + 1));
    }

    public record Cell(int x, int z) {
    }
}
