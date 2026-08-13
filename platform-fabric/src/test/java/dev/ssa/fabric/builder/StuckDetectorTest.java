package dev.ssa.fabric.builder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class StuckDetectorTest {
    @Test
    void reportsStuckOnlyAfterBoundedStationaryMovingObservations() {
        StuckDetector detector = new StuckDetector(3);
        BlockPos position = new BlockPos(4, 70, -2);

        assertFalse(detector.observe(1, position, StuckDetector.NavigationStatus.MOVING).stuck());
        assertFalse(detector.observe(2, position, StuckDetector.NavigationStatus.MOVING).stuck());
        assertTrue(detector.observe(3, position, StuckDetector.NavigationStatus.MOVING).stuck());
    }

    @Test
    void movementResetsTheStationaryCounter() {
        StuckDetector detector = new StuckDetector(2);
        BlockPos position = new BlockPos(4, 70, -2);

        detector.observe(1, position, StuckDetector.NavigationStatus.MOVING);
        detector.observe(2, position, StuckDetector.NavigationStatus.MOVING);
        assertFalse(detector.observe(3, position.offset(1, 0, 0), StuckDetector.NavigationStatus.MOVING).stuck());
        assertFalse(detector.observe(4, position.offset(1, 0, 0), StuckDetector.NavigationStatus.MOVING).stuck());
    }

    @Test
    void terminalNavigationStatesNeverReportStuck() {
        StuckDetector detector = new StuckDetector(1);
        BlockPos position = new BlockPos(4, 70, -2);

        assertFalse(detector.observe(1, position, StuckDetector.NavigationStatus.ARRIVED).stuck());
        assertFalse(detector.observe(2, position, StuckDetector.NavigationStatus.BLOCKED).stuck());
        assertFalse(detector.observe(3, position, StuckDetector.NavigationStatus.SUSPENDED).stuck());
    }
}
