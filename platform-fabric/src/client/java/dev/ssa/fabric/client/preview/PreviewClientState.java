package dev.ssa.fabric.client.preview;

import dev.ssa.architect.blueprint.BlockRole;
import dev.ssa.architect.blueprint.Blueprint;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.fabric.client.spike.preview.PreviewLayer;
import dev.ssa.fabric.client.spike.preview.PreviewRevision;
import dev.ssa.fabric.network.PreviewPayloads.ConfirmPreview;
import dev.ssa.fabric.network.PreviewPayloads.PreviewFailure;
import dev.ssa.fabric.network.PreviewPayloads.PreviewResult;
import dev.ssa.fabric.network.PreviewPayloads.RequestPreview;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class PreviewClientState {
    private static final String AIR = "minecraft:air";
    private static final Comparator<GridPos> POSITION_ORDER = Comparator.comparingInt(GridPos::x)
            .thenComparingInt(GridPos::y)
            .thenComparingInt(GridPos::z);

    private final RevisionSink sink;
    private String surveyToken;
    private long nextRequestNonce;
    private long pendingRequestNonce = -1;
    private long renderRevision;
    private PreviewResult authoritativePreview;
    private BlockPos displayOrigin;
    private UUID selectedHutId;
    private Set<GridPos> conflictCells = Set.of();
    private boolean locallyMoved;
    private PreviewFailure.Reason lastFailure;

    public PreviewClientState(RevisionSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    public static PreviewClientState production() {
        Minecraft client = Minecraft.getInstance();
        return new PreviewClientState(new RevisionSink() {
            @Override
            public void replace(PreviewRevision revision) {
                onClientThread(client, () -> GhostPreviewRenderer.replace(revision));
            }

            @Override
            public void clear() {
                onClientThread(client, GhostPreviewRenderer::clear);
            }
        });
    }

    private static void onClientThread(Minecraft client, Runnable action) {
        if (client.isSameThread()) {
            action.run();
        } else {
            client.execute(action);
        }
    }

    public synchronized boolean receiveSurveyToken(String token) {
        Objects.requireNonNull(token, "token");
        if (token.isBlank()) {
            throw new IllegalArgumentException("Survey token must not be blank");
        }
        boolean startsWorkflow = surveyToken == null
                && pendingRequestNonce < 0
                && authoritativePreview == null;
        surveyToken = token;
        pendingRequestNonce = -1;
        return startsWorkflow;
    }

    public synchronized boolean canRequestPreview() {
        return surveyToken != null;
    }

    public synchronized RequestPreview requestPreview(HouseRequirements requirements, int rotation) {
        Objects.requireNonNull(requirements, "requirements");
        if (surveyToken == null) {
            throw new IllegalStateException("A server Survey token is required before requesting a preview");
        }
        long nonce = nextRequestNonce++;
        RequestPreview request = new RequestPreview(surveyToken, requirements, rotation, nonce);
        surveyToken = null;
        pendingRequestNonce = nonce;
        lastFailure = null;
        authoritativePreview = null;
        displayOrigin = null;
        conflictCells = Set.of();
        locallyMoved = false;
        sink.clear();
        return request;
    }

    public synchronized boolean accept(PreviewResult result) {
        Objects.requireNonNull(result, "result");
        if (result.requestNonce() != pendingRequestNonce) {
            return false;
        }
        long nextRevision = renderRevision + 1;
        PreviewRevision revision;
        try {
            revision = buildRevision(result, result.origin(), Set.of(), nextRevision);
        } catch (IllegalArgumentException oversizedOrEmptyPreview) {
            pendingRequestNonce = -1;
            sink.clear();
            return false;
        }
        authoritativePreview = result;
        displayOrigin = result.origin();
        conflictCells = Set.of();
        locallyMoved = false;
        pendingRequestNonce = -1;
        renderRevision = nextRevision;
        sink.replace(revision);
        return true;
    }

    public synchronized boolean reject(PreviewFailure failure) {
        Objects.requireNonNull(failure, "failure");
        if (failure.requestNonce() != pendingRequestNonce) {
            return false;
        }
        pendingRequestNonce = -1;
        lastFailure = failure.reason();
        return true;
    }

    public synchronized Optional<PreviewFailure.Reason> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    public synchronized void selectHut(UUID hutId) {
        selectedHutId = Objects.requireNonNull(hutId, "hutId");
    }

    public synchronized Optional<ConfirmPreview> confirmation() {
        if (authoritativePreview == null || selectedHutId == null || locallyMoved) {
            return Optional.empty();
        }
        return Optional.of(new ConfirmPreview(
                authoritativePreview.previewSessionId(),
                authoritativePreview.blueprintHash(),
                selectedHutId));
    }

    public synchronized void setConflictCells(Set<GridPos> cells) {
        Set<GridPos> nextCells = Set.copyOf(Objects.requireNonNull(cells, "cells"));
        if (authoritativePreview == null) {
            conflictCells = nextCells;
            return;
        }
        long nextRevision = renderRevision + 1;
        PreviewRevision revision = buildRevision(
                authoritativePreview, displayOrigin, nextCells, nextRevision);
        conflictCells = nextCells;
        renderRevision = nextRevision;
        sink.replace(revision);
    }

    public synchronized void movePreview(BlockPos origin) {
        Objects.requireNonNull(origin, "origin");
        if (authoritativePreview == null) {
            throw new IllegalStateException("No server preview is available to move");
        }
        BlockPos nextOrigin = origin.immutable();
        long nextRevision = renderRevision + 1;
        PreviewRevision revision = buildRevision(
                authoritativePreview, nextOrigin, conflictCells, nextRevision);
        displayOrigin = nextOrigin;
        locallyMoved = !nextOrigin.equals(authoritativePreview.origin());
        renderRevision = nextRevision;
        sink.replace(revision);
    }

    public synchronized Optional<PreviewResult> preview() {
        return Optional.ofNullable(authoritativePreview);
    }

    public synchronized void clear() {
        surveyToken = null;
        pendingRequestNonce = -1;
        authoritativePreview = null;
        displayOrigin = null;
        selectedHutId = null;
        conflictCells = Set.of();
        locallyMoved = false;
        lastFailure = null;
        sink.clear();
    }

    private static PreviewRevision buildRevision(
            PreviewResult result,
            BlockPos origin,
            Set<GridPos> conflicts,
            long revision) {
        Blueprint blueprint = result.blueprint();
        Map<GridPos, PreviewLayer> cells = new HashMap<>();

        addFootprintBoundary(cells, blueprint);
        blueprint.terrainPlan().changes().forEach(change -> putLayer(
                cells,
                change.pos(),
                change.afterState().blockId().toString().equals(AIR)
                        ? PreviewLayer.TERRAIN_REMOVAL
                        : PreviewLayer.TERRAIN_FILL));
        blueprint.blocks().forEach(block -> putLayer(
                cells,
                block.relativePosition(),
                block.materialRole() == dev.ssa.architect.material.MaterialRole.DOOR
                        ? PreviewLayer.ENTRANCE
                        : block.blockRole() == BlockRole.DECORATION
                                ? PreviewLayer.OPTIONAL
                                : PreviewLayer.REQUIRED));
        conflicts.forEach(position -> putLayer(cells, position, PreviewLayer.CONFLICT));

        if (cells.isEmpty() || cells.size() > PreviewRevision.MAX_BLOCKS) {
            throw new IllegalArgumentException(
                    "Preview render cells must be between 1 and " + PreviewRevision.MAX_BLOCKS);
        }

        List<Map.Entry<GridPos, PreviewLayer>> ordered = new ArrayList<>(cells.entrySet());
        ordered.sort(Map.Entry.comparingByKey(POSITION_ORDER));
        int[] x = new int[ordered.size()];
        int[] y = new int[ordered.size()];
        int[] z = new int[ordered.size()];
        PreviewLayer[] layers = new PreviewLayer[ordered.size()];
        for (int index = 0; index < ordered.size(); index++) {
            Map.Entry<GridPos, PreviewLayer> entry = ordered.get(index);
            GridPos rotated = PreviewTransform.rotate(entry.getKey(), result.rotation());
            x[index] = rotated.x();
            y[index] = rotated.y();
            z[index] = rotated.z();
            layers[index] = entry.getValue();
        }

        long contentIdentity = blueprint.id().getMostSignificantBits()
                ^ blueprint.id().getLeastSignificantBits();
        return PreviewRevision.create(
                revision,
                contentIdentity,
                origin.getX(),
                origin.getY(),
                origin.getZ(),
                result.rotation() / 90,
                x,
                y,
                z,
                layers);
    }

    private static void addFootprintBoundary(Map<GridPos, PreviewLayer> cells, Blueprint blueprint) {
        int markerY = blueprint.localBounds().minimum().y() - 1;
        for (GridPos cell : blueprint.footprint()) {
            if (isBoundary(cell, blueprint.footprint())) {
                putLayer(cells, new GridPos(cell.x(), markerY, cell.z()), PreviewLayer.FOOTPRINT);
            }
        }
    }

    private static boolean isBoundary(GridPos cell, Set<GridPos> footprint) {
        return !footprint.contains(new GridPos(cell.x() + 1, cell.y(), cell.z()))
                || !footprint.contains(new GridPos(cell.x() - 1, cell.y(), cell.z()))
                || !footprint.contains(new GridPos(cell.x(), cell.y(), cell.z() + 1))
                || !footprint.contains(new GridPos(cell.x(), cell.y(), cell.z() - 1));
    }

    private static void putLayer(Map<GridPos, PreviewLayer> cells, GridPos position, PreviewLayer layer) {
        cells.compute(position, (ignored, existing) -> existing == null || priority(layer) > priority(existing)
                ? layer
                : existing);
    }

    private static int priority(PreviewLayer layer) {
        return switch (layer) {
            case FOOTPRINT -> 1;
            case OPTIONAL -> 2;
            case REQUIRED -> 3;
            case TERRAIN_FILL, TERRAIN_REMOVAL -> 4;
            case ENTRANCE -> 5;
            case CONFLICT -> 6;
        };
    }

    public interface RevisionSink {
        void replace(PreviewRevision revision);

        void clear();
    }
}
