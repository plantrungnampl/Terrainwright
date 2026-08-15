package dev.ssa.fabric.link;

import dev.ssa.architect.model.GridPos;
import dev.ssa.common.permission.PermissionPort;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

public final class BuilderChestLinkService {
    public static final long MAX_DISTANCE_SQUARED = 256;

    private final PermissionPort permissions;
    private final Optional<UUID> transferOwner;

    public BuilderChestLinkService(PermissionPort permissions) {
        this(permissions, Optional.empty());
    }

    public BuilderChestLinkService(PermissionPort permissions, UUID transferOwner) {
        this(permissions, Optional.of(Objects.requireNonNull(transferOwner, "transferOwner")));
    }

    private BuilderChestLinkService(PermissionPort permissions, Optional<UUID> transferOwner) {
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.transferOwner = Objects.requireNonNull(transferOwner, "transferOwner");
    }

    public LinkResult link(
            ServerLevel level,
            BlockPos hutPos,
            UUID ownerId,
            BlockPos proposedChestPos,
            Optional<ContainerBinding> current) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(hutPos, "hutPos");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(proposedChestPos, "proposedChestPos");
        current = Objects.requireNonNull(current, "current");

        if (!level.isLoaded(proposedChestPos)) {
            return LinkResult.rejected(LinkFailure.CHUNK_UNLOADED, current);
        }
        Optional<Topology> topology = topologyAt(level, proposedChestPos);
        if (topology.isEmpty()) {
            return LinkResult.rejected(LinkFailure.NOT_VANILLA_CHEST, current);
        }
        Topology resolved = topology.orElseThrow();
        if (distanceSquared(hutPos, resolved.primary()) > MAX_DISTANCE_SQUARED) {
            return LinkResult.rejected(LinkFailure.TOO_FAR, current);
        }
        if (!canModify(level, ownerId, resolved.primary())
                || resolved.partner().stream()
                        .anyMatch(partner -> !canModify(level, ownerId, partner))) {
            return LinkResult.rejected(LinkFailure.PERMISSION_DENIED, current);
        }

        Identifier dimension = level.dimension().identifier();
        ContainerBinding binding = ContainerBinding.resolve(
                dimension, resolved.primary(), resolved.partner(), current);
        return LinkResult.linked(binding);
    }

    public boolean isTransferEligible(ServerLevel level, ContainerBinding binding) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(binding, "binding");
        Optional<Topology> observed = topologyAt(level, binding.primaryPos());
        if (observed.isEmpty()
                || !binding.matchesTopology(
                        level.dimension().identifier(),
                        observed.orElseThrow().primary(),
                        observed.orElseThrow().partner())) {
            return false;
        }
        if (transferOwner.isEmpty()) {
            return true;
        }
        UUID owner = transferOwner.orElseThrow();
        Topology resolved = observed.orElseThrow();
        return canModify(level, owner, resolved.primary())
                && resolved.partner().stream().allMatch(partner -> canModify(level, owner, partner));
    }

    private boolean canModify(ServerLevel level, UUID ownerId, BlockPos position) {
        return permissions.canModify(
                ownerId,
                level.dimension().identifier().toString(),
                gridPos(position));
    }

    private static Optional<Topology> topologyAt(ServerLevel level, BlockPos proposedPos) {
        if (!level.isLoaded(proposedPos)) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(proposedPos);
        if (!state.is(Blocks.CHEST)
                || !(level.getBlockEntity(proposedPos) instanceof ChestBlockEntity)) {
            return Optional.empty();
        }
        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.SINGLE) {
            return Optional.of(new Topology(proposedPos.immutable(), Optional.empty()));
        }

        BlockPos partnerPos = ChestBlock.getConnectedBlockPos(proposedPos, state);
        if (!level.isLoaded(partnerPos)) {
            return Optional.empty();
        }
        BlockState partnerState = level.getBlockState(partnerPos);
        if (!partnerState.is(Blocks.CHEST)
                || !(level.getBlockEntity(partnerPos) instanceof ChestBlockEntity)
                || partnerState.getValue(ChestBlock.TYPE) != type.getOpposite()
                || partnerState.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)
                || !ChestBlock.getConnectedBlockPos(partnerPos, partnerState).equals(proposedPos)) {
            return Optional.empty();
        }

        ContainerBinding canonical = ContainerBinding.resolve(
                level.dimension().identifier(),
                proposedPos,
                Optional.of(partnerPos),
                Optional.empty());
        return Optional.of(new Topology(canonical.primaryPos(), canonical.partnerPos()));
    }

    private static long distanceSquared(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dy = (long) first.getY() - second.getY();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static GridPos gridPos(BlockPos position) {
        return new GridPos(position.getX(), position.getY(), position.getZ());
    }

    private record Topology(BlockPos primary, Optional<BlockPos> partner) {}

    public record LinkResult(
            boolean linked,
            Optional<ContainerBinding> binding,
            Optional<LinkFailure> failure) {
        public LinkResult {
            binding = Objects.requireNonNull(binding, "binding");
            failure = Objects.requireNonNull(failure, "failure");
            if (linked == failure.isPresent()) {
                throw new IllegalArgumentException("Link result must be either linked or rejected");
            }
            if (linked && binding.isEmpty()) {
                throw new IllegalArgumentException("A successful link requires a binding");
            }
        }

        private static LinkResult linked(ContainerBinding binding) {
            return new LinkResult(true, Optional.of(binding), Optional.empty());
        }

        private static LinkResult rejected(
                LinkFailure failure,
                Optional<ContainerBinding> current) {
            return new LinkResult(false, current, Optional.of(failure));
        }
    }

    public enum LinkFailure {
        NOT_VANILLA_CHEST,
        TOO_FAR,
        PERMISSION_DENIED,
        CHUNK_UNLOADED
    }
}
