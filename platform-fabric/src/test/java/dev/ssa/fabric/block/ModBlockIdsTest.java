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

    @Test
    void productionBlocksUseStableRegistryIds() {
        assertEquals(
                "smart_survival_architect:architect_table",
                ModBlockIds.ARCHITECT_TABLE.identifier().toString());
        assertEquals(
                "smart_survival_architect:builder_hut",
                ModBlockIds.BUILDER_HUT.identifier().toString());
    }
}
