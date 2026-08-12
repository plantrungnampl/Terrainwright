package dev.ssa.fabric.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class BuilderHutBlockEntityTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void stableReferencesRoundTripWithoutEmbeddingBuildJob() {
        BuilderHutBlockEntity.ReferenceState expected = new BuilderHutBlockEntity.ReferenceState(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                Optional.of(UUID.fromString("22222222-2222-2222-2222-222222222222")),
                Optional.of("33333333-3333-3333-3333-333333333333"),
                Optional.of(UUID.fromString("44444444-4444-4444-4444-444444444444")),
                Optional.of(7L));
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, RegistryAccess.EMPTY);

        BuilderHutBlockEntity.writeReferences(output, expected);
        CompoundTag tag = output.buildResult();
        BuilderHutBlockEntity.ReferenceState actual = BuilderHutBlockEntity.readReferences(
                TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, tag));

        assertEquals(expected, actual);
        assertFalse(tag.contains("BuildJob"));
        assertFalse(tag.contains("CompletedTaskIds"));
        assertFalse(tag.contains("BlockJournal"));
    }
}
