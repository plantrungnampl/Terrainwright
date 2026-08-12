package dev.ssa.construction.schedule;

import dev.ssa.architect.model.GridPos;
import java.util.Objects;

public record WorkZone(int gridX, int gridZ) {
    public static final int CELL_SIZE = 5;

    public static WorkZone containing(GridPos position) {
        Objects.requireNonNull(position, "position");
        return new WorkZone(
                Math.floorDiv(position.x(), CELL_SIZE),
                Math.floorDiv(position.z(), CELL_SIZE));
    }

    public String id() {
        return "zone:" + gridX + ":" + gridZ;
    }

    public int minimumX() {
        return gridX * CELL_SIZE;
    }

    public int maximumXExclusive() {
        return minimumX() + CELL_SIZE;
    }

    public int minimumZ() {
        return gridZ * CELL_SIZE;
    }

    public int maximumZExclusive() {
        return minimumZ() + CELL_SIZE;
    }

    public boolean contains(GridPos position) {
        Objects.requireNonNull(position, "position");
        return position.x() >= minimumX()
                && position.x() < maximumXExclusive()
                && position.z() >= minimumZ()
                && position.z() < maximumZExclusive();
    }
}
