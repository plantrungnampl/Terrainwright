package dev.ssa.architect.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

final class GridPosTest {
    @Test
    void coordinatesHaveRecordValueSemantics() {
        assertEquals(new GridPos(3, 64, -7), new GridPos(3, 64, -7));
        assertNotEquals(new GridPos(3, 64, -7), new GridPos(3, 65, -7));
    }
}
