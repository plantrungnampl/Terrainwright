package dev.ssa.architect.style;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.ArchitectEngine;
import dev.ssa.architect.ArchitectEngine.GenerationResult;
import dev.ssa.architect.blueprint.Blueprint;
import dev.ssa.architect.blueprint.BlueprintBlock;
import dev.ssa.architect.material.BlockCapability;
import dev.ssa.architect.material.BlockCapabilityRegistry;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.EntrancePreference;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.TerrainSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class StyleGalleryTest {
    private static final List<StylePack> STYLES = List.of(
            new MedievalStyle(),
            new JapaneseStyle(),
            new ModernStyle());

    @Test
    void smallFlatAndMediumGentleFixturesKeepDistinctStyleIdentity() {
        for (GalleryFixture fixture : List.of(
                new GalleryFixture("small-flat", 9, 11, 1, flat(13, 15), 101L),
                new GalleryFixture("medium-gentle", 15, 19, 2, gentleSlope(19, 23), 202L))) {
            List<Blueprint> gallery = STYLES.stream()
                    .map(style -> generate(style, fixture))
                    .toList();

            assertEquals(3, gallery.stream().map(StyleGalleryTest::geometrySignature).distinct().count(),
                    fixture.name() + " geometry identity");
            assertEquals(3, gallery.stream().map(StyleGalleryTest::materialSignature).distinct().count(),
                    fixture.name() + " material identity");
            if (fixture.name().contains("gentle")) {
                assertTrue(gallery.stream().allMatch(blueprint -> !blueprint.terrainPlan().changes().isEmpty()),
                        fixture.name() + " did not adapt the slope");
            }
        }
    }

    private static Blueprint generate(StylePack style, GalleryFixture fixture) {
        HouseRequirements requirements = new HouseRequirements(
                style.id(),
                fixture.width(),
                fixture.depth(),
                fixture.floors(),
                fixture.floors() == 1 ? 1 : 2,
                true,
                true,
                fixture.floors() > 1,
                false,
                EntrancePreference.AUTO,
                fixture.seed());
        GenerationResult result = new ArchitectEngine().generate(
                requirements,
                fixture.terrain(),
                style,
                registry(style));
        Blueprint blueprint = assertInstanceOf(
                GenerationResult.Success.class,
                result,
                style.id() + " " + fixture.name() + " " + result)
                .blueprint();
        assertTrue(blueprint.validation().isValid(), style.id() + " " + fixture.name());
        for (MaterialRole identityRole : List.of(
                MaterialRole.FOUNDATION_STONE,
                MaterialRole.STRUCTURAL_PRIMARY,
                MaterialRole.WALL_PRIMARY,
                MaterialRole.ROOF_PRIMARY)) {
            NamespacedId expected = style.fallbackPalette().get(identityRole).getFirst().state().blockId();
            assertTrue(blueprint.blocks().stream().anyMatch(block ->
                            block.materialRole() == identityRole
                                    && block.placementState().blockId().equals(expected)),
                    style.id() + " missing " + identityRole + " in " + fixture.name());
        }
        return blueprint;
    }

    private static Set<GridPos> geometrySignature(Blueprint blueprint) {
        return blueprint.blocks().stream()
                .map(BlueprintBlock::relativePosition)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> materialSignature(Blueprint blueprint) {
        return blueprint.blocks().stream()
                .map(block -> block.materialRole() + "=" + block.placementState().blockId())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static TerrainSnapshot flat(int width, int depth) {
        return terrain(width, depth, false);
    }

    private static TerrainSnapshot gentleSlope(int width, int depth) {
        return terrain(width, depth, true);
    }

    private static TerrainSnapshot terrain(int width, int depth, boolean slope) {
        List<Integer> heights = new ArrayList<>(width * depth);
        List<NamespacedId> materials = new ArrayList<>(width * depth);
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                heights.add(slope && x >= width / 2 ? 11 : 10);
                materials.add(NamespacedId.parse("minecraft:grass_block"));
            }
        }
        return new TerrainSnapshot(
                new GridPos(0, 10, 0),
                width,
                depth,
                10,
                slope ? 11 : 10,
                heights,
                materials,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                new TerrainSnapshot.SlopeMetrics(slope ? 0.1 : 0, slope ? 1 : 0),
                Map.of(),
                (slope ? "gentle-" : "flat-") + width + "x" + depth);
    }

    private static BlockCapabilityRegistry registry(StylePack style) {
        Map<NamespacedId, Set<BlockCapability>> entries = new HashMap<>();
        style.fallbackPalette().values().forEach(candidates -> candidates.forEach(candidate -> entries
                .computeIfAbsent(candidate.state().blockId(), ignored -> new HashSet<>())
                .addAll(candidate.requiredCapabilities())));
        return BlockCapabilityRegistry.of(entries);
    }

    private record GalleryFixture(
            String name,
            int width,
            int depth,
            int floors,
            TerrainSnapshot terrain,
            long seed) {
    }
}
