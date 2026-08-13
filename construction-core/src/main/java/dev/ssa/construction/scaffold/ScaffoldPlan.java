package dev.ssa.construction.scaffold;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bounded, provenance-ready temporary support placements for one Builder recovery attempt. */
public final class ScaffoldPlan {
    public static final int MAX_PLACEMENTS = 24;
    public static final int MAX_HEIGHT = 12;

    private final List<Placement> placements;
    private final int height;

    public ScaffoldPlan(List<Placement> placements) {
        Objects.requireNonNull(placements, "placements");
        if (placements.size() > MAX_PLACEMENTS) {
            throw new IllegalArgumentException("scaffold plan exceeds " + MAX_PLACEMENTS + " placements");
        }
        Set<GridPos> positions = new HashSet<>();
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Placement placement : placements) {
            Objects.requireNonNull(placement, "placement");
            if (!positions.add(placement.position())) {
                throw new IllegalArgumentException("scaffold plan contains duplicate position: " + placement.position());
            }
            minY = Math.min(minY, placement.position().y());
            maxY = Math.max(maxY, placement.position().y());
        }
        int calculatedHeight = placements.isEmpty() ? 0 : maxY - minY;
        if (calculatedHeight > MAX_HEIGHT) {
            throw new IllegalArgumentException("scaffold plan exceeds height " + MAX_HEIGHT);
        }
        this.placements = List.copyOf(placements);
        this.height = calculatedHeight;
    }

    public List<Placement> placements() {
        return placements;
    }

    /** Vertical extent in blocks between the lowest and highest placement. */
    public int height() {
        return height;
    }

    public record Placement(GridPos position, BlockStateSpec state) {
        public Placement {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(state, "state");
        }
    }
}
