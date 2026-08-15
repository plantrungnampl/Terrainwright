package dev.ssa.fabric.client.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.model.EntrancePreference;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.StyleId;
import dev.ssa.fabric.client.spike.preview.PreviewLayer;
import dev.ssa.fabric.client.spike.preview.PreviewRevision;
import dev.ssa.fabric.network.PreviewPayloads.PreviewResult;
import dev.ssa.fabric.network.PreviewPayloads.PreviewFailure;
import dev.ssa.fabric.network.PreviewPayloads.RequestPreview;
import dev.ssa.fabric.preview.PreviewTestFixtures;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class PreviewClientStateTest {
    @Test
    void onlyNewestMatchingServerResultBecomesRenderableAndConfirmable() {
        RecordingSink sink = new RecordingSink();
        PreviewClientState state = new PreviewClientState(sink);
        var blueprint = PreviewTestFixtures.blueprint(42);

        state.receiveSurveyToken("first-token");
        RequestPreview first = state.requestPreview(requirements(42), 0);
        state.receiveSurveyToken("replacement-token");
        RequestPreview replacement = state.requestPreview(requirements(43), 90);

        assertFalse(state.accept(new PreviewResult(
                UUID.randomUUID(),
                blueprint.hash(),
                blueprint,
                new BlockPos(10, 64, 20),
                0,
                500,
                first.requestNonce())));
        assertTrue(state.accept(new PreviewResult(
                UUID.randomUUID(),
                blueprint.hash(),
                blueprint,
                new BlockPos(30, 70, 40),
                90,
                500,
                replacement.requestNonce())));

        PreviewRevision revision = sink.revision;
        assertEquals(30, revision.originX());
        assertEquals(70, revision.originY());
        assertEquals(40, revision.originZ());
        assertEquals(1, revision.rotationQuarterTurns());
        assertEquals(1, revision.layerCount(PreviewLayer.REQUIRED));
        assertEquals(1, revision.layerCount(PreviewLayer.FOOTPRINT));

        UUID hutId = UUID.randomUUID();
        state.selectHut(hutId);
        assertEquals(hutId, state.confirmation().orElseThrow().chosenHutId());
    }

    @Test
    void reselectingSiteRequiresANewAuthoritativePreviewBeforeConfirmation() {
        PreviewClientState state = new PreviewClientState(new RecordingSink());
        var blueprint = PreviewTestFixtures.blueprint(47);
        UUID firstSessionId = UUID.randomUUID();
        UUID replacementSessionId = UUID.randomUUID();
        UUID hutId = UUID.randomUUID();

        state.receiveSurveyToken("first-site");
        RequestPreview firstRequest = state.requestPreview(requirements(47), 0);
        assertTrue(state.accept(new PreviewResult(
                firstSessionId,
                blueprint.hash(),
                blueprint,
                new BlockPos(10, 64, 20),
                0,
                500,
                firstRequest.requestNonce())));
        state.selectHut(hutId);
        assertEquals(firstSessionId, state.confirmation().orElseThrow().previewSessionId());

        state.clear();

        assertTrue(state.confirmation().isEmpty());
        state.receiveSurveyToken("replacement-site");
        RequestPreview replacementRequest = state.requestPreview(requirements(48), 90);
        assertTrue(state.accept(new PreviewResult(
                replacementSessionId,
                blueprint.hash(),
                blueprint,
                new BlockPos(30, 70, 40),
                90,
                500,
                replacementRequest.requestNonce())));
        state.selectHut(hutId);

        assertEquals(replacementSessionId, state.confirmation().orElseThrow().previewSessionId());
        assertEquals(new BlockPos(30, 70, 40), state.preview().orElseThrow().origin());
    }

    @Test
    void localMoveAndConflictOverlayRebuildTheGhostButInvalidateAuthority() {
        RecordingSink sink = new RecordingSink();
        PreviewClientState state = new PreviewClientState(sink);
        var blueprint = PreviewTestFixtures.blueprint(51);
        state.receiveSurveyToken("survey-token");
        RequestPreview request = state.requestPreview(requirements(51), 0);
        state.accept(new PreviewResult(
                UUID.randomUUID(),
                blueprint.hash(),
                blueprint,
                new BlockPos(5, 65, 9),
                0,
                600,
                request.requestNonce()));
        state.selectHut(UUID.randomUUID());

        state.setConflictCells(Set.of(new GridPos(0, 0, 0)));
        assertEquals(1, sink.revision.layerCount(PreviewLayer.CONFLICT));
        assertEquals(0, sink.revision.layerCount(PreviewLayer.REQUIRED));

        state.movePreview(new BlockPos(8, 65, 12));

        assertEquals(8, sink.revision.originX());
        assertEquals(12, sink.revision.originZ());
        assertTrue(state.confirmation().isEmpty());
    }

    @Test
    void retryTokenAcknowledgesRejectedRequestAndAllowsAnotherAttempt() {
        RecordingSink sink = new RecordingSink();
        PreviewClientState state = new PreviewClientState(sink);
        state.receiveSurveyToken("retry-token");
        state.requestPreview(requirements(61), 0);

        assertFalse(state.canRequestPreview());

        state.receiveSurveyToken("retry-token");

        assertTrue(state.canRequestPreview());
        assertEquals("retry-token", state.requestPreview(requirements(62), 0).surveyToken());
    }

    @Test
    void matchingFailureClearsPendingRequestAndExposesReason() {
        PreviewClientState state = new PreviewClientState(new RecordingSink());
        state.receiveSurveyToken("expiring-token");
        long nonce = state.requestPreview(requirements(71), 0).requestNonce();

        assertTrue(state.reject(new PreviewFailure(nonce, PreviewFailure.Reason.SURVEY_EXPIRED)));
        assertEquals(PreviewFailure.Reason.SURVEY_EXPIRED, state.lastFailure().orElseThrow());
        assertFalse(state.reject(new PreviewFailure(nonce + 1, PreviewFailure.Reason.SERVER_BUSY)));
    }

    private static HouseRequirements requirements(long seed) {
        return new HouseRequirements(
                StyleId.parse("smart_survival_architect:medieval"),
                9,
                11,
                1,
                1,
                true,
                true,
                false,
                false,
                EntrancePreference.AUTO,
                seed);
    }

    private static final class RecordingSink implements PreviewClientState.RevisionSink {
        private PreviewRevision revision;

        @Override
        public void replace(PreviewRevision revision) {
            this.revision = revision;
        }

        @Override
        public void clear() {
            revision = null;
        }
    }
}
