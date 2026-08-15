package dev.ssa.fabric.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.model.EntrancePreference;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.StyleId;
import dev.ssa.fabric.preview.PreviewTestFixtures;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

final class PreviewPayloadCodecTest {
    @Test
    void requestAndConfirmPayloadsRoundTripWithoutCoordinatesOrBlueprint() {
        PreviewPayloads.RequestPreview request = new PreviewPayloads.RequestPreview(
                "survey-token",
                new HouseRequirements(
                        StyleId.parse("smart_survival_architect:medieval"),
                        9,
                        11,
                        2,
                        2,
                        true,
                        true,
                        true,
                        false,
                        EntrancePreference.EAST,
                        42),
                270,
                7);
        PreviewPayloads.ConfirmPreview confirm = new PreviewPayloads.ConfirmPreview(
                UUID.randomUUID(),
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                UUID.randomUUID());
        PreviewPayloads.StartSurvey start = new PreviewPayloads.StartSurvey(new BlockPos(1, 64, 2));
        PreviewPayloads.CancelSurvey cancel = new PreviewPayloads.CancelSurvey();
        PreviewPayloads.SurveyStatus status = new PreviewPayloads.SurveyStatus(
                PreviewPayloads.SurveyStatus.Action.START, true);
        PreviewPayloads.SelectSurveySite select = new PreviewPayloads.SelectSurveySite(new BlockPos(9, 70, 11));
        PreviewPayloads.SurveyTokenResult token = new PreviewPayloads.SurveyTokenResult("opaque-token");
        PreviewPayloads.PreviewFailure failure = new PreviewPayloads.PreviewFailure(
                7, PreviewPayloads.PreviewFailure.Reason.SURVEY_EXPIRED);

        assertEquals(start, roundTrip(start, PreviewPayloads.StartSurvey.CODEC));
        assertEquals(cancel, roundTrip(cancel, PreviewPayloads.CancelSurvey.CODEC));
        assertEquals(status, roundTrip(status, PreviewPayloads.SurveyStatus.CODEC));
        assertEquals(select, roundTrip(select, PreviewPayloads.SelectSurveySite.CODEC));
        assertEquals(token, roundTrip(token, PreviewPayloads.SurveyTokenResult.CODEC));
        assertEquals(failure, roundTrip(failure, PreviewPayloads.PreviewFailure.CODEC));
        assertEquals(request, roundTrip(request, PreviewPayloads.RequestPreview.CODEC));
        assertEquals(confirm, roundTrip(confirm, PreviewPayloads.ConfirmPreview.CODEC));
    }

    @Test
    void surveyStatusProtocolIncludesExplicitConfirmAcknowledgement() {
        assertTrue(Arrays.stream(PreviewPayloads.SurveyStatus.Action.values())
                .anyMatch(action -> action.name().equals("CONFIRM")));
    }

    @Test
    void serverPreviewResultRoundTripsTheExactTrustedBlueprint() {
        var blueprint = PreviewTestFixtures.blueprint(42);
        PreviewPayloads.PreviewResult result = new PreviewPayloads.PreviewResult(
                UUID.randomUUID(),
                blueprint.hash(),
                blueprint,
                new BlockPos(125, 71, -48),
                90,
                500,
                8);

        PreviewPayloads.PreviewResult decoded = roundTrip(result, PreviewPayloads.PreviewResult.CODEC);

        assertEquals(result, decoded);
        assertEquals(result.blueprintHash(), decoded.blueprint().hash());
    }

    private static <T> T roundTrip(
            T value,
            net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, T> codec) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            codec.encode(buffer, value);
            return codec.decode(buffer);
        } finally {
            buffer.release();
        }
    }
}
