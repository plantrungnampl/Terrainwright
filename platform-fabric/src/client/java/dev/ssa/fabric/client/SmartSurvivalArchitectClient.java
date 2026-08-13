package dev.ssa.fabric.client;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.fabric.SmartSurvivalArchitectMod;
import dev.ssa.fabric.client.preview.GhostPreviewRenderer;
import dev.ssa.fabric.client.preview.PreviewClientState;
import dev.ssa.fabric.client.preview.PreviewTransform;
import dev.ssa.fabric.client.entity.BuilderRenderer;
import dev.ssa.fabric.client.screen.ArchitectScreen;
import dev.ssa.fabric.block.BuilderHutBlockEntity;
import dev.ssa.fabric.block.ModBlocks;
import dev.ssa.fabric.entity.ModEntityTypes;
import dev.ssa.fabric.network.PreviewPayloads.PreviewResult;
import dev.ssa.fabric.network.PreviewPayloads.CancelSurvey;
import dev.ssa.fabric.network.PreviewPayloads.PreviewFailure;
import dev.ssa.fabric.network.PreviewPayloads.SelectSurveySite;
import dev.ssa.fabric.network.PreviewPayloads.StartSurvey;
import dev.ssa.fabric.network.PreviewPayloads.SurveyTokenResult;
import dev.ssa.fabric.network.PreviewPayloads.SurveyStatus;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SmartSurvivalArchitectClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(SmartSurvivalArchitectMod.MOD_ID);
    private static final PreviewClientState PREVIEW_STATE = PreviewClientState.production();
    private static BlockPos activeArchitectTable;
    private static SelectionMode selectionMode = SelectionMode.NONE;

    @Override
    public void onInitializeClient() {
        LOGGER.info("SSA_S1_CLIENT_READY");
        EntityRendererRegistry.register(ModEntityTypes.BUILDER, BuilderRenderer::new);
        GhostPreviewRenderer.initialize();
        ClientPlayNetworking.registerGlobalReceiver(SurveyTokenResult.TYPE, SmartSurvivalArchitectClient::receiveToken);
        ClientPlayNetworking.registerGlobalReceiver(SurveyStatus.TYPE, SmartSurvivalArchitectClient::receiveSurveyStatus);
        ClientPlayNetworking.registerGlobalReceiver(PreviewFailure.TYPE, SmartSurvivalArchitectClient::receiveFailure);
        ClientPlayNetworking.registerGlobalReceiver(PreviewResult.TYPE, SmartSurvivalArchitectClient::receivePreview);
        UseBlockCallback.EVENT.register(SmartSurvivalArchitectClient::useBlock);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (selectionMode != SelectionMode.NONE && client.gui.screen() instanceof PauseScreen) {
                cancelSelection(false);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> clearWorkflow());
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> LOGGER.info("SSA_S1_CLIENT_STARTED"));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> clearWorkflow());
    }

    public static PreviewClientState previewState() {
        return PREVIEW_STATE;
    }

    private static void receiveToken(SurveyTokenResult payload, ClientPlayNetworking.Context context) {
        context.client().execute(() -> {
            boolean wasSelectingSite = selectionMode == SelectionMode.SITE;
            boolean startsWorkflow = PREVIEW_STATE.receiveSurveyToken(payload.surveyToken());
            selectionMode = SelectionMode.NONE;
            if (startsWorkflow || wasSelectingSite) {
                openScreen(context.client());
            }
        });
    }

    private static InteractionResult useBlock(
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.level.Level level,
            net.minecraft.world.InteractionHand hand,
            net.minecraft.world.phys.BlockHitResult hit) {
        if (!level.isClientSide() || hand != net.minecraft.world.InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        BlockPos position = hit.getBlockPos();
        if (selectionMode != SelectionMode.NONE
                && level.getBlockState(position).is(ModBlocks.ARCHITECT_TABLE)) {
            cancelSelection(true);
            return InteractionResult.SUCCESS;
        }
        if (selectionMode == SelectionMode.SITE) {
            if (hit.getDirection() != Direction.UP) {
                cancelSelection(true);
                return InteractionResult.SUCCESS;
            }
            ClientPlayNetworking.send(new SelectSurveySite(position));
            return InteractionResult.SUCCESS;
        }
        if (selectionMode == SelectionMode.HUT) {
            if (level.getBlockEntity(position) instanceof BuilderHutBlockEntity hut) {
                PREVIEW_STATE.selectHut(hut.references().hutId());
                selectionMode = SelectionMode.NONE;
                openScreen(Minecraft.getInstance());
                return InteractionResult.SUCCESS;
            }
            cancelSelection(true);
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockState(position).is(ModBlocks.ARCHITECT_TABLE)) {
            activeArchitectTable = position.immutable();
            PREVIEW_STATE.clear();
            openScreen(Minecraft.getInstance());
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private static void beginSiteSelection() {
        if (activeArchitectTable == null) {
            return;
        }
        PREVIEW_STATE.clear();
        ClientPlayNetworking.send(new StartSurvey(activeArchitectTable));
        selectionMode = SelectionMode.SITE_PENDING;
        Minecraft.getInstance().setScreenAndShow(null);
    }

    private static void beginHutSelection() {
        selectionMode = SelectionMode.HUT;
        Minecraft.getInstance().setScreenAndShow(null);
    }

    private static void cancelSelection(boolean reopenScreen) {
        ClientPlayNetworking.send(new CancelSurvey());
        selectionMode = SelectionMode.NONE;
        if (reopenScreen) {
            openScreen(Minecraft.getInstance());
        }
    }

    private static void receiveSurveyStatus(SurveyStatus payload, ClientPlayNetworking.Context context) {
        context.client().execute(() -> {
            if (payload.action() == SurveyStatus.Action.START
                    && selectionMode == SelectionMode.SITE_PENDING
                    && payload.accepted()) {
                selectionMode = SelectionMode.SITE;
                return;
            }
            if (!payload.accepted()
                    && (selectionMode == SelectionMode.SITE_PENDING || selectionMode == SelectionMode.SITE)) {
                selectionMode = SelectionMode.NONE;
                openScreen(context.client());
            }
        });
    }

    private static void openScreen(Minecraft client) {
        client.setScreenAndShow(new ArchitectScreen(
                PREVIEW_STATE,
                SmartSurvivalArchitectClient::beginSiteSelection,
                SmartSurvivalArchitectClient::beginHutSelection));
    }

    private static void clearWorkflow() {
        activeArchitectTable = null;
        selectionMode = SelectionMode.NONE;
        PREVIEW_STATE.clear();
    }

    private static void receivePreview(PreviewResult payload, ClientPlayNetworking.Context context) {
        context.client().execute(() -> {
            if (PREVIEW_STATE.accept(payload) && context.client().level != null) {
                PREVIEW_STATE.setConflictCells(conflicts(payload, context.client().level));
            }
        });
    }

    private static void receiveFailure(PreviewFailure payload, ClientPlayNetworking.Context context) {
        context.client().execute(() -> PREVIEW_STATE.reject(payload));
    }

    private static Set<GridPos> conflicts(PreviewResult result, net.minecraft.client.multiplayer.ClientLevel level) {
        Set<GridPos> conflicts = new HashSet<>();
        result.blueprint().blocks().forEach(block -> {
            var worldPosition = PreviewTransform.toWorld(
                    result.origin(), block.relativePosition(), result.rotation());
            if (!level.isLoaded(worldPosition)) {
                return;
            }
            var current = level.getBlockState(worldPosition);
            if (!current.isAir() && !matches(current, block.placementState())) {
                conflicts.add(block.relativePosition());
            }
        });
        result.blueprint().terrainPlan().changes().forEach(change -> {
            var worldPosition = PreviewTransform.toWorld(result.origin(), change.pos(), result.rotation());
            if (!level.isLoaded(worldPosition)) {
                return;
            }
            if (!matches(level.getBlockState(worldPosition), change.beforeState())) {
                conflicts.add(change.pos());
            }
        });
        return Set.copyOf(conflicts);
    }

    private static boolean matches(BlockState state, BlockStateSpec expected) {
        String currentId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (!currentId.equals(expected.blockId().toString())) {
            return false;
        }
        return expected.properties().entrySet().stream().allMatch(expectedProperty -> state.getProperties().stream()
                .filter(property -> property.getName().equals(expectedProperty.getKey()))
                .anyMatch(property -> propertyValue(state, property).equals(expectedProperty.getValue())));
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private enum SelectionMode {
        NONE,
        SITE_PENDING,
        SITE,
        HUT
    }
}
