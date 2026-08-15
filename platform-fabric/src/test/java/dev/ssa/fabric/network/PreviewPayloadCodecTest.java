package dev.ssa.fabric.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.blueprint.Blueprint;
import dev.ssa.architect.model.EntrancePreference;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.StyleId;
import dev.ssa.fabric.network.PreviewPayloads.ConfirmPreview;
import dev.ssa.fabric.network.PreviewPayloads.PreviewFailure;
import dev.ssa.fabric.network.PreviewPayloads.PreviewResult;
import dev.ssa.fabric.network.PreviewPayloads.RequestPreview;
import dev.ssa.fabric.network.PreviewPayloads.SurveyStatus;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PreviewPayloadCodecTest {
    @Test
    void previewRequestCarriesOnlySurveyAuthorityAndDesignInputs() {
        HouseRequirements requirements = new HouseRequirements(
                StyleId.parse("ssa:medieval"),
                15,
                19,
                2,
                3,
                true,
                true,
                false,
                true,
                EntrancePreference.FRONT,
                42L);

        RequestPreview request = new RequestPreview("survey-token", requirements, 90, 7L);

        assertEquals("survey-token", request.surveyToken());
        assertEquals(requirements, request.requirements());
        assertEquals(90, request.rotation());
        assertEquals(7L, request.requestNonce());
        assertThrows(IllegalArgumentException.class,
                () -> new RequestPreview("survey-token", requirements, 45, 7L));
    }

    @Test
    void confirmPayloadCarriesOnlyServerIssuedPreviewIdentityHashAndHutChoice() {
        UUID sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID hutId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        ConfirmPreview confirm = new ConfirmPreview(sessionId, hash, hutId);

        assertEquals(sessionId, confirm.previewSessionId());
        assertEquals(hash, confirm.expectedBlueprintHash());
        assertEquals(hutId, confirm.chosenHutId());
        assertThrows(IllegalArgumentException.class,
                () -> new ConfirmPreview(sessionId, "not-a-hash", hutId));
    }

    @Test
    void previewResultRejectsMismatchedBlueprintHash() {
        Blueprint blueprint = PreviewTestBlueprints.blueprint();

        assertThrows(IllegalArgumentException.class, () -> new PreviewResult(
                UUID.randomUUID(),
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                blueprint,
                new net.minecraft.core.BlockPos(0, 64, 0),
                0,
                100,
                1));
    }

    @Test
    void previewFailureRejectsNegativeRequestNonce() {
        assertThrows(IllegalArgumentException.class,
                () -> new PreviewFailure(-1, PreviewFailure.Reason.SERVER_BUSY));
    }

    @Test
    void surveyStatusProtocolIncludesExplicitConfirmAcknowledgement() {
        assertTrue(Arrays.stream(SurveyStatus.Action.values())
                .anyMatch(action -> action.name().equals("CONFIRM")));
    }
}
