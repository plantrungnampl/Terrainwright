package dev.ssa.fabric.release;

import dev.ssa.architect.ArchitectEngine;
import dev.ssa.architect.ArchitectEngine.GenerationResult;
import dev.ssa.architect.blueprint.Blueprint;
import dev.ssa.architect.blueprint.BlueprintBlock;
import dev.ssa.architect.model.EntrancePreference;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.StyleId;
import dev.ssa.architect.model.TerrainSnapshot;
import dev.ssa.architect.terrain.TerrainPlan;
import dev.ssa.construction.operation.DropPolicy;
import dev.ssa.construction.operation.OperationKind;
import dev.ssa.construction.plan.ConstructionPlanner;
import dev.ssa.fabric.style.StyleDataLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class ReleaseScenarioGameTests {
    private static final List<StyleId> STYLE_IDS = List.of(
            StyleId.parse("smart_survival_architect:medieval"),
            StyleId.parse("smart_survival_architect:japanese"),
            StyleId.parse("smart_survival_architect:modern"));

    @GameTest
    public void bundledStylesGenerateSmallAndMediumServerPlans(GameTestHelper context) {
        verifyStyleMatrix();
        context.succeed();
    }

    private static void verifyStyleMatrix() {
        for (Fixture fixture : List.of(
                new Fixture("small-flat", 9, 11, 1, terrain(13, 15, false), 101L),
                new Fixture("medium-gentle", 15, 19, 2, terrain(19, 23, true), 202L))) {
            List<Blueprint> blueprints = new ArrayList<>();
            for (StyleId styleId : STYLE_IDS) {
                StyleDataLoader.LoadedStyle loaded = StyleDataLoader.find(styleId).orElseThrow();
                HouseRequirements requirements = new HouseRequirements(
                        styleId,
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
                        loaded.style(),
                        loaded.capabilities());
                if (!(result instanceof GenerationResult.Success)) {
                    throw new AssertionError(styleId + " failed " + fixture.name() + ": " + result);
                }
                Blueprint blueprint = ((GenerationResult.Success) result).blueprint();
                if (!blueprint.validation().isValid()) {
                    throw new AssertionError(styleId + " invalid " + fixture.name());
                }
                if (new ConstructionPlanner().plan(blueprint).tasks().isEmpty()) {
                    throw new AssertionError(styleId + " produced an empty construction plan");
                }
                if (blueprint.terrainPlan().modifyWater() || blueprint.terrainPlan().modifyLava()) {
                    throw new AssertionError(styleId + " attempted liquid modification");
                }
                blueprints.add(blueprint);
            }
            if (blueprints.stream().map(ReleaseScenarioGameTests::geometrySignature).distinct().count() != 3) {
                throw new AssertionError(fixture.name() + " did not retain three geometry identities");
            }
            if (blueprints.stream().map(ReleaseScenarioGameTests::materialSignature).distinct().count() != 3) {
                throw new AssertionError(fixture.name() + " did not retain three material identities");
            }
        }
    }

    @GameTest
    public void releaseVocabularyKeepsMutationAndDropScopeClosed(GameTestHelper context) {
        context.assertValueEqual(
                List.of(TerrainPlan.SalvagePolicy.values()),
                List.of(TerrainPlan.SalvagePolicy.DISCARD_NO_DROPS),
                "terrain salvage policies");
        context.assertValueEqual(
                List.of(DropPolicy.values()),
                List.of(DropPolicy.SUPPRESS, DropPolicy.NOT_APPLICABLE),
                "operation drop policies");
        context.assertValueEqual(
                List.of(OperationKind.values()),
                List.of(OperationKind.MATERIAL_TRANSFER, OperationKind.WORLD_MUTATION),
                "privileged operation kinds");
        context.succeed();
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
                (slope ? "release-gentle-" : "release-flat-") + width + "x" + depth);
    }

    private record Fixture(
            String name,
            int width,
            int depth,
            int floors,
            TerrainSnapshot terrain,
            long seed) {}
}
