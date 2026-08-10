package dev.ssa.fabric.block;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ModBlockIdsTest {
    @Test
    void spikeMarkerUsesTheStableRegistryId() {
        assertEquals(
                "smart_survival_architect:spike_marker",
                ModBlockIds.SPIKE_MARKER.identifier().toString());
    }
}
