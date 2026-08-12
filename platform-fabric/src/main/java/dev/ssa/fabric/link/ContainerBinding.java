package dev.ssa.fabric.link;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

public record ContainerBinding(
        Identifier dimensionId,
        BlockPos primaryPos,
        Optional<BlockPos> partnerPos,
        UUID inventoryId,
        long revision,
        int formatVersion) {
    public static final int CURRENT_FORMAT_VERSION = 1;
    private static final Comparator<BlockPos> POSITION_ORDER = Comparator.comparingInt((BlockPos pos) -> pos.getX())
            .thenComparingInt(pos -> pos.getY())
            .thenComparingInt(pos -> pos.getZ());

    public ContainerBinding {
        Objects.requireNonNull(dimensionId, "dimensionId");
        primaryPos = Objects.requireNonNull(primaryPos, "primaryPos").immutable();
        partnerPos = Objects.requireNonNull(partnerPos, "partnerPos").map(BlockPos::immutable);
        Objects.requireNonNull(inventoryId, "inventoryId");
        if (revision < 1) {
            throw new IllegalArgumentException("Container binding revision must be positive");
        }
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported ContainerBinding format version: " + formatVersion);
        }
        if (partnerPos.isPresent()
                && POSITION_ORDER.compare(primaryPos, partnerPos.orElseThrow()) >= 0) {
            throw new IllegalArgumentException("Container primary must be the canonical first position");
        }
        if (partnerPos.isPresent() && !isAdjacentHorizontal(primaryPos, partnerPos.orElseThrow())) {
            throw new IllegalArgumentException("Container partner must be one horizontal block away");
        }
        UUID expectedIdentity = identityFor(dimensionId, primaryPos, partnerPos);
        if (!expectedIdentity.equals(inventoryId)) {
            throw new IllegalArgumentException("Container inventory identity does not match its topology");
        }
    }

    public static ContainerBinding resolve(
            Identifier dimensionId,
            BlockPos proposedPos,
            Optional<BlockPos> proposedPartner,
            Optional<ContainerBinding> current) {
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(proposedPos, "proposedPos");
        proposedPartner = Objects.requireNonNull(proposedPartner, "proposedPartner");
        current = Objects.requireNonNull(current, "current");

        BlockPos primary = proposedPos.immutable();
        Optional<BlockPos> partner = proposedPartner.map(BlockPos::immutable);
        if (partner.isPresent() && POSITION_ORDER.compare(primary, partner.orElseThrow()) > 0) {
            BlockPos originalPrimary = primary;
            primary = partner.orElseThrow();
            partner = Optional.of(originalPrimary);
        }
        if (current.isPresent() && current.orElseThrow().matchesTopology(dimensionId, primary, partner)) {
            return current.orElseThrow();
        }
        long revision = current.map(ContainerBinding::revision).orElse(0L) + 1;
        return new ContainerBinding(
                dimensionId,
                primary,
                partner,
                identityFor(dimensionId, primary, partner),
                revision,
                CURRENT_FORMAT_VERSION);
    }

    public boolean matchesTopology(
            Identifier observedDimension,
            BlockPos observedPos,
            Optional<BlockPos> observedPartner) {
        Objects.requireNonNull(observedDimension, "observedDimension");
        Objects.requireNonNull(observedPos, "observedPos");
        observedPartner = Objects.requireNonNull(observedPartner, "observedPartner");
        BlockPos observedPrimary = observedPos.immutable();
        Optional<BlockPos> canonicalPartner = observedPartner.map(BlockPos::immutable);
        if (canonicalPartner.isPresent()
                && POSITION_ORDER.compare(observedPrimary, canonicalPartner.orElseThrow()) > 0) {
            BlockPos originalPrimary = observedPrimary;
            observedPrimary = canonicalPartner.orElseThrow();
            canonicalPartner = Optional.of(originalPrimary);
        }
        return dimensionId.equals(observedDimension)
                && primaryPos.equals(observedPrimary)
                && partnerPos.equals(canonicalPartner);
    }

    public static UUID identityFor(
            Identifier dimensionId,
            BlockPos primaryPos,
            Optional<BlockPos> partnerPos) {
        String partner = partnerPos
                .map(position -> position.getX() + "," + position.getY() + "," + position.getZ())
                .orElse("single");
        String identity = "ssa:container:"
                + dimensionId + ":"
                + primaryPos.getX() + "," + primaryPos.getY() + "," + primaryPos.getZ() + ":"
                + partner;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isAdjacentHorizontal(BlockPos first, BlockPos second) {
        long dx = Math.abs((long) first.getX() - second.getX());
        long dy = Math.abs((long) first.getY() - second.getY());
        long dz = Math.abs((long) first.getZ() - second.getZ());
        return dy == 0 && dx + dz == 1;
    }
}
