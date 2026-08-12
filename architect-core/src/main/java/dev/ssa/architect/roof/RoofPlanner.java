package dev.ssa.architect.roof;

import dev.ssa.architect.blueprint.BlockRole;
import dev.ssa.architect.blueprint.BlueprintBlock;
import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.layout.Footprint;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.style.StylePack;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RoofPlanner {
    private static final Comparator<Footprint.Cell> CELL_ORDER =
            Comparator.comparingInt(Footprint.Cell::x).thenComparingInt(Footprint.Cell::z);

    public List<BlueprintBlock> plan(
            Footprint footprint,
            int roofBaseY,
            StylePack style,
            BlockStateSpec roofState,
            BlockStateSpec supportState) {
        Objects.requireNonNull(style, "style");
        int overhang = style.roofRules().overhangRange().minimum();
        return planBounded(
                footprint,
                roofBaseY,
                style,
                roofState,
                supportState,
                -overhang,
                footprint.width() + overhang,
                -overhang,
                footprint.depth() + overhang);
    }

    public List<BlueprintBlock> planWithinSnapshot(
            Footprint footprint,
            int roofBaseY,
            StylePack style,
            BlockStateSpec roofState,
            BlockStateSpec supportState,
            int snapshotWidth,
            int snapshotDepth) {
        if (snapshotWidth <= 0 || snapshotDepth <= 0) {
            throw new IllegalArgumentException("Snapshot dimensions must be positive");
        }
        return planBounded(
                footprint,
                roofBaseY,
                style,
                roofState,
                supportState,
                0,
                snapshotWidth,
                0,
                snapshotDepth);
    }

    private List<BlueprintBlock> planBounded(
            Footprint footprint,
            int roofBaseY,
            StylePack style,
            BlockStateSpec roofState,
            BlockStateSpec supportState,
            int minimumX,
            int maximumXExclusive,
            int minimumZ,
            int maximumZExclusive) {
        Objects.requireNonNull(footprint, "footprint");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(roofState, "roofState");
        Objects.requireNonNull(supportState, "supportState");

        StylePack.RoofRules rules = style.roofRules();
        int overhang = rules.overhangRange().minimum();
        Set<Footprint.Cell> roofCells = expandedCells(
                footprint,
                overhang,
                minimumX,
                maximumXExclusive,
                minimumZ,
                maximumZExclusive);
        int minX = roofCells.stream().mapToInt(Footprint.Cell::x).min().orElseThrow();
        int maxX = roofCells.stream().mapToInt(Footprint.Cell::x).max().orElseThrow();
        int minZ = roofCells.stream().mapToInt(Footprint.Cell::z).min().orElseThrow();
        int maxZ = roofCells.stream().mapToInt(Footprint.Cell::z).max().orElseThrow();
        int rise = rules.pitchRiseRange().maximum();

        Map<Footprint.Cell, GridPos> roofPositions = new HashMap<>();
        for (Footprint.Cell cell : roofCells) {
            int roofY = roofBaseY + height(
                    rules.primaryFamily(), cell, minX, maxX, minZ, maxZ, rise);
            roofPositions.put(cell, new GridPos(cell.x(), roofY, cell.z()));
        }

        Set<Footprint.Cell> anchors = new HashSet<>();
        footprint.cells().stream()
                .filter(cell -> footprint.touchesBoundary(Set.of(cell)))
                .forEach(anchors::add);
        Map<Footprint.Cell, Integer> distanceToAnchor = distancesFrom(anchors, roofCells);

        List<BlueprintBlock> blocks = new ArrayList<>();
        addAnchorSupports(
                anchors,
                roofPositions,
                roofBaseY,
                supportState,
                blocks);
        roofCells.stream().sorted(CELL_ORDER).forEach(cell -> {
            GridPos roofPosition = roofPositions.get(cell);
            GridPos dependency;
            BlockStateSpec orientedState;
            if (anchors.contains(cell)) {
                dependency = new GridPos(cell.x(), roofPosition.y() - 1, cell.z());
            } else {
                Footprint.Cell supportingCell = supportingCell(
                        cell,
                        footprint,
                        roofCells,
                        roofPositions,
                        distanceToAnchor);
                dependency = roofPositions.get(supportingCell);
            }
            orientedState = withFacing(roofState, uphillDirection(cell, roofCells, roofPositions));
            blocks.add(new BlueprintBlock(
                    roofPosition,
                    BlockRole.ENVELOPE,
                    MaterialRole.ROOF_PRIMARY,
                    orientedState,
                    BuildPhase.ROOF,
                    Set.of(dependency)));
        });
        return List.copyOf(blocks);
    }

    private static void addAnchorSupports(
            Set<Footprint.Cell> anchors,
            Map<Footprint.Cell, GridPos> roofPositions,
            int roofBaseY,
            BlockStateSpec supportState,
            List<BlueprintBlock> blocks) {
        anchors.stream().sorted(CELL_ORDER).forEach(cell -> {
            GridPos roof = roofPositions.get(cell);
            for (int y = roofBaseY - 1; y < roof.y(); y++) {
                Set<GridPos> dependencies = y == roofBaseY - 1
                        ? Set.of()
                        : Set.of(new GridPos(cell.x(), y - 1, cell.z()));
                blocks.add(new BlueprintBlock(
                        new GridPos(cell.x(), y, cell.z()),
                        BlockRole.STRUCTURAL,
                        MaterialRole.STRUCTURAL_PRIMARY,
                        supportState,
                        BuildPhase.WALL_FRAME,
                        dependencies));
            }
        });
    }

    private static Map<Footprint.Cell, Integer> distancesFrom(
            Set<Footprint.Cell> anchors,
            Set<Footprint.Cell> roofCells) {
        Map<Footprint.Cell, Integer> distances = new HashMap<>();
        ArrayDeque<Footprint.Cell> pending = new ArrayDeque<>();
        anchors.stream().sorted(CELL_ORDER).forEach(anchor -> {
            distances.put(anchor, 0);
            pending.addLast(anchor);
        });
        while (!pending.isEmpty()) {
            Footprint.Cell current = pending.removeFirst();
            int nextDistance = distances.get(current) + 1;
            neighbors(current).stream().sorted(CELL_ORDER).forEach(neighbor -> {
                if (roofCells.contains(neighbor) && !distances.containsKey(neighbor)) {
                    distances.put(neighbor, nextDistance);
                    pending.addLast(neighbor);
                }
            });
        }
        if (distances.size() != roofCells.size()) {
            throw new IllegalStateException("Roof envelope is not connected to its structural anchors");
        }
        return Map.copyOf(distances);
    }

    private static Footprint.Cell supportingCell(
            Footprint.Cell cell,
            Footprint footprint,
            Set<Footprint.Cell> roofCells,
            Map<Footprint.Cell, GridPos> roofPositions,
            Map<Footprint.Cell, Integer> distanceToAnchor) {
        List<Footprint.Cell> candidates = neighbors(cell).stream()
                .filter(roofCells::contains)
                .toList();
        if (!footprint.cells().contains(cell)) {
            return candidates.stream()
                    .filter(neighbor -> distanceToAnchor.get(neighbor) < distanceToAnchor.get(cell))
                    .min(CELL_ORDER)
                    .orElseThrow(() -> new IllegalStateException(
                            "Roof overhang has no inward support path: " + cell));
        }

        int currentY = roofPositions.get(cell).y();
        List<Footprint.Cell> downhill = candidates.stream()
                .filter(neighbor -> roofPositions.get(neighbor).y() < currentY)
                .toList();
        if (!downhill.isEmpty()) {
            return downhill.stream()
                    .sorted(Comparator
                            .comparingInt((Footprint.Cell neighbor) -> roofPositions.get(neighbor).y())
                            .reversed()
                            .thenComparing(CELL_ORDER))
                    .findFirst()
                    .orElseThrow();
        }
        return candidates.stream()
                .filter(neighbor -> distanceToAnchor.get(neighbor) < distanceToAnchor.get(cell))
                .min(CELL_ORDER)
                .orElseThrow(() -> new IllegalStateException(
                        "Roof plateau has no path to a structural anchor: " + cell));
    }

    private static String uphillDirection(
            Footprint.Cell cell,
            Set<Footprint.Cell> roofCells,
            Map<Footprint.Cell, GridPos> roofPositions) {
        int currentY = roofPositions.get(cell).y();
        List<Footprint.Cell> adjacent = neighbors(cell).stream()
                .filter(roofCells::contains)
                .toList();
        List<Footprint.Cell> uphill = adjacent.stream()
                .filter(neighbor -> roofPositions.get(neighbor).y() > currentY)
                .toList();
        if (!uphill.isEmpty()) {
            Footprint.Cell selected = uphill.stream()
                    .sorted(Comparator
                            .comparingInt((Footprint.Cell neighbor) -> roofPositions.get(neighbor).y())
                            .thenComparing(CELL_ORDER))
                    .findFirst()
                    .orElseThrow();
            return direction(cell, selected);
        }
        List<Footprint.Cell> downhill = adjacent.stream()
                .filter(neighbor -> roofPositions.get(neighbor).y() < currentY)
                .toList();
        if (!downhill.isEmpty()) {
            Footprint.Cell selected = downhill.stream()
                    .sorted(Comparator
                            .comparingInt((Footprint.Cell neighbor) -> roofPositions.get(neighbor).y())
                            .reversed()
                            .thenComparing(CELL_ORDER))
                    .findFirst()
                    .orElseThrow();
            return opposite(direction(cell, selected));
        }
        return null;
    }

    private static Set<Footprint.Cell> expandedCells(
            Footprint footprint,
            int overhang,
            int minimumX,
            int maximumXExclusive,
            int minimumZ,
            int maximumZExclusive) {
        Set<Footprint.Cell> expanded = new HashSet<>();
        int startX = Math.max(-overhang, minimumX);
        int endX = Math.min(footprint.width() + overhang, maximumXExclusive);
        int startZ = Math.max(-overhang, minimumZ);
        int endZ = Math.min(footprint.depth() + overhang, maximumZExclusive);
        for (int x = startX; x < endX; x++) {
            for (int z = startZ; z < endZ; z++) {
                Footprint.Cell candidate = new Footprint.Cell(x, z);
                if (distanceToFootprint(candidate, footprint.cells()) <= overhang) {
                    expanded.add(candidate);
                }
            }
        }
        return Set.copyOf(expanded);
    }

    private static int distanceToFootprint(Footprint.Cell candidate, Set<Footprint.Cell> footprint) {
        int minimum = Integer.MAX_VALUE;
        for (Footprint.Cell cell : footprint) {
            int distance = Math.max(
                    Math.abs(candidate.x() - cell.x()),
                    Math.abs(candidate.z() - cell.z()));
            minimum = Math.min(minimum, distance);
        }
        return minimum;
    }

    private static int height(
            StylePack.RoofFamily family,
            Footprint.Cell cell,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int rise) {
        return switch (family) {
            case FLAT -> 0;
            case SHED -> scaled(cell.x() - minX, maxX - minX, rise);
            case GABLE -> ridgeHeight(cell.x(), minX, maxX, rise);
            case CROSS_GABLE -> Math.max(
                    ridgeHeight(cell.x(), minX, maxX, rise),
                    ridgeHeight(cell.z(), minZ, maxZ, rise));
            case HIP, WIDE_OVERHANG_HIP -> Math.min(
                    ridgeHeight(cell.x(), minX, maxX, rise),
                    ridgeHeight(cell.z(), minZ, maxZ, rise));
        };
    }

    private static int ridgeHeight(int coordinate, int minimum, int maximum, int rise) {
        int distanceToEdge = Math.min(coordinate - minimum, maximum - coordinate);
        int halfSpan = Math.max((maximum - minimum) / 2, 1);
        return scaled(distanceToEdge, halfSpan, rise);
    }

    private static int scaled(int value, int span, int rise) {
        if (span <= 0 || rise <= 0) {
            return 0;
        }
        return (int) Math.round((double) value * rise / span);
    }

    private static Set<Footprint.Cell> neighbors(Footprint.Cell cell) {
        return Set.of(
                new Footprint.Cell(cell.x(), cell.z() - 1),
                new Footprint.Cell(cell.x() + 1, cell.z()),
                new Footprint.Cell(cell.x(), cell.z() + 1),
                new Footprint.Cell(cell.x() - 1, cell.z()));
    }

    private static String direction(Footprint.Cell from, Footprint.Cell to) {
        if (to.x() > from.x()) {
            return "east";
        }
        if (to.x() < from.x()) {
            return "west";
        }
        return to.z() > from.z() ? "south" : "north";
    }

    private static BlockStateSpec withFacing(BlockStateSpec state, String facing) {
        if (facing == null || !state.properties().containsKey("facing")) {
            return state;
        }
        Map<String, String> properties = new HashMap<>(state.properties());
        properties.put("facing", facing);
        return new BlockStateSpec(state.blockId(), properties);
    }

    private static String opposite(String direction) {
        return switch (direction) {
            case "north" -> "south";
            case "east" -> "west";
            case "south" -> "north";
            case "west" -> "east";
            default -> throw new IllegalArgumentException("Unknown horizontal direction: " + direction);
        };
    }
}
