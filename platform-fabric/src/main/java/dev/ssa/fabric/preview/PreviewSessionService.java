package dev.ssa.fabric.preview;

import dev.ssa.architect.blueprint.Blueprint;
import dev.ssa.architect.model.GridPos;
import dev.ssa.common.permission.PermissionPort;
import dev.ssa.fabric.network.PreviewPayloads.ConfirmPreview;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import dev.ssa.fabric.survey.SurveyModeService.SiteToken;
import dev.ssa.fabric.world.FabricTerrainScanner;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

public final class PreviewSessionService {
    private final Map<UUID, PreviewSession> sessions = new HashMap<>();

    public PreviewSession store(
            UUID owner,
            SiteToken siteToken,
            Blueprint blueprint,
            int rotation,
            long expiryRevision,
            long requestNonce,
            int snapshotWidth,
            int snapshotDepth) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(siteToken, "siteToken");
        Objects.requireNonNull(blueprint, "blueprint");
        if (!siteToken.ownerId().equals(owner)) {
            throw new IllegalArgumentException("Preview owner does not match its Survey token");
        }
        requireRotation(rotation);
        if (expiryRevision < 0 || expiryRevision > siteToken.expiresAtRevision()) {
            throw new IllegalArgumentException("Preview expiry must remain within its Survey token lifetime");
        }
        if (requestNonce < 0) {
            throw new IllegalArgumentException("requestNonce must not be negative");
        }
        requireSnapshotDimensions(snapshotWidth, snapshotDepth);
        PreviewSession session = new PreviewSession(
                UUID.randomUUID(),
                blueprint.hash(),
                blueprint,
                owner,
                expiryRevision,
                siteToken.tokenHash(),
                siteToken.worldRevision(),
                siteToken.dimensionId(),
                siteToken.anchor(),
                snapshotWidth,
                snapshotDepth,
                rotation,
                requestNonce);
        sessions.put(owner, session);
        return session;
    }

    public Optional<ConfirmationAuthority> confirm(
            ServerLevel level,
            FabricTerrainScanner scanner,
            PermissionPort permissions,
            ServerBuildJobRepository repository,
            UUID owner,
            ConfirmPreview request,
            long currentRevision) {
        return confirmDetailed(
                        level,
                        scanner,
                        permissions,
                        repository,
                        owner,
                        request,
                        currentRevision)
                .authority();
    }

    public ConfirmationResult confirmDetailed(
            ServerLevel level,
            FabricTerrainScanner scanner,
            PermissionPort permissions,
            ServerBuildJobRepository repository,
            UUID owner,
            ConfirmPreview request,
            long currentRevision) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(scanner, "scanner");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(request, "request");
        PreviewSession session = sessions.get(owner);
        if (session == null
                || !session.id().equals(request.previewSessionId())
                || !session.blueprintHash().equals(request.expectedBlueprintHash())) {
            return ConfirmationResult.rejected(ConfirmationFailure.SESSION_MISMATCH);
        }
        if (currentRevision > session.expiryRevision()) {
            return ConfirmationResult.rejected(ConfirmationFailure.PREVIEW_EXPIRED);
        }
        if (!session.dimensionId().equals(level.dimension().identifier())) {
            return ConfirmationResult.rejected(ConfirmationFailure.WRONG_DIMENSION);
        }
        Optional<ServerBuildJobRepository.HutState> hut = repository.findHut(request.chosenHutId());
        if (hut.isEmpty()) {
            return ConfirmationResult.rejected(ConfirmationFailure.HUT_NOT_FOUND);
        }
        ServerBuildJobRepository.HutState selectedHut = hut.orElseThrow();
        if (!selectedHut.ownerId().equals(owner)) {
            return ConfirmationResult.rejected(ConfirmationFailure.HUT_NOT_OWNED);
        }
        if (selectedHut.activeJobId().isPresent()) {
            return ConfirmationResult.rejected(ConfirmationFailure.HUT_ALREADY_BUSY);
        }
        if (selectedHut.containerBinding().isEmpty()) {
            return ConfirmationResult.rejected(ConfirmationFailure.HUT_CHEST_NOT_LINKED);
        }
        if (!permissions.canModify(
                owner,
                session.dimensionId().toString(),
                gridPos(session.anchor()))) {
            return ConfirmationResult.rejected(ConfirmationFailure.PERMISSION_DENIED);
        }
        Optional<dev.ssa.architect.model.TerrainSnapshot> observed = scanner.scan(
                level,
                session.anchor(),
                session.snapshotWidth(),
                session.snapshotDepth());
        if (observed.isEmpty()
                || !session.worldRevision().equals(observed.orElseThrow().revisionFingerprint())) {
            return ConfirmationResult.rejected(ConfirmationFailure.STALE_TERRAIN);
        }
        sessions.remove(owner);
        return ConfirmationResult.confirmed(new ConfirmationAuthority(
                owner,
                request.chosenHutId(),
                session.blueprintHash(),
                session.blueprint(),
                session.rotation(),
                session.surveyTokenHash(),
                session.worldRevision(),
                session.dimensionId(),
                session.anchor()));
    }

    Optional<ConfirmationAuthority> confirmObserved(
            UUID owner,
            UUID sessionId,
            String expectedBlueprintHash,
            UUID hutId,
            String observedWorldRevision,
            long currentRevision) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(expectedBlueprintHash, "expectedBlueprintHash");
        Objects.requireNonNull(hutId, "hutId");
        Objects.requireNonNull(observedWorldRevision, "observedWorldRevision");
        PreviewSession session = sessions.get(owner);
        if (session == null
                || !session.id().equals(sessionId)
                || currentRevision > session.expiryRevision()
                || !session.blueprintHash().equals(expectedBlueprintHash)
                || !session.worldRevision().equals(observedWorldRevision)) {
            return Optional.empty();
        }
        sessions.remove(owner);
        return Optional.of(new ConfirmationAuthority(
                owner,
                hutId,
                session.blueprintHash(),
                session.blueprint(),
                session.rotation(),
                session.surveyTokenHash(),
                session.worldRevision(),
                session.dimensionId(),
                session.anchor()));
    }

    public Optional<PreviewSession> activeSession(UUID owner) {
        return Optional.ofNullable(sessions.get(Objects.requireNonNull(owner, "owner")));
    }

    public void cancel(UUID owner) {
        sessions.remove(Objects.requireNonNull(owner, "owner"));
    }

    private static void requireRotation(int rotation) {
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
            throw new IllegalArgumentException("Rotation must be 0, 90, 180, or 270 degrees");
        }
    }

    private static void requireSnapshotDimensions(int width, int depth) {
        if (width <= 0
                || depth <= 0
                || width > FabricTerrainScanner.MAX_DIMENSION
                || depth > FabricTerrainScanner.MAX_DIMENSION) {
            throw new IllegalArgumentException("Preview snapshot dimensions exceed the V1 terrain bound");
        }
    }

    private static GridPos gridPos(BlockPos position) {
        return new GridPos(position.getX(), position.getY(), position.getZ());
    }

    public record PreviewSession(
            UUID id,
            String blueprintHash,
            Blueprint blueprint,
            UUID owner,
            long expiryRevision,
            String surveyTokenHash,
            String worldRevision,
            Identifier dimensionId,
            BlockPos anchor,
            int snapshotWidth,
            int snapshotDepth,
            int rotation,
            long requestNonce) {
        public PreviewSession {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(blueprintHash, "blueprintHash");
            Objects.requireNonNull(blueprint, "blueprint");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(surveyTokenHash, "surveyTokenHash");
            Objects.requireNonNull(worldRevision, "worldRevision");
            Objects.requireNonNull(dimensionId, "dimensionId");
            anchor = Objects.requireNonNull(anchor, "anchor").immutable();
            if (!blueprint.hash().equals(blueprintHash)) {
                throw new IllegalArgumentException("Preview hash does not match its Blueprint");
            }
            if (expiryRevision < 0 || requestNonce < 0) {
                throw new IllegalArgumentException("Preview revisions/nonces must not be negative");
            }
            if (!surveyTokenHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("surveyTokenHash must be lowercase SHA-256");
            }
            if (worldRevision.isBlank() || worldRevision.length() > 160) {
                throw new IllegalArgumentException("worldRevision must contain 1 to 160 characters");
            }
            requireSnapshotDimensions(snapshotWidth, snapshotDepth);
            requireRotation(rotation);
        }
    }

    public record ConfirmationResult(
            Optional<ConfirmationAuthority> authority,
            Optional<ConfirmationFailure> failure) {
        public ConfirmationResult {
            authority = Objects.requireNonNull(authority, "authority");
            failure = Objects.requireNonNull(failure, "failure");
            if (authority.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException("Confirmation result must contain exactly one outcome");
            }
        }

        public boolean confirmed() {
            return authority.isPresent();
        }

        private static ConfirmationResult confirmed(ConfirmationAuthority authority) {
            return new ConfirmationResult(Optional.of(authority), Optional.empty());
        }

        private static ConfirmationResult rejected(ConfirmationFailure failure) {
            return new ConfirmationResult(Optional.empty(), Optional.of(failure));
        }
    }

    public enum ConfirmationFailure {
        SESSION_MISMATCH("The preview is no longer authoritative. Generate a fresh preview."),
        PREVIEW_EXPIRED("The preview expired. Generate a fresh preview."),
        WRONG_DIMENSION("Return to the preview dimension before confirming."),
        HUT_NOT_FOUND("The selected Builder Hut is unavailable."),
        HUT_NOT_OWNED("Select a Builder Hut that you own."),
        HUT_ALREADY_BUSY("The selected Builder Hut already has an active job."),
        HUT_CHEST_NOT_LINKED("Link a Builder Chest before confirming the build."),
        PERMISSION_DENIED("Build permission is no longer available at the selected site."),
        STALE_TERRAIN("The terrain changed after preview generation. Generate a fresh preview.");

        private final String message;

        ConfirmationFailure(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    public record ConfirmationAuthority(
            UUID owner,
            UUID hutId,
            String blueprintHash,
            Blueprint blueprint,
            int rotation,
            String surveyTokenHash,
            String worldRevision,
            Identifier dimensionId,
            BlockPos anchor) {
        public ConfirmationAuthority {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(hutId, "hutId");
            Objects.requireNonNull(blueprintHash, "blueprintHash");
            Objects.requireNonNull(blueprint, "blueprint");
            Objects.requireNonNull(surveyTokenHash, "surveyTokenHash");
            Objects.requireNonNull(worldRevision, "worldRevision");
            Objects.requireNonNull(dimensionId, "dimensionId");
            anchor = Objects.requireNonNull(anchor, "anchor").immutable();
            if (!blueprint.hash().equals(blueprintHash)) {
                throw new IllegalArgumentException("Confirmation hash does not match its Blueprint");
            }
            requireRotation(rotation);
        }
    }
}
