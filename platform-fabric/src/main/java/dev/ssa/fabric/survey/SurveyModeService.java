package dev.ssa.fabric.survey;

import dev.ssa.architect.model.GridPos;
import dev.ssa.common.permission.PermissionPort;
import dev.ssa.fabric.block.ModBlocks;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class SurveyModeService {
    public static final int MAX_RANGE = 64;
    public static final long MAX_RANGE_SQUARED = (long) MAX_RANGE * MAX_RANGE;
    public static final long DEFAULT_SESSION_TTL = 20L * 30L;
    public static final long DEFAULT_TOKEN_TTL = 20L * 30L;

    private final PermissionPort permissions;
    private final SecureRandom random;
    private final long sessionTtl;
    private final long tokenTtl;
    private final Map<UUID, SurveySession> sessions = new HashMap<>();
    private final Map<String, SiteToken> tokens = new HashMap<>();

    public SurveyModeService(PermissionPort permissions) {
        this(permissions, new SecureRandom(), DEFAULT_SESSION_TTL, DEFAULT_TOKEN_TTL);
    }

    SurveyModeService(
            PermissionPort permissions,
            SecureRandom random,
            long sessionTtl,
            long tokenTtl) {
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.random = Objects.requireNonNull(random, "random");
        if (sessionTtl <= 0 || tokenTtl <= 0) {
            throw new IllegalArgumentException("Survey time-to-live values must be positive");
        }
        this.sessionTtl = sessionTtl;
        this.tokenTtl = tokenTtl;
    }

    public StartResult start(ServerPlayer player, BlockPos architectTablePos, long currentRevision) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(architectTablePos, "architectTablePos");
        if (currentRevision < 0) {
            throw new IllegalArgumentException("currentRevision must not be negative");
        }
        ServerLevel level = player.level();
        UUID owner = player.getUUID();
        purgeExpiredTokens(currentRevision);
        revokeTokens(owner);
        if (!level.isLoaded(architectTablePos)
                || !level.getBlockState(architectTablePos).is(ModBlocks.ARCHITECT_TABLE)) {
            cancel(owner);
            return StartResult.rejected(Failure.NOT_ARCHITECT_TABLE);
        }
        if (distanceSquared(player.position(), Vec3.atCenterOf(architectTablePos)) > MAX_RANGE_SQUARED) {
            cancel(owner);
            return StartResult.rejected(Failure.PLAYER_OUT_OF_RANGE);
        }
        if (!permissions.canModify(owner, gridPos(architectTablePos))) {
            cancel(owner);
            return StartResult.rejected(Failure.PERMISSION_DENIED);
        }
        SurveySession session = new SurveySession(
                owner,
                level.dimension().identifier(),
                architectTablePos.immutable(),
                currentRevision + sessionTtl);
        sessions.put(owner, session);
        return StartResult.started(session);
    }

    public SelectionResult selectSite(
            ServerPlayer player,
            BlockPos anchor,
            String worldRevision,
            long currentRevision) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(anchor, "anchor");
        requireRevision(worldRevision);
        if (currentRevision < 0) {
            throw new IllegalArgumentException("currentRevision must not be negative");
        }
        UUID owner = player.getUUID();
        SurveySession session = sessions.get(owner);
        if (session == null) {
            return SelectionResult.rejected(Failure.NO_ACTIVE_SESSION);
        }
        if (currentRevision > session.expiresAtRevision()) {
            cancel(owner);
            return SelectionResult.rejected(Failure.EXPIRED);
        }
        ServerLevel level = player.level();
        if (!session.dimensionId().equals(level.dimension().identifier())) {
            cancel(owner);
            return SelectionResult.rejected(Failure.WRONG_DIMENSION);
        }
        if (!level.isLoaded(session.architectTablePos())
                || !level.getBlockState(session.architectTablePos()).is(ModBlocks.ARCHITECT_TABLE)) {
            cancel(owner);
            return SelectionResult.rejected(Failure.NOT_ARCHITECT_TABLE);
        }
        if (distanceSquared(player.position(), Vec3.atCenterOf(session.architectTablePos()))
                > MAX_RANGE_SQUARED) {
            cancel(owner);
            return SelectionResult.rejected(Failure.PLAYER_OUT_OF_RANGE);
        }
        if (distanceSquared(session.architectTablePos(), anchor) > MAX_RANGE_SQUARED) {
            return SelectionResult.rejected(Failure.ANCHOR_OUT_OF_RANGE);
        }
        if (!level.isLoaded(anchor) || !level.isLoaded(anchor.above())) {
            return SelectionResult.rejected(Failure.CHUNK_UNLOADED);
        }
        if (!permissions.canModify(owner, gridPos(anchor))) {
            return SelectionResult.rejected(Failure.PERMISSION_DENIED);
        }
        if (!level.getBlockState(anchor).isFaceSturdy(level, anchor, Direction.UP)
                || !level.getBlockState(anchor.above()).canBeReplaced()) {
            return SelectionResult.rejected(Failure.NOT_TOP_SURFACE);
        }

        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(MAX_RANGE));
        BlockHitResult hit = level.clip(new ClipContext(
                eye,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player));
        if (hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(anchor)
                || hit.getDirection() != Direction.UP) {
            return SelectionResult.rejected(Failure.RAY_TRACE_MISMATCH);
        }

        String rawToken = randomToken();
        String tokenHash = hashToken(rawToken);
        SiteToken token = new SiteToken(
                owner,
                session.dimensionId(),
                session.architectTablePos(),
                anchor,
                tokenHash,
                worldRevision,
                currentRevision + tokenTtl);
        tokens.put(tokenHash, token);
        sessions.remove(owner);
        return SelectionResult.accepted(new IssuedSiteToken(rawToken, token));
    }

    public Optional<SiteToken> validateToken(
            UUID owner,
            String rawToken,
            long currentRevision) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(rawToken, "rawToken");
        purgeExpiredTokens(currentRevision);
        SiteToken token = tokens.get(hashToken(rawToken));
        if (token == null || !token.ownerId().equals(owner) || currentRevision > token.expiresAtRevision()) {
            return Optional.empty();
        }
        return Optional.of(token);
    }

    public Optional<SiteToken> consumeToken(
            UUID owner,
            String rawToken,
            long currentRevision) {
        Optional<SiteToken> token = validateToken(owner, rawToken, currentRevision);
        token.ifPresent(value -> tokens.remove(value.tokenHash()));
        return token;
    }

    public void cancel(UUID owner) {
        UUID trustedOwner = Objects.requireNonNull(owner, "owner");
        sessions.remove(trustedOwner);
        revokeTokens(trustedOwner);
    }

    public Optional<SurveySession> activeSession(UUID owner) {
        return Optional.ofNullable(sessions.get(Objects.requireNonNull(owner, "owner")));
    }

    public static String hashToken(String rawToken) {
        Objects.requireNonNull(rawToken, "rawToken");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void revokeTokens(UUID owner) {
        tokens.values().removeIf(token -> token.ownerId().equals(owner));
    }

    private void purgeExpiredTokens(long currentRevision) {
        tokens.values().removeIf(token -> currentRevision > token.expiresAtRevision());
    }

    private static long distanceSquared(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dy = (long) first.getY() - second.getY();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double distanceSquared(Vec3 first, Vec3 second) {
        return first.distanceToSqr(second);
    }

    private static GridPos gridPos(BlockPos position) {
        return new GridPos(position.getX(), position.getY(), position.getZ());
    }

    private static void requireRevision(String revision) {
        Objects.requireNonNull(revision, "worldRevision");
        if (revision.isBlank() || revision.length() > 160) {
            throw new IllegalArgumentException("worldRevision must contain 1 to 160 characters");
        }
    }

    public record SurveySession(
            UUID ownerId,
            Identifier dimensionId,
            BlockPos architectTablePos,
            long expiresAtRevision) {
        public SurveySession {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(dimensionId, "dimensionId");
            architectTablePos = Objects.requireNonNull(architectTablePos, "architectTablePos").immutable();
            if (expiresAtRevision < 0) {
                throw new IllegalArgumentException("expiresAtRevision must not be negative");
            }
        }
    }

    public record SiteToken(
            UUID ownerId,
            Identifier dimensionId,
            BlockPos architectTablePos,
            BlockPos anchor,
            String tokenHash,
            String worldRevision,
            long expiresAtRevision) {
        public SiteToken {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(dimensionId, "dimensionId");
            architectTablePos = Objects.requireNonNull(architectTablePos, "architectTablePos").immutable();
            anchor = Objects.requireNonNull(anchor, "anchor").immutable();
            Objects.requireNonNull(tokenHash, "tokenHash");
            if (!tokenHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("tokenHash must be lowercase SHA-256");
            }
            requireRevision(worldRevision);
            if (expiresAtRevision < 0) {
                throw new IllegalArgumentException("expiresAtRevision must not be negative");
            }
        }
    }

    public record IssuedSiteToken(String rawToken, SiteToken token) {
        public IssuedSiteToken {
            Objects.requireNonNull(rawToken, "rawToken");
            Objects.requireNonNull(token, "token");
            if (!hashToken(rawToken).equals(token.tokenHash())) {
                throw new IllegalArgumentException("Raw Survey token does not match its hash");
            }
        }
    }

    public record StartResult(Optional<SurveySession> session, Optional<Failure> failure) {
        public StartResult {
            session = Objects.requireNonNull(session, "session");
            failure = Objects.requireNonNull(failure, "failure");
            if (session.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException("Survey start must be accepted or rejected");
            }
        }

        private static StartResult started(SurveySession session) {
            return new StartResult(Optional.of(session), Optional.empty());
        }

        private static StartResult rejected(Failure failure) {
            return new StartResult(Optional.empty(), Optional.of(failure));
        }
    }

    public record SelectionResult(Optional<IssuedSiteToken> token, Optional<Failure> failure) {
        public SelectionResult {
            token = Objects.requireNonNull(token, "token");
            failure = Objects.requireNonNull(failure, "failure");
            if (token.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException("Site selection must be accepted or rejected");
            }
        }

        private static SelectionResult accepted(IssuedSiteToken token) {
            return new SelectionResult(Optional.of(token), Optional.empty());
        }

        private static SelectionResult rejected(Failure failure) {
            return new SelectionResult(Optional.empty(), Optional.of(failure));
        }
    }

    public enum Failure {
        NO_ACTIVE_SESSION,
        NOT_ARCHITECT_TABLE,
        PLAYER_OUT_OF_RANGE,
        ANCHOR_OUT_OF_RANGE,
        WRONG_DIMENSION,
        PERMISSION_DENIED,
        CHUNK_UNLOADED,
        NOT_TOP_SURFACE,
        RAY_TRACE_MISMATCH,
        EXPIRED
    }
}
