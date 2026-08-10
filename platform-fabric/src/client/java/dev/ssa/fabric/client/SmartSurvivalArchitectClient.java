package dev.ssa.fabric.client;

import dev.ssa.fabric.SmartSurvivalArchitectMod;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SmartSurvivalArchitectClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(SmartSurvivalArchitectMod.MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("SSA_S1_CLIENT_READY");
    }
}
