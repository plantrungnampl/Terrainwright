package dev.ssa.architect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.ArchitectEngine.CandidateStatus;
import dev.ssa.architect.ArchitectEngine.GenerationResult;
import dev.ssa.architect.blueprint.BlockRole;
import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.material.BlockCapability;
import dev.ssa.architect.material.BlockCapabilityRegistry;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.EntrancePreference;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.TerrainSnapshot;
import dev.ssa.architect.scoring.ScoreBreakdown;
import dev.ssa.architect.style.JapaneseStyle;
import dev.ssa.architect.style.MedievalStyle;
import dev.ssa.architect.style.ModernStyle;
import dev.ssa.architect.style.StylePack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ArchitectEngineTest {
    private final ArchitectEngine engine = new ArchitectEngine();
    private final StylePack style = new MedievalStyle();

    @Test
    void generationIsDeterministicAndReturnsBestValidOfEight() {
        GenerationResult.Success first = success(engine.generate(
                requirements(42L, 1), terrain(15, 15), style, registry(style)));
        GenerationResult.Success second = success(engine.generate(
                requirements(42L, 1), terrain(15, 15), style, registry(style)));

        assertEquals(first.blueprint().hash(), second.blueprint().hash());
        assertEquals(first.blueprint().scoreBreakdown(), second.blueprint().scoreBreakdown());
        assertEquals(first.diagnostics(), second.diagnostics());
        assertEquals(8, first.diagnostics().candidateCount());
        assertEquals(8, first.diagnostics().candidates().size());
        assertTrue(first.blueprint().validation().isValid());
        assertTrue(first.blueprint().blocks().stream()
                .anyMatch(block -> block.blockRole() == BlockRole.INTERIOR));

        double highestValidScore = first.diagnostics().candidates().stream()
                .filter(candidate -> candidate.status() == CandidateStatus.VALID)
                .map(ArchitectEngine.CandidateDiagnostic::score)
                .flatMap(java.util.Optional::stream)
                .mapToDouble(ScoreBreakdown::total)
                .max()
                .orElseThrow();
        assertEquals(highestValidScore, first.blueprint().scoreBreakdown().total());
    }

    @Test
    void featureMapInsertionOrderCannotChangeScoringOrWinner() {
        NamespacedId north = NamespacedId.parse("minecraft:north_feature");
        NamespacedId south = NamespacedId.parse("minecraft:south_feature");
        Map<NamespacedId, List<GridPos>> forward = new LinkedHashMap<>();
        forward.put(north, List.of(new GridPos(0, 0, -30)));
        forward.put(south, List.of(new GridPos(0, 0, 30)));
        Map<NamespacedId, List<GridPos>> reverse = new LinkedHashMap<>();
        reverse.put(south, List.of(new GridPos(0, 0, 30)));
        reverse.put(north, List.of(new GridPos(0, 0, -30)));

        GenerationResult.Success first = success(engine.generate(
                requirements(42L, 1), terrain(15, 15, forward), style, registry(style)));
        GenerationResult.Success second = success(engine.generate(
                requirements(42L, 1), terrain(15, 15, reverse), style, registry(style)));

        assertEquals(first.blueprint().hash(), second.blueprint().hash());
        assertEquals(first.diagnostics(), second.diagnostics());
    }

    @Test
    void rejectsAllInvalidCandidatesWithoutProducingAPreview() {
        GenerationResult.Failure failure = assertInstanceOf(
                GenerationResult.Failure.class,
                engine.generate(
                        requirements(91L, 1),
                        terrain(15, 15),
                        style,
                        BlockCapabilityRegistry.of(Map.of())));

        assertEquals(ArchitectEngine.FailureReason.NO_VALID_CANDIDATE, failure.reason());
        assertEquals(8, failure.diagnostics().candidateCount());
        assertEquals(0, failure.diagnostics().validCandidateCount());
        assertTrue(failure.diagnostics().candidates().stream()
                .allMatch(candidate -> candidate.status() == CandidateStatus.REJECTED));
        assertTrue(failure.diagnostics().candidates().stream()
                .allMatch(candidate -> candidate.rejectionCodes().contains("MATERIAL_UNRESOLVED")));
    }

    @Test
    void registryBackendFailuresAreNotMaskedAsCandidateRejections() {
        BlockCapabilityRegistry stable = registry(style);
        AtomicInteger supportsCalls = new AtomicInteger();
        BlockCapabilityRegistry failing = new BlockCapabilityRegistry() {
            @Override
            public java.util.Optional<Set<BlockCapability>> capabilities(NamespacedId blockId) {
                return stable.capabilities(blockId);
            }

            @Override
            public boolean supports(dev.ssa.architect.model.BlockStateSpec state) {
                if (supportsCalls.incrementAndGet() > MaterialRole.values().length) {
                    throw new IllegalStateException("registry backend failed");
                }
                return stable.supports(state);
            }
        };

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> engine.generate(requirements(42L, 1), terrain(15, 15), style, failing));

        assertEquals("registry backend failed", failure.getMessage());
    }

    @Test
    void interruptedGenerationCancelsWithoutReturningAPreview() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> engine.generate(
                    requirements(42L, 2), terrain(15, 15), style, registry(style)));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void everyBuiltInStyleAndFloorCountSupportsEveryEntranceDirection() {
        for (StylePack builtIn : List.of(
                new MedievalStyle(),
                new JapaneseStyle(),
                new ModernStyle())) {
            for (int floors : List.of(1, 2, 3)) {
                for (EntrancePreference preference : List.of(
                        EntrancePreference.NORTH,
                        EntrancePreference.EAST,
                        EntrancePreference.SOUTH,
                        EntrancePreference.WEST)) {
                    HouseRequirements directional = new HouseRequirements(
                            builtIn.id(), 13, 13, floors, 2, true, true,
                            false, false, preference, 55L);
                    GenerationResult generated = engine.generate(
                            directional, terrain(15, 15), builtIn, registry(builtIn));
                    GenerationResult.Success result = assertInstanceOf(
                            GenerationResult.Success.class,
                            generated,
                            builtIn.id() + " floors=" + floors + " preference=" + preference
                                    + " " + generated);

                    assertTrue(hasExteriorEntranceFacing(result, preference));
                }
            }
        }
    }

    @Test
    void chimneyAndBalconyRequirementsAffectGeometry() {
        HouseRequirements plain = new HouseRequirements(
                style.id(), 13, 13, 2, 1, true, false, false, false,
                EntrancePreference.AUTO, 81L);
        HouseRequirements configured = new HouseRequirements(
                style.id(), 13, 13, 2, 1, true, false, true, true,
                EntrancePreference.AUTO, 81L);
        GenerationResult.Success withoutFeatures = success(engine.generate(
                plain, terrain(15, 15), style, registry(style)));
        GenerationResult.Success withFeatures = success(engine.generate(
                configured, terrain(15, 15), style, registry(style)));

        assertNotEquals(withoutFeatures.blueprint().hash(), withFeatures.blueprint().hash());
        assertTrue(withFeatures.blueprint().rooms().stream()
                .anyMatch(room -> room.type().path().equals("balcony")));
        assertTrue(withFeatures.blueprint().blocks().stream()
                .anyMatch(block -> block.materialRole() == MaterialRole.RAILING));
        int roofTop = withFeatures.blueprint().blocks().stream()
                .filter(block -> block.materialRole() == MaterialRole.ROOF_PRIMARY)
                .mapToInt(block -> block.relativePosition().y())
                .max()
                .orElseThrow();
        assertTrue(withFeatures.blueprint().blocks().stream()
                .filter(block -> block.relativePosition().x() == 13)
                .filter(block -> block.relativePosition().z() == 6)
                .anyMatch(block -> block.relativePosition().y() > roofTop));
        assertTrue(withFeatures.blueprint().blocks().stream().allMatch(block ->
                block.relativePosition().x() >= 0
                        && block.relativePosition().x() < 15
                        && block.relativePosition().z() >= 0
                        && block.relativePosition().z() < 15));
    }

    @Test
    void multiFloorGenerationIncludesValidatorSafeStairs() {
        for (int floors : List.of(2, 3)) {
            GenerationResult.Success result = success(engine.generate(
                    requirements(7L, floors), terrain(15, 15), style, registry(style)));

            assertTrue(result.blueprint().validation().isValid());
            assertTrue(result.blueprint().blocks().stream()
                    .filter(block -> block.phase() == BuildPhase.STAIRS)
                    .count() >= (long) (floors - 1) * 4);
        }
    }

    @Test
    void everyBuiltInStyleProducesAValidatedCandidate() {
        for (StylePack builtIn : List.of(
                new MedievalStyle(),
                new JapaneseStyle(),
                new ModernStyle())) {
            for (int floors : List.of(1, 2, 3)) {
                HouseRequirements requirements = new HouseRequirements(
                        builtIn.id(),
                        13,
                        13,
                        floors,
                        2,
                        true,
                        true,
                        false,
                        false,
                        EntrancePreference.AUTO,
                        123L);

                GenerationResult generated = engine.generate(
                        requirements, terrain(15, 15), builtIn, registry(builtIn));
                GenerationResult.Success result = assertInstanceOf(
                        GenerationResult.Success.class,
                        generated,
                        builtIn.id() + " floors=" + floors + " " + generated);

                assertTrue(result.blueprint().validation().isValid(), builtIn.id() + " floors=" + floors);
                assertTrue(result.blueprint().blocks().stream().allMatch(block ->
                        block.relativePosition().x() >= 0
                                && block.relativePosition().x() < 15
                                && block.relativePosition().z() >= 0
                                && block.relativePosition().z() < 15));
            }
        }
    }

    @Test
    void commonFlatSiteRemainsViableAcrossRequestSeeds() {
        for (StylePack builtIn : List.of(
                new MedievalStyle(),
                new JapaneseStyle(),
                new ModernStyle())) {
            for (int floors : List.of(1, 2, 3)) {
                for (long seed : List.of(0L, 1L, 9L)) {
                    HouseRequirements requirements = new HouseRequirements(
                            builtIn.id(),
                            13,
                            13,
                            floors,
                            2,
                            true,
                            true,
                            false,
                            false,
                            EntrancePreference.AUTO,
                            seed);
                    GenerationResult generated = engine.generate(
                            requirements, terrain(15, 15), builtIn, registry(builtIn));

                    assertInstanceOf(
                            GenerationResult.Success.class,
                            generated,
                            builtIn.id() + " floors=" + floors + " seed=" + seed
                                    + " " + generated);
                }
            }
        }
    }

    @Test
    void recordsCommonTwoFloorGenerationBenchmark() {
        HouseRequirements warmup = requirements(1_000L, 2);
        engine.generate(warmup, terrain(15, 15), style, registry(style));

        List<Long> elapsedMillis = new ArrayList<>();
        for (long seed = 1_001L; seed <= 1_010L; seed++) {
            long started = System.nanoTime();
            GenerationResult result = engine.generate(
                    requirements(seed, 2), terrain(15, 15), style, registry(style));
            long elapsed = (System.nanoTime() - started) / 1_000_000L;

            assertInstanceOf(GenerationResult.Success.class, result, result::toString);
            elapsedMillis.add(elapsed);
        }
        elapsedMillis.sort(Long::compareTo);

        long median = elapsedMillis.get(elapsedMillis.size() / 2);
        long p95 = elapsedMillis.get(elapsedMillis.size() - 1);
        System.out.printf(
                "SSA_ARCHITECT_BENCHMARK samples=%d median_ms=%d p95_ms=%d%n",
                elapsedMillis.size(), median, p95);
        assertEquals(10, elapsedMillis.size());
    }

    private static GenerationResult.Success success(GenerationResult result) {
        return assertInstanceOf(GenerationResult.Success.class, result, result::toString);
    }

    private static boolean hasExteriorEntranceFacing(
            GenerationResult.Success result,
            EntrancePreference preference) {
        Set<GridPos> footprint = result.blueprint().footprint();
        return result.blueprint().blocks().stream()
                .filter(block -> block.materialRole() == MaterialRole.DOOR)
                .filter(block -> "lower".equals(block.placementState().properties().get("half")))
                .filter(block -> preference.name().equalsIgnoreCase(
                        block.placementState().properties().get("facing")))
                .map(block -> step(block.relativePosition(), preference))
                .anyMatch(outside -> footprint.stream().noneMatch(cell ->
                        cell.x() == outside.x() && cell.z() == outside.z()));
    }

    private static GridPos step(GridPos position, EntrancePreference direction) {
        return switch (direction) {
            case NORTH -> new GridPos(position.x(), position.y(), position.z() - 1);
            case EAST -> new GridPos(position.x() + 1, position.y(), position.z());
            case SOUTH -> new GridPos(position.x(), position.y(), position.z() + 1);
            case WEST -> new GridPos(position.x() - 1, position.y(), position.z());
            case AUTO -> throw new IllegalArgumentException("AUTO has no fixed direction");
        };
    }

    private HouseRequirements requirements(long seed, int floors) {
        return new HouseRequirements(
                style.id(),
                13,
                13,
                floors,
                floors == 1 ? 1 : 2,
                true,
                true,
                false,
                false,
                EntrancePreference.AUTO,
                seed);
    }

    private static TerrainSnapshot terrain(int width, int depth) {
        return terrain(
                width,
                depth,
                Map.of(
                        NamespacedId.parse("minecraft:village"),
                        List.of(new GridPos(width + 8, 0, depth / 2))));
    }

    private static TerrainSnapshot terrain(
            int width,
            int depth,
            Map<NamespacedId, List<GridPos>> features) {
        int area = width * depth;
        List<Integer> heights = new ArrayList<>(area);
        List<NamespacedId> materials = new ArrayList<>(area);
        for (int index = 0; index < area; index++) {
            heights.add(10);
            materials.add(NamespacedId.parse("minecraft:grass_block"));
        }
        return new TerrainSnapshot(
                new GridPos(0, 10, 0),
                width,
                depth,
                10,
                10,
                heights,
                materials,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                new TerrainSnapshot.SlopeMetrics(0, 0),
                features,
                "flat-" + width + "x" + depth);
    }

    private static BlockCapabilityRegistry registry(StylePack style) {
        Map<NamespacedId, Set<BlockCapability>> entries = new HashMap<>();
        style.fallbackPalette().values().forEach(candidates -> candidates.forEach(candidate -> entries
                .computeIfAbsent(candidate.state().blockId(), ignored -> new HashSet<>())
                .addAll(candidate.requiredCapabilities())));
        return BlockCapabilityRegistry.of(entries);
    }
}
