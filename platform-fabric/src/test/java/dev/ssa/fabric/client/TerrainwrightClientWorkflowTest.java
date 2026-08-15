package dev.ssa.fabric.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class TerrainwrightClientWorkflowTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void sameCoordinatesInDifferentDimensionsAreDifferentArchitectTables() {
        BlockPos position = new BlockPos(12, 70, -4);
        GlobalPos overworldTable = GlobalPos.of(Level.OVERWORLD, position);
        GlobalPos netherTable = GlobalPos.of(Level.NETHER, position);

        assertTrue(TerrainwrightClient.isSameArchitectTable(overworldTable, overworldTable));
        assertFalse(TerrainwrightClient.isSameArchitectTable(overworldTable, netherTable));
    }

    @Test
    void onlySiteSelectionCancellationInvalidatesTheServerSurvey() {
        assertTrue(TerrainwrightClient.SelectionMode.SITE_PENDING.cancelsServerSurvey());
        assertTrue(TerrainwrightClient.SelectionMode.SITE.cancelsServerSurvey());
        assertFalse(TerrainwrightClient.SelectionMode.HUT.cancelsServerSurvey());
        assertFalse(TerrainwrightClient.SelectionMode.CHEST.cancelsServerSurvey());
    }
}
