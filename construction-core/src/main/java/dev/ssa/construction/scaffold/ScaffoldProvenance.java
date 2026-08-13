package dev.ssa.construction.scaffold;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Durable ownership and operation evidence for temporary scaffold cells. */
public record ScaffoldProvenance(
        String jobId,
        String planId,
        String taskId,
        List<Cell> cells,
        long revision) {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.:/-]{1,160}");

    public ScaffoldProvenance {
        requireIdentifier(jobId, "jobId");
        requireIdentifier(planId, "planId");
        requireIdentifier(taskId, "taskId");
        cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
        if (cells.isEmpty() || cells.size() > ScaffoldPlan.MAX_PLACEMENTS) {
            throw new IllegalArgumentException("scaffold provenance requires 1 to "
                    + ScaffoldPlan.MAX_PLACEMENTS + " cells");
        }
        new ScaffoldPlan(cells.stream()
                .map(cell -> new ScaffoldPlan.Placement(cell.position(), cell.state()))
                .toList());
        if (revision < 0) {
            throw new IllegalArgumentException("scaffold provenance revision must not be negative");
        }
    }

    public static ScaffoldProvenance planned(
            String jobId, String planId, String taskId, ScaffoldPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return new ScaffoldProvenance(
                jobId,
                planId,
                taskId,
                plan.placements().stream()
                        .map(placement -> new Cell(
                                placement.position(),
                                placement.state(),
                                Optional.empty(),
                                Optional.empty()))
                        .toList(),
                0);
    }

    public ScaffoldProvenance recordPlaced(int cellIndex, String operationId) {
        requireIdentifier(operationId, "operationId");
        Cell cell = cell(cellIndex);
        if (cell.placementOperationId().isPresent()) {
            if (cell.placementOperationId().orElseThrow().equals(operationId)) {
                return this;
            }
            throw new IllegalStateException("scaffold cell has conflicting placement evidence");
        }
        return replace(cellIndex, new Cell(
                cell.position(), cell.state(), Optional.of(operationId), Optional.empty()));
    }

    public ScaffoldProvenance recordRemoved(int cellIndex, String operationId) {
        requireIdentifier(operationId, "operationId");
        Cell cell = cell(cellIndex);
        if (cell.placementOperationId().isEmpty()) {
            throw new IllegalStateException("scaffold cell cannot be removed before placement");
        }
        if (cell.removalOperationId().isPresent()) {
            if (cell.removalOperationId().orElseThrow().equals(operationId)) {
                return this;
            }
            throw new IllegalStateException("scaffold cell has conflicting removal evidence");
        }
        return replace(cellIndex, new Cell(
                cell.position(), cell.state(), cell.placementOperationId(), Optional.of(operationId)));
    }

    public boolean isCleaned() {
        return cells.stream().allMatch(Cell::isCleaned);
    }

    private Cell cell(int index) {
        if (index < 0 || index >= cells.size()) {
            throw new IllegalArgumentException("scaffold cell index is out of bounds: " + index);
        }
        return cells.get(index);
    }

    private ScaffoldProvenance replace(int index, Cell replacement) {
        List<Cell> next = new ArrayList<>(cells);
        next.set(index, replacement);
        return new ScaffoldProvenance(jobId, planId, taskId, next, revision + 1);
    }

    private static void requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a valid durable identifier");
        }
    }

    public record Cell(
            GridPos position,
            BlockStateSpec state,
            Optional<String> placementOperationId,
            Optional<String> removalOperationId) {
        public Cell {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(state, "state");
            placementOperationId = Objects.requireNonNull(placementOperationId, "placementOperationId");
            removalOperationId = Objects.requireNonNull(removalOperationId, "removalOperationId");
            placementOperationId.ifPresent(value -> requireIdentifier(value, "placementOperationId"));
            removalOperationId.ifPresent(value -> requireIdentifier(value, "removalOperationId"));
            if (removalOperationId.isPresent() && placementOperationId.isEmpty()) {
                throw new IllegalArgumentException("scaffold removal evidence requires placement evidence");
            }
        }

        public boolean isPlaced() {
            return placementOperationId.isPresent() && removalOperationId.isEmpty();
        }

        public boolean isCleaned() {
            return removalOperationId.isPresent();
        }
    }
}
