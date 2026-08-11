package dev.ssa.architect.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.style.MedievalStyle;
import dev.ssa.architect.style.StylePack;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class PaletteResolverTest {
    @Test
    void exposesExactlyTheCanonicalCapabilityVocabulary() {
        assertEquals(
                Set.of(
                        "FULL_CUBE", "STAIR", "SLAB", "PANE", "DOOR", "TRAPDOOR", "FENCE",
                        "FENCE_OR_WALL", "LIGHT_SOURCE", "ORIENTABLE_AXIS", "HORIZONTAL_FACING"),
                java.util.Arrays.stream(BlockCapability.values())
                        .map(Enum::name)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        assertEquals(BlockCapability.FULL_CUBE, BlockCapability.parse("FULL_CUBE"));
        assertThrows(IllegalArgumentException.class, () -> BlockCapability.parse("FULL_BLOCK"));
    }

    @Test
    void skipsMissingAndIncompatibleOverridesThenUsesFirstCompatibleCandidate() {
        StylePack.PaletteCandidate missing = candidate("example:missing_roof", "east");
        StylePack.PaletteCandidate incompatible = candidate("example:decorative_roof", "west");
        StylePack.PaletteCandidate compatible = candidate("example:roof_stairs", "south");
        List<StylePack.PaletteCandidate> roofOverrides = new ArrayList<>(List.of(
                missing, incompatible, compatible));
        PaletteResolver resolver = new PaletteResolver(
                new MedievalStyle(),
                Map.of(MaterialRole.ROOF_PRIMARY, roofOverrides));
        roofOverrides.clear();

        BlockCapabilityRegistry registry = BlockCapabilityRegistry.of(Map.of(
                NamespacedId.parse("example:decorative_roof"), EnumSet.of(BlockCapability.FULL_CUBE),
                NamespacedId.parse("example:roof_stairs"),
                EnumSet.of(BlockCapability.STAIR, BlockCapability.HORIZONTAL_FACING)));

        BlockStateSpec resolved = resolver.resolve(MaterialRole.ROOF_PRIMARY, registry).orElseThrow();
        assertEquals(NamespacedId.parse("example:roof_stairs"), resolved.blockId());
        assertEquals("south", resolved.properties().get("facing"));
    }

    @Test
    void missingModdedRoofFallsBackToVanillaCompatibleRoof() {
        PaletteResolver resolver = new PaletteResolver(
                new MedievalStyle(),
                Map.of(MaterialRole.ROOF_PRIMARY, List.of(candidate("example:missing_roof", "east"))));
        BlockCapabilityRegistry registry = BlockCapabilityRegistry.of(Map.of(
                NamespacedId.parse("minecraft:dark_oak_stairs"),
                EnumSet.of(BlockCapability.STAIR, BlockCapability.HORIZONTAL_FACING)));

        BlockStateSpec resolved = resolver.resolve(MaterialRole.ROOF_PRIMARY, registry).orElseThrow();
        assertEquals(NamespacedId.parse("minecraft:dark_oak_stairs"), resolved.blockId());
    }

    @Test
    void overrideCannotLowerTheStylesRequiredCapabilities() {
        StylePack.PaletteCandidate untrusted = new StylePack.PaletteCandidate(
                new BlockStateSpec(NamespacedId.parse("example:not_a_stair"), Map.of()),
                Set.of());
        PaletteResolver resolver = new PaletteResolver(
                new MedievalStyle(),
                Map.of(MaterialRole.ROOF_PRIMARY, List.of(untrusted)));
        BlockCapabilityRegistry registry = BlockCapabilityRegistry.of(Map.of(
                NamespacedId.parse("example:not_a_stair"), Set.of(),
                NamespacedId.parse("minecraft:dark_oak_stairs"),
                Set.of(BlockCapability.STAIR, BlockCapability.HORIZONTAL_FACING)));

        assertEquals(
                NamespacedId.parse("minecraft:dark_oak_stairs"),
                resolver.resolve(MaterialRole.ROOF_PRIMARY, registry).orElseThrow().blockId());
    }

    @Test
    void rejectsOverrideWithUnsupportedStatePropertiesOrValues() {
        StylePack.PaletteCandidate invalidFacing = candidate("example:invalid_facing", "not_real");
        StylePack.PaletteCandidate invalidProperty = new StylePack.PaletteCandidate(
                new BlockStateSpec(
                        NamespacedId.parse("example:invalid_property"),
                        Map.of("axis", "y")),
                Set.of(BlockCapability.STAIR, BlockCapability.HORIZONTAL_FACING));
        PaletteResolver resolver = new PaletteResolver(
                new MedievalStyle(),
                Map.of(MaterialRole.ROOF_PRIMARY, List.of(invalidFacing, invalidProperty)));
        Set<BlockCapability> stairCapabilities =
                Set.of(BlockCapability.STAIR, BlockCapability.HORIZONTAL_FACING);
        BlockCapabilityRegistry registry = BlockCapabilityRegistry.of(Map.of(
                NamespacedId.parse("example:invalid_facing"), stairCapabilities,
                NamespacedId.parse("example:invalid_property"), stairCapabilities,
                NamespacedId.parse("minecraft:dark_oak_stairs"), stairCapabilities));

        assertEquals(
                NamespacedId.parse("minecraft:dark_oak_stairs"),
                resolver.resolve(MaterialRole.ROOF_PRIMARY, registry).orElseThrow().blockId());
    }

    @Test
    void returnsEmptyWhenNoCompatibleOverrideOrFallbackExists() {
        PaletteResolver resolver = new PaletteResolver(new MedievalStyle());
        assertTrue(resolver.resolve(
                MaterialRole.ROOF_PRIMARY,
                BlockCapabilityRegistry.of(Map.of())).isEmpty());
    }

    @Test
    void registrySnapshotIsDetachedFromMutableSources() {
        NamespacedId blockId = NamespacedId.parse("example:block");
        Set<BlockCapability> capabilities = new HashSet<>(Set.of(BlockCapability.FULL_CUBE));
        Map<NamespacedId, Set<BlockCapability>> entries = new HashMap<>();
        entries.put(blockId, capabilities);
        BlockCapabilityRegistry registry = BlockCapabilityRegistry.of(entries);

        capabilities.clear();
        entries.clear();

        assertEquals(Set.of(BlockCapability.FULL_CUBE), registry.capabilities(blockId).orElseThrow());
        assertThrows(UnsupportedOperationException.class,
                () -> registry.capabilities(blockId).orElseThrow().clear());
    }

    private static StylePack.PaletteCandidate candidate(String blockId, String facing) {
        return new StylePack.PaletteCandidate(
                new BlockStateSpec(
                        NamespacedId.parse(blockId),
                        Map.of("facing", facing)),
                EnumSet.of(BlockCapability.STAIR, BlockCapability.HORIZONTAL_FACING));
    }
}
