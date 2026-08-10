package dev.ssa.fabric;

import dev.ssa.fabric.block.ModBlockIds;
import dev.ssa.fabric.block.ModBlocks;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SmartSurvivalArchitectMod implements ModInitializer {
    public static final String MOD_ID = "smart_survival_architect";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModBlocks.initialize();
        LOGGER.info("SSA_S1_COMMON_READY block={}", ModBlockIds.SPIKE_MARKER.identifier());
    }
}
