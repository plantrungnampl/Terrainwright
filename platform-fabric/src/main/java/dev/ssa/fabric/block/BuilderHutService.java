package dev.ssa.fabric.block;

import dev.ssa.common.permission.PermissionPort;
import dev.ssa.fabric.link.BuilderChestLinkService;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import dev.ssa.fabric.permission.FabricPermissionAdapter;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Server-authoritative ownership and explicit chest-binding operations for a Builder Hut. */
public final class BuilderHutService {
    private BuilderHutService() {}

    public static void claimPlacedHut(
            ServerLevel level,
            BlockPos hutPos,
            UUID ownerId) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(hutPos, "hutPos");
        Objects.requireNonNull(ownerId, "ownerId");
        BuilderHutBlockEntity hut = hutAt(level, hutPos);
        BuilderHutBlockEntity.ReferenceState references = hut.references();
        if (references.ownerId().isPresent() && !references.ownerId().orElseThrow().equals(ownerId)) {
            throw new IllegalStateException("Builder Hut is already owned");
        }
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(level);
        ServerBuildJobRepository.HutState durable = repository.findHut(references.hutId())
                .orElseGet(() -> new ServerBuildJobRepository.HutState(
                        references.hutId(),
                        ownerId,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        0));
        if (!durable.ownerId().equals(ownerId)) {
            throw new IllegalStateException("Builder Hut durable owner disagrees with placement owner");
        }
        repository.saveHutState(durable);
        hut.setReferences(referenceState(durable));
        level.getDataStorage().scheduleSave();
    }

    public static BuilderChestLinkService.LinkResult linkChest(
            ServerLevel level,
            BlockPos hutPos,
            UUID hutId,
            UUID ownerId,
            BlockPos chestPos) {
        return linkChest(
                level,
                hutPos,
                hutId,
                ownerId,
                chestPos,
                FabricPermissionAdapter.forServer(level.getServer()));
    }

    public static BuilderChestLinkService.LinkResult linkChest(
            ServerLevel level,
            BlockPos hutPos,
            UUID hutId,
            UUID ownerId,
            BlockPos chestPos,
            PermissionPort permissions) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(hutPos, "hutPos");
        Objects.requireNonNull(hutId, "hutId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(chestPos, "chestPos");
        Objects.requireNonNull(permissions, "permissions");
        BuilderHutBlockEntity hut = hutAt(level, hutPos);
        if (!hut.references().hutId().equals(hutId)
                || !hut.references().ownerId().equals(Optional.of(ownerId))) {
            throw new IllegalStateException("Builder Hut identity or owner does not match");
        }
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(level);
        ServerBuildJobRepository.HutState durable = repository.findHut(hutId)
                .filter(state -> state.ownerId().equals(ownerId))
                .orElseThrow(() -> new IllegalStateException("Builder Hut is not durably owned by this player"));
        BuilderChestLinkService.LinkResult result = new BuilderChestLinkService(permissions)
                .link(level, hutPos, ownerId, chestPos, durable.containerBinding());
        if (!result.linked()) {
            return result;
        }
        ServerBuildJobRepository.HutState updated = new ServerBuildJobRepository.HutState(
                durable.hutId(),
                durable.ownerId(),
                durable.activeJobId(),
                result.binding(),
                durable.builderLifecycle(),
                durable.revision() + 1);
        repository.saveHutState(updated);
        hut.setReferences(referenceState(updated));
        level.getDataStorage().scheduleSave();
        return result;
    }

    private static BuilderHutBlockEntity hutAt(ServerLevel level, BlockPos hutPos) {
        if (!(level.getBlockEntity(hutPos) instanceof BuilderHutBlockEntity hut)) {
            throw new IllegalStateException("Builder Hut block entity is unavailable");
        }
        return hut;
    }

    private static BuilderHutBlockEntity.ReferenceState referenceState(
            ServerBuildJobRepository.HutState state) {
        return new BuilderHutBlockEntity.ReferenceState(
                state.hutId(),
                Optional.of(state.ownerId()),
                state.activeJobId(),
                state.builderLifecycle().map(lifecycle -> lifecycle.builderId()),
                state.containerBinding().map(binding -> binding.revision()));
    }
}
