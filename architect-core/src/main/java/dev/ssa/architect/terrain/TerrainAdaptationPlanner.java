package dev.ssa.architect.terrain;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.TerrainSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class TerrainAdaptationPlanner {
    private static final BlockStateSpec AIR = new BlockStateSpec(NamespacedId.parse("minecraft:air"), Map.of());

    public Optional<TerrainPlan> plan(
            TerrainSnapshot snapshot,
            Set<GridPos> footprint,
            BlockStateSpec fillState,
            TerrainBudget budget) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(footprint, "footprint");
        Objects.requireNonNull(fillState, "fillState");
        Objects.requireNonNull(budget, "budget");
        if (footprint.isEmpty()) {
            throw new IllegalArgumentException("Terrain footprint must not be empty");
        }

        List<GridPos> orderedCells = footprint.stream()
                .sorted(Comparator.comparingInt(GridPos::x).thenComparingInt(GridPos::z))
                .toList();
        List<Integer> heights = new ArrayList<>(orderedCells.size());
        for (GridPos cell : orderedCells) {
            requireWithinSnapshot(snapshot, cell);
            NamespacedId surfaceMaterial = snapshot.surfaceMaterialAt(cell.x(), cell.z());
            if (!TerrainPlan.isReplaceableNatural(surfaceMaterial)) {
                return Optional.empty();
            }
            heights.add(snapshot.surfaceYAt(cell.x(), cell.z()));
        }
        if (snapshot.obstructionFlags().stream().anyMatch(obstruction -> footprint.stream().anyMatch(
                cell -> cell.x() == obstruction.x() && cell.z() == obstruction.z()))) {
            return Optional.empty();
        }

        int minimum = heights.stream().min(Integer::compareTo).orElseThrow();
        int maximum = heights.stream().max(Integer::compareTo).orElseThrow();
        Candidate best = null;
        for (int target = minimum; target <= maximum; target++) {
            Candidate candidate = candidate(snapshot, orderedCells, target, fillState);
            if (!withinNumericLimits(candidate.plan(), budget)) {
                continue;
            }
            if (best == null || candidate.compareTo(best) < 0) {
                best = candidate;
            }
        }
        return best == null ? Optional.empty() : Optional.of(best.plan());
    }

    private static boolean withinNumericLimits(TerrainPlan plan, TerrainBudget budget) {
        return plan.removedCount() <= budget.maxRemovedBlocks()
                && plan.filledCount() <= budget.maxFilledBlocks()
                && plan.maxVerticalCut() <= budget.maxVerticalCut()
                && plan.maxVerticalFill() <= budget.maxVerticalFill();
    }

    private static Candidate candidate(
            TerrainSnapshot snapshot,
            List<GridPos> footprint,
            int targetWorldY,
            BlockStateSpec fillState) {
        List<TerrainPlan.TerrainCellChange> changes = new ArrayList<>();
        int maxCut = 0;
        int maxFill = 0;
        boolean water = false;
        boolean lava = false;
        for (GridPos cell : footprint) {
            int surfaceY = snapshot.surfaceYAt(cell.x(), cell.z());
            int delta = surfaceY - targetWorldY;
            maxCut = Math.max(maxCut, Math.max(delta, 0));
            maxFill = Math.max(maxFill, Math.max(-delta, 0));
            water |= snapshot.isWaterAt(cell.x(), cell.z());
            lava |= snapshot.isLavaAt(cell.x(), cell.z());
            BlockStateSpec surfaceState = new BlockStateSpec(
                    snapshot.surfaceMaterialAt(cell.x(), cell.z()),
                    Map.of());
            for (int y = targetWorldY + 1; y <= surfaceY; y++) {
                changes.add(change(
                        snapshot,
                        cell,
                        y,
                        surfaceState,
                        AIR));
            }
            for (int y = surfaceY + 1; y <= targetWorldY; y++) {
                changes.add(change(
                        snapshot,
                        cell,
                        y,
                        AIR,
                        fillState));
            }
        }
        int removed = 0;
        int filled = 0;
        for (GridPos cell : footprint) {
            int delta = snapshot.surfaceYAt(cell.x(), cell.z()) - targetWorldY;
            removed += Math.max(delta, 0);
            filled += Math.max(-delta, 0);
        }
        TerrainPlan plan = new TerrainPlan(
                strategy(removed, filled),
                removed,
                filled,
                maxCut,
                maxFill,
                water,
                lava,
                TerrainPlan.SalvagePolicy.DISCARD_NO_DROPS,
                changes);
        return new Candidate(plan, changes.size(), Math.max(maxCut, maxFill), targetWorldY);
    }

    private static TerrainPlan.TerrainCellChange change(
            TerrainSnapshot snapshot,
            GridPos cell,
            int worldY,
            BlockStateSpec beforeState,
            BlockStateSpec afterState) {
        return new TerrainPlan.TerrainCellChange(
                new GridPos(cell.x(), worldY - snapshot.origin().y(), cell.z()),
                beforeState,
                afterState,
                TerrainPlan.DropPolicy.SUPPRESS,
                TerrainPlan.XpPolicy.SUPPRESS);
    }

    private static TerrainPlan.Strategy strategy(int removed, int filled) {
        if (removed == 0 && filled == 0) {
            return TerrainPlan.Strategy.FLAT;
        }
        if (filled == 0) {
            return TerrainPlan.Strategy.CUT;
        }
        if (removed == 0) {
            return TerrainPlan.Strategy.FILL;
        }
        return TerrainPlan.Strategy.MIXED;
    }

    private static void requireWithinSnapshot(TerrainSnapshot snapshot, GridPos cell) {
        if (cell.x() < 0 || cell.x() >= snapshot.width()
                || cell.z() < 0 || cell.z() >= snapshot.depth()) {
            throw new IllegalArgumentException("Terrain footprint lies outside the snapshot: " + cell);
        }
    }

    private record Candidate(TerrainPlan plan, int editCount, int maximumVerticalChange, int targetWorldY)
            implements Comparable<Candidate> {
        @Override
        public int compareTo(Candidate other) {
            int byEdits = Integer.compare(editCount, other.editCount);
            if (byEdits != 0) {
                return byEdits;
            }
            int byVerticalChange = Integer.compare(maximumVerticalChange, other.maximumVerticalChange);
            if (byVerticalChange != 0) {
                return byVerticalChange;
            }
            return Integer.compare(targetWorldY, other.targetWorldY);
        }
    }
}
