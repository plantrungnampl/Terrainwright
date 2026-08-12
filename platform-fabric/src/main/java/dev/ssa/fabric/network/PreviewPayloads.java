package dev.ssa.fabric.network;

import dev.ssa.architect.blueprint.Blueprint;
import dev.ssa.architect.model.EntrancePreference;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.StyleId;
import dev.ssa.fabric.SmartSurvivalArchitectMod;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class PreviewPayloads {
    private PreviewPayloads() {}

    public record StartSurvey(BlockPos architectTablePos) implements CustomPacketPayload {
        public static final Type<StartSurvey> TYPE = PreviewPayloads.type("start_survey");
        public static final StreamCodec<RegistryFriendlyByteBuf, StartSurvey> CODEC = StreamCodec.of(
                StartSurvey::encode,
                StartSurvey::decode);

        public StartSurvey {
            architectTablePos = Objects.requireNonNull(architectTablePos, "architectTablePos").immutable();
        }

        @Override
        public Type<StartSurvey> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, StartSurvey payload) {
            writeBlockPos(buffer, payload.architectTablePos());
        }

        private static StartSurvey decode(RegistryFriendlyByteBuf buffer) {
            return new StartSurvey(readBlockPos(buffer));
        }
    }

    public record CancelSurvey() implements CustomPacketPayload {
        public static final Type<CancelSurvey> TYPE = PreviewPayloads.type("cancel_survey");
        public static final StreamCodec<RegistryFriendlyByteBuf, CancelSurvey> CODEC = StreamCodec.of(
                (buffer, payload) -> {},
                buffer -> new CancelSurvey());

        @Override
        public Type<CancelSurvey> type() {
            return TYPE;
        }
    }

    public record SurveyStatus(Action action, boolean accepted) implements CustomPacketPayload {
        public static final Type<SurveyStatus> TYPE = PreviewPayloads.type("survey_status");
        public static final StreamCodec<RegistryFriendlyByteBuf, SurveyStatus> CODEC = StreamCodec.of(
                SurveyStatus::encode,
                SurveyStatus::decode);

        public SurveyStatus {
            Objects.requireNonNull(action, "action");
        }

        @Override
        public Type<SurveyStatus> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, SurveyStatus payload) {
            buffer.writeVarInt(payload.action().ordinal());
            buffer.writeBoolean(payload.accepted());
        }

        private static SurveyStatus decode(RegistryFriendlyByteBuf buffer) {
            int ordinal = buffer.readVarInt();
            Action[] actions = Action.values();
            if (ordinal < 0 || ordinal >= actions.length) {
                throw new IllegalArgumentException("Unknown Survey action");
            }
            return new SurveyStatus(actions[ordinal], buffer.readBoolean());
        }

        public enum Action {
            START,
            SELECT_SITE
        }
    }

    public record SelectSurveySite(BlockPos anchor) implements CustomPacketPayload {
        public static final Type<SelectSurveySite> TYPE = PreviewPayloads.type("select_survey_site");
        public static final StreamCodec<RegistryFriendlyByteBuf, SelectSurveySite> CODEC = StreamCodec.of(
                SelectSurveySite::encode,
                SelectSurveySite::decode);

        public SelectSurveySite {
            anchor = Objects.requireNonNull(anchor, "anchor").immutable();
        }

        @Override
        public Type<SelectSurveySite> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, SelectSurveySite payload) {
            writeBlockPos(buffer, payload.anchor());
        }

        private static SelectSurveySite decode(RegistryFriendlyByteBuf buffer) {
            return new SelectSurveySite(readBlockPos(buffer));
        }
    }

    public record SurveyTokenResult(String surveyToken) implements CustomPacketPayload {
        public static final Type<SurveyTokenResult> TYPE = PreviewPayloads.type("survey_token_result");
        public static final StreamCodec<RegistryFriendlyByteBuf, SurveyTokenResult> CODEC = StreamCodec.of(
                SurveyTokenResult::encode,
                SurveyTokenResult::decode);

        public SurveyTokenResult {
            Objects.requireNonNull(surveyToken, "surveyToken");
            if (surveyToken.isBlank() || surveyToken.length() > 256) {
                throw new IllegalArgumentException("surveyToken must contain 1 to 256 characters");
            }
        }

        @Override
        public Type<SurveyTokenResult> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, SurveyTokenResult payload) {
            buffer.writeUtf(payload.surveyToken(), 256);
        }

        private static SurveyTokenResult decode(RegistryFriendlyByteBuf buffer) {
            return new SurveyTokenResult(buffer.readUtf(256));
        }
    }

    public record RequestPreview(
            String surveyToken,
            HouseRequirements requirements,
            int rotation,
            long requestNonce) implements CustomPacketPayload {
        public static final Type<RequestPreview> TYPE = PreviewPayloads.type("request_preview");
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestPreview> CODEC = StreamCodec.of(
                RequestPreview::encode,
                RequestPreview::decode);

        public RequestPreview {
            Objects.requireNonNull(surveyToken, "surveyToken");
            Objects.requireNonNull(requirements, "requirements");
            if (surveyToken.isBlank() || surveyToken.length() > 256) {
                throw new IllegalArgumentException("surveyToken must contain 1 to 256 characters");
            }
            requireRotation(rotation);
            if (requestNonce < 0) {
                throw new IllegalArgumentException("requestNonce must not be negative");
            }
        }

        @Override
        public Type<RequestPreview> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, RequestPreview payload) {
            buffer.writeUtf(payload.surveyToken(), 256);
            HouseRequirements requirements = payload.requirements();
            buffer.writeUtf(requirements.styleId().toString(), 160);
            buffer.writeVarInt(requirements.targetWidth());
            buffer.writeVarInt(requirements.targetDepth());
            buffer.writeVarInt(requirements.floors());
            buffer.writeVarInt(requirements.bedrooms());
            buffer.writeBoolean(requirements.kitchen());
            buffer.writeBoolean(requirements.storage());
            buffer.writeBoolean(requirements.balcony());
            buffer.writeBoolean(requirements.chimney());
            buffer.writeVarInt(requirements.entrancePreference().ordinal());
            buffer.writeLong(requirements.seed());
            buffer.writeVarInt(payload.rotation());
            buffer.writeLong(payload.requestNonce());
        }

        private static RequestPreview decode(RegistryFriendlyByteBuf buffer) {
            String token = buffer.readUtf(256);
            StyleId styleId = StyleId.parse(buffer.readUtf(160));
            int width = buffer.readVarInt();
            int depth = buffer.readVarInt();
            int floors = buffer.readVarInt();
            int bedrooms = buffer.readVarInt();
            boolean kitchen = buffer.readBoolean();
            boolean storage = buffer.readBoolean();
            boolean balcony = buffer.readBoolean();
            boolean chimney = buffer.readBoolean();
            int entranceOrdinal = buffer.readVarInt();
            EntrancePreference[] entrances = EntrancePreference.values();
            if (entranceOrdinal < 0 || entranceOrdinal >= entrances.length) {
                throw new IllegalArgumentException("Unknown entrance preference in preview request");
            }
            HouseRequirements requirements = new HouseRequirements(
                    styleId,
                    width,
                    depth,
                    floors,
                    bedrooms,
                    kitchen,
                    storage,
                    balcony,
                    chimney,
                    entrances[entranceOrdinal],
                    buffer.readLong());
            return new RequestPreview(token, requirements, buffer.readVarInt(), buffer.readLong());
        }
    }

    public record ConfirmPreview(
            UUID previewSessionId,
            String expectedBlueprintHash,
            UUID chosenHutId) implements CustomPacketPayload {
        public static final Type<ConfirmPreview> TYPE = PreviewPayloads.type("confirm_preview");
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfirmPreview> CODEC = StreamCodec.of(
                ConfirmPreview::encode,
                ConfirmPreview::decode);

        public ConfirmPreview {
            Objects.requireNonNull(previewSessionId, "previewSessionId");
            Objects.requireNonNull(expectedBlueprintHash, "expectedBlueprintHash");
            Objects.requireNonNull(chosenHutId, "chosenHutId");
            if (!expectedBlueprintHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("expectedBlueprintHash must be lowercase SHA-256");
            }
        }

        @Override
        public Type<ConfirmPreview> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, ConfirmPreview payload) {
            buffer.writeUUID(payload.previewSessionId());
            buffer.writeUtf(payload.expectedBlueprintHash(), 64);
            buffer.writeUUID(payload.chosenHutId());
        }

        private static ConfirmPreview decode(RegistryFriendlyByteBuf buffer) {
            return new ConfirmPreview(buffer.readUUID(), buffer.readUtf(64), buffer.readUUID());
        }
    }

    public record PreviewResult(
            UUID previewSessionId,
            String blueprintHash,
            Blueprint blueprint,
            BlockPos origin,
            int rotation,
            long expiryRevision,
            long requestNonce) implements CustomPacketPayload {
        public static final Type<PreviewResult> TYPE = PreviewPayloads.type("preview_result");
        public static final StreamCodec<RegistryFriendlyByteBuf, PreviewResult> CODEC = StreamCodec.of(
                PreviewResult::encode,
                PreviewResult::decode);

        public PreviewResult {
            Objects.requireNonNull(previewSessionId, "previewSessionId");
            Objects.requireNonNull(blueprintHash, "blueprintHash");
            Objects.requireNonNull(blueprint, "blueprint");
            origin = Objects.requireNonNull(origin, "origin").immutable();
            if (!blueprint.hash().equals(blueprintHash)) {
                throw new IllegalArgumentException("Preview result hash does not match its Blueprint");
            }
            requireRotation(rotation);
            if (expiryRevision < 0 || requestNonce < 0) {
                throw new IllegalArgumentException("Preview result revisions/nonces must not be negative");
            }
        }

        @Override
        public Type<PreviewResult> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, PreviewResult payload) {
            buffer.writeUUID(payload.previewSessionId());
            buffer.writeUtf(payload.blueprintHash(), 64);
            BlueprintStreamCodec.CODEC.encode(buffer, payload.blueprint());
            writeBlockPos(buffer, payload.origin());
            buffer.writeVarInt(payload.rotation());
            buffer.writeLong(payload.expiryRevision());
            buffer.writeLong(payload.requestNonce());
        }

        private static PreviewResult decode(RegistryFriendlyByteBuf buffer) {
            return new PreviewResult(
                    buffer.readUUID(),
                    buffer.readUtf(64),
                    BlueprintStreamCodec.CODEC.decode(buffer),
                    readBlockPos(buffer),
                    buffer.readVarInt(),
                    buffer.readLong(),
                    buffer.readLong());
        }
    }

    public record PreviewFailure(long requestNonce, Reason reason) implements CustomPacketPayload {
        public static final Type<PreviewFailure> TYPE = PreviewPayloads.type("preview_failure");
        public static final StreamCodec<RegistryFriendlyByteBuf, PreviewFailure> CODEC = StreamCodec.of(
                PreviewFailure::encode,
                PreviewFailure::decode);

        public PreviewFailure {
            Objects.requireNonNull(reason, "reason");
            if (requestNonce < 0) {
                throw new IllegalArgumentException("requestNonce must not be negative");
            }
        }

        @Override
        public Type<PreviewFailure> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, PreviewFailure payload) {
            buffer.writeLong(payload.requestNonce());
            buffer.writeVarInt(payload.reason().ordinal());
        }

        private static PreviewFailure decode(RegistryFriendlyByteBuf buffer) {
            long nonce = buffer.readLong();
            int ordinal = buffer.readVarInt();
            Reason[] reasons = Reason.values();
            if (ordinal < 0 || ordinal >= reasons.length) {
                throw new IllegalArgumentException("Unknown preview failure reason");
            }
            return new PreviewFailure(nonce, reasons[ordinal]);
        }

        public enum Reason {
            RATE_LIMITED,
            INVALID_SURVEY,
            SURVEY_EXPIRED,
            WRONG_DIMENSION,
            TERRAIN_UNAVAILABLE,
            GENERATION_FAILED,
            SERVER_BUSY
        }
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(
                SmartSurvivalArchitectMod.MOD_ID, path));
    }

    private static void writeBlockPos(RegistryFriendlyByteBuf buffer, BlockPos position) {
        buffer.writeInt(position.getX());
        buffer.writeInt(position.getY());
        buffer.writeInt(position.getZ());
    }

    private static BlockPos readBlockPos(RegistryFriendlyByteBuf buffer) {
        return new BlockPos(buffer.readInt(), buffer.readInt(), buffer.readInt());
    }

    private static void requireRotation(int rotation) {
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
            throw new IllegalArgumentException("Rotation must be 0, 90, 180, or 270 degrees");
        }
    }
}
