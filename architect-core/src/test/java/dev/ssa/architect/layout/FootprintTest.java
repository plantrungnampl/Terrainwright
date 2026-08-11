package dev.ssa.architect.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class FootprintTest {
    @Test
    void createsConnectedRectangleLAndTCellMasks() {
        Footprint rectangle = Footprint.rectangle(6, 5);
        Footprint lShape = Footprint.lShape(6, 5, 2, 2);
        Footprint tShape = Footprint.tShape(7, 5, 3, 2);

        assertEquals(30, rectangle.cells().size());
        assertEquals(26, lShape.cells().size());
        assertEquals(23, tShape.cells().size());
        assertTrue(rectangle.isConnected());
        assertTrue(lShape.isConnected());
        assertTrue(tShape.isConnected());
        assertTrue(tShape.touchesBoundary(Set.of(new Footprint.Cell(0, 0))));
    }

    @Test
    void footprintIsDetachedAndRejectsOutOfBoundsOrDisconnectedMasks() {
        Set<Footprint.Cell> cells = new HashSet<>(Set.of(
                new Footprint.Cell(0, 0),
                new Footprint.Cell(1, 0)));
        Footprint footprint = new Footprint(2, 1, cells);
        cells.clear();

        assertEquals(2, footprint.cells().size());
        assertThrows(UnsupportedOperationException.class, () -> footprint.cells().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new Footprint(2, 2, Set.of(new Footprint.Cell(2, 0))));
        assertThrows(IllegalArgumentException.class, () -> Footprint.rectangle(32, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Footprint(3, 3, Set.of(
                        new Footprint.Cell(0, 0),
                        new Footprint.Cell(2, 2))));
    }
}
