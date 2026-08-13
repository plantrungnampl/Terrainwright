package dev.ssa.fabric.construction;

import com.mojang.serialization.DynamicOps;
import dev.ssa.construction.operation.BlockStateSnapshot;
import dev.ssa.construction.operation.StackSnapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public final class MinecraftSnapshotAdapter {
    private static final long MAX_NBT_BYTES = 1_048_576;

    private final DynamicOps<Tag> registryOps;

    public MinecraftSnapshotAdapter(HolderLookup.Provider registries) {
        registryOps = Objects.requireNonNull(registries, "registries").createSerializationContext(NbtOps.INSTANCE);
    }

    public StackSnapshot snapshot(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) {
            return StackSnapshot.empty();
        }
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        Tag components = DataComponentPatch.CODEC
                .encodeStart(registryOps, stack.getComponentsPatch())
                .getOrThrow(message -> new IllegalArgumentException("could not encode item components: " + message));
        return StackSnapshot.of(itemId, stack.getCount(), writeTag(components));
    }

    public ItemStack restore(StackSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.count() == 0) {
            return ItemStack.EMPTY;
        }
        Identifier itemId = Identifier.parse(snapshot.itemId());
        Item item = BuiltInRegistries.ITEM.getOptional(itemId)
                .orElseThrow(() -> new IllegalArgumentException("unknown item ID: " + itemId));
        DataComponentPatch components = DataComponentPatch.CODEC
                .parse(registryOps, readTag(snapshot.componentsPayload()))
                .getOrThrow(message -> new IllegalArgumentException("could not decode item components: " + message));
        ItemStack restored = new ItemStack(item, snapshot.count());
        restored.applyComponentsAndValidate(components);
        return restored;
    }

    public BlockStateSnapshot snapshot(BlockState state) {
        Objects.requireNonNull(state, "state");
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Tag encoded = BlockState.CODEC
                .encodeStart(registryOps, state)
                .getOrThrow(message -> new IllegalArgumentException("could not encode block state: " + message));
        return BlockStateSnapshot.of(blockId, writeTag(encoded));
    }

    public BlockState restore(BlockStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        BlockState restored = BlockState.CODEC
                .parse(registryOps, readTag(snapshot.propertiesPayload()))
                .getOrThrow(message -> new IllegalArgumentException("could not decode block state: " + message));
        String restoredId = BuiltInRegistries.BLOCK.getKey(restored.getBlock()).toString();
        if (!snapshot.blockId().equals(restoredId)) {
            throw new IllegalArgumentException(
                    "block snapshot ID " + snapshot.blockId() + " does not match payload ID " + restoredId);
        }
        return restored;
    }

    private static byte[] writeTag(Tag tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                NbtIo.writeAnyTag(tag, output);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("could not serialize snapshot NBT", exception);
        }
    }

    private static Tag readTag(byte[] payload) {
        if (payload.length > MAX_NBT_BYTES) {
            throw new IllegalArgumentException("snapshot NBT exceeds 1 MiB");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            Tag tag = NbtIo.readAnyTag(input, NbtAccounter.create(MAX_NBT_BYTES));
            if (input.available() != 0) {
                throw new IllegalArgumentException("snapshot NBT has trailing bytes");
            }
            return tag;
        } catch (IOException exception) {
            throw new IllegalArgumentException("could not deserialize snapshot NBT", exception);
        }
    }
}
