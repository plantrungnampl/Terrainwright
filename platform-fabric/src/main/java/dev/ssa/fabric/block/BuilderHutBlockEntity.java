package dev.ssa.fabric.block;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public final class BuilderHutBlockEntity extends BlockEntity {
    private static final String HUT_ID = "hut_id";
    private static final String OWNER_ID = "owner_id";
    private static final String ACTIVE_JOB_ID = "active_job_id";
    private static final String BUILDER_ID = "builder_id";
    private static final String BINDING_REVISION = "binding_revision";

    private ReferenceState references;

    public BuilderHutBlockEntity(BlockPos position, BlockState state) {
        super(ModBlockEntityTypes.BUILDER_HUT, position, state);
        references = ReferenceState.unowned(UUID.randomUUID());
    }

    public ReferenceState references() {
        return references;
    }

    public void setReferences(ReferenceState references) {
        this.references = Objects.requireNonNull(references, "references");
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        references = readReferences(input);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        writeReferences(output, references);
    }

    static ReferenceState readReferences(ValueInput input) {
        UUID hutId = parseRequiredUuid(input, HUT_ID);
        Optional<UUID> ownerId = parseOptionalUuid(input, OWNER_ID);
        Optional<String> activeJobId = input.getString(ACTIVE_JOB_ID);
        Optional<UUID> builderId = parseOptionalUuid(input, BUILDER_ID);
        Optional<Long> bindingRevision = input.getLong(BINDING_REVISION);
        return new ReferenceState(hutId, ownerId, activeJobId, builderId, bindingRevision);
    }

    static void writeReferences(ValueOutput output, ReferenceState state) {
        output.putString(HUT_ID, state.hutId().toString());
        state.ownerId().ifPresent(value -> output.putString(OWNER_ID, value.toString()));
        state.activeJobId().ifPresent(value -> output.putString(ACTIVE_JOB_ID, value));
        state.builderId().ifPresent(value -> output.putString(BUILDER_ID, value.toString()));
        state.bindingRevision().ifPresent(value -> output.putLong(BINDING_REVISION, value));
    }

    private static UUID parseRequiredUuid(ValueInput input, String key) {
        return UUID.fromString(input.getString(key)
                .orElseThrow(() -> new IllegalStateException("Missing Hut reference: " + key)));
    }

    private static Optional<UUID> parseOptionalUuid(ValueInput input, String key) {
        return input.getString(key).map(UUID::fromString);
    }

    public record ReferenceState(
            UUID hutId,
            Optional<UUID> ownerId,
            Optional<String> activeJobId,
            Optional<UUID> builderId,
            Optional<Long> bindingRevision) {
        public ReferenceState {
            Objects.requireNonNull(hutId, "hutId");
            ownerId = Objects.requireNonNull(ownerId, "ownerId");
            activeJobId = Objects.requireNonNull(activeJobId, "activeJobId");
            builderId = Objects.requireNonNull(builderId, "builderId");
            bindingRevision = Objects.requireNonNull(bindingRevision, "bindingRevision");
            activeJobId.ifPresent(value -> {
                if (value.isBlank() || value.length() > 160) {
                    throw new IllegalArgumentException("activeJobId must contain 1 to 160 characters");
                }
            });
            bindingRevision.ifPresent(value -> {
                if (value < 0) {
                    throw new IllegalArgumentException("bindingRevision must not be negative");
                }
            });
        }

        public static ReferenceState unowned(UUID hutId) {
            return new ReferenceState(
                    hutId, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
    }
}
