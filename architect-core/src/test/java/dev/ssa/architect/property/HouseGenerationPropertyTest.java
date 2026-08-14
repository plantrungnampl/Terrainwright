package dev.ssa.architect.property;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.ArchitectEngine;
import dev.ssa.architect.ArchitectEngine.GenerationResult;
import dev.ssa.architect.blueprint.Blueprint;
import dev.ssa.architect.blueprint.BlueprintBlock;
import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.material.BlockCapability;
import dev.ssa.architect.material.BlockCapabilityRegistry;
import dev.ssa.architect.model.EntrancePreference;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.TerrainSnapshot;
import dev.ssa.architect.style.JapaneseStyle;
import dev.ssa.architect.style.MedievalStyle;
import dev.ssa.architect.style.ModernStyle;
import dev.ssa.architect.style.StylePack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class HouseGenerationPropertyTest {
    private static final List<StylePack> STYLES = List.of(
            new MedievalStyle(),
            new JapaneseStyle(),
            new ModernStyle());
    private static final List<Long> SEEDS = List.of(0L, 1L, 42L, Long.MAX_VALUE);

    @Test
    void seededBuiltInGenerationIsBoundedOrderedLegalAndReproducible() {
        ArchitectEngine engine = new ArchitectEngine();
        TerrainSnapshot terrain = gentleSlope(17, 17);
        for (StylePack style : STYLES) {
            BlockCapabilityRegistry registry = registry(style);
            for (long seed : SEEDS) {
                HouseRequirements requirements = new HouseRequirements(
                        style.id(),
                        13,
                        13,
                        2,
                        2,
                        true,
                        true,
                        false,
                        false,
                        EntrancePreference.AUTO,
                        seed);

                GenerationResult.Success first = success(engine.generate(
                        requirements, terrain, style, registry));
                GenerationResult.Success second = success(engine.generate(
                        requirements, terrain, style, registry));

                assertEquals(first.blueprint(), second.blueprint(), label(style, seed));
                assertEquals(first.blueprint().hash(), second.blueprint().hash(), label(style, seed));
                assertEquals(first.diagnostics(), second.diagnostics(), label(style, seed));
                assertBlueprintContracts(first.blueprint(), terrain, registry, label(style, seed));
            }
        }
    }

    private static void assertBlueprintContracts(
            Blueprint blueprint,
            TerrainSnapshot terrain,
            BlockCapabilityRegistry registry,
            String label) {
        assertTrue(blueprint.validation().isValid(), label);
        assertEquals(
                blueprint.blocks().size(),
                blueprint.blocks().stream().map(BlueprintBlock::relativePosition).distinct().count(),
                label + " duplicate placements");
        assertTrue(blueprint.blocks().stream().allMatch(block ->
                        withinSite(block.relativePosition(), terrain)),
                label + " block outside site");
        assertTrue(blueprint.terrainPlan().changes().stream().allMatch(change ->
                        withinSite(change.pos(), terrain)),
                label + " terrain change outside site");
        assertTrue(blueprint.blocks().stream().allMatch(block -> registry.supports(block.placementState())),
                label + " unsupported placement state");
        assertTrue(!blueprint.terrainPlan().modifyWater() && !blueprint.terrainPlan().modifyLava(),
                label + " illegal liquid modification");

        Map<GridPos, BlueprintBlock> byPosition = blueprint.blocks().stream().collect(Collectors.toMap(
                BlueprintBlock::relativePosition,
                Function.identity()));
        List<BuildPhase> order = BuildPhase.canonicalOrder();
        for (BlueprintBlock block : blueprint.blocks()) {
            for (GridPos dependencyPosition : block.dependencies()) {
                BlueprintBlock dependency = byPosition.get(dependencyPosition);
                assertNotNull(dependency, label + " missing dependency " + dependencyPosition);
                assertTrue(
                        order.indexOf(dependency.phase()) <= order.indexOf(block.phase()),
                        label + " dependency scheduled after its consumer");
            }
        }
    }

    private static boolean withinSite(GridPos position, TerrainSnapshot terrain) {
        return position.x() >= 0
                && position.x() < terrain.width()
                && position.z() >= 0
                && position.z() < terrain.depth();
    }

    private static GenerationResult.Success success(GenerationResult result) {
        return assertInstanceOf(GenerationResult.Success.class, result, result::toString);
    }

    private static TerrainSnapshot gentleSlope(int width, int depth) {
        List<Integer> heights = new ArrayList<>(width * depth);
        List<NamespacedId> materials = new ArrayList<>(width * depth);
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                heights.add(x < width / 2 ? 10 : 11);
                materials.add(NamespacedId.parse("minecraft:grass_block"));
            }
        }
        return new TerrainSnapshot(
                new GridPos(0, 10, 0),
                width,
                depth,
                10,
                11,
                heights,
                materials,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                new TerrainSnapshot.SlopeMetrics(0.1, 1.0),
                Map.of(),
                "gentle-" + width + "x" + depth);
    }

    private static BlockCapabilityRegistry registry(StylePack style) {
        Map<NamespacedId, Set<BlockCapability>> entries = new HashMap<>();
        style.fallbackPalette().values().forEach(candidates -> candidates.forEach(candidate -> entries
                .computeIfAbsent(candidate.state().blockId(), ignored -> new HashSet<>())
                .addAll(candidate.requiredCapabilities())));
        return BlockCapabilityRegistry.of(entries);
    }

    private static String label(StylePack style, long seed) {
        return style.id() + " seed=" + seed;
    }
}
