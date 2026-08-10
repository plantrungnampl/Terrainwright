package dev.ssa.fabric.client;

import dev.ssa.fabric.SmartSurvivalArchitectMod;
import dev.ssa.fabric.client.spike.preview.GhostPreviewRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SmartSurvivalArchitectClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(SmartSurvivalArchitectMod.MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("SSA_S1_CLIENT_READY");
        GhostPreviewRenderer.initialize();
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> LOGGER.info("SSA_S1_CLIENT_STARTED"));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> GhostPreviewRenderer.dispose());
    }
}
