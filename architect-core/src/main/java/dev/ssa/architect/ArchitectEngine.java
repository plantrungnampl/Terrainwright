package dev.ssa.architect;

import dev.ssa.architect.blueprint.BlockRole;
import dev.ssa.architect.blueprint.Blueprint;
import dev.ssa.architect.blueprint.BlueprintBlock;
import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.blueprint.Room;
import dev.ssa.architect.layout.FloorLayout;
import dev.ssa.architect.layout.FloorLayoutSolver;
import dev.ssa.architect.layout.Footprint;
import dev.ssa.architect.material.BlockCapabilityRegistry;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.material.PaletteResolver;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.TerrainSnapshot;
import dev.ssa.architect.opening.OpeningPlanner;
import dev.ssa.architect.opening.OpeningPlanner.Opening;
import dev.ssa.architect.opening.OpeningPlanner.OpeningPlan;
import dev.ssa.architect.opening.OpeningPlanner.OpeningPlanUnavailableException;
import dev.ssa.architect.opening.OpeningPlanner.OpeningType;
import dev.ssa.architect.roof.RoofPlanner;
import dev.ssa.architect.room.RoomGraph;
import dev.ssa.architect.room.RoomGraphGenerator;
import dev.ssa.architect.scoring.BlueprintScorer;
import dev.ssa.architect.scoring.ScoreBreakdown;
import dev.ssa.architect.style.StylePack;
import dev.ssa.architect.terrain.TerrainAdaptationPlanner;
import dev.ssa.architect.terrain.TerrainBudget;
import dev.ssa.architect.terrain.TerrainPlan;
import dev.ssa.architect.validation.BlueprintValidation;
import dev.ssa.architect.validation.BlueprintValidator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

public final class ArchitectEngine {
    public static final int CANDIDATE_COUNT = 8;
    private static final int LAYOUT_ATTEMPT_COUNT = 4;
    private static final int FLOOR_HEIGHT = 4;
    private static final int STAIR_RUN_LENGTH = FLOOR_HEIGHT;
    private static final Comparator<Footprint.Cell> CELL_ORDER = Comparator
            .comparingInt(Footprint.Cell::x)
            .thenComparingInt(Footprint.Cell::z);
    private static final Comparator<GridPos> POSITION_ORDER = Comparator
            .comparingInt(GridPos::y)
            .thenComparingInt(GridPos::x)
            .thenComparingInt(GridPos::z);

    private final RoomGraphGenerator graphGenerator = new RoomGraphGenerator();
    private final FloorLayoutSolver layoutSolver = new FloorLayoutSolver();
    private final TerrainAdaptationPlanner terrainPlanner = new TerrainAdaptationPlanner();
    private final OpeningPlanner openingPlanner = new OpeningPlanner();
    private final RoofPlanner roofPlanner = new RoofPlanner();
    private final BlueprintScorer scorer = new BlueprintScorer();

    public GenerationResult generate(
            HouseRequirements requirements,
            TerrainSnapshot terrain,
            StylePack style,
            BlockCapabilityRegistry registry) {
        Objects.requireNonNull(requirements, "requirements");
        Objects.requireNonNull(terrain, "terrain");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(registry, "registry");
        checkCancelled();

        if (!requirements.styleId().equals(style.id())) {
            return rejectedResult(requirements.seed(), "STYLE_MISMATCH");
        }
        if (requirements.targetWidth() > terrain.width()
                || requirements.targetDepth() > terrain.depth()) {
            return rejectedResult(requirements.seed(), "SITE_OUT_OF_BOUNDS");
        }

        Optional<Map<MaterialRole, BlockStateSpec>> resolvedPalette = resolvePalette(style, registry);
        if (resolvedPalette.isEmpty()) {
            return rejectedResult(requirements.seed(), "MATERIAL_UNRESOLVED");
        }

        RoomGraph graph = graphGenerator.generate(requirements);
        BlueprintValidator validator = new BlueprintValidator(style, registry);
        List<CandidateDiagnostic> diagnostics = new ArrayList<>(CANDIDATE_COUNT);
        List<ValidCandidate> validCandidates = new ArrayList<>();
        for (int index = 0; index < CANDIDATE_COUNT; index++) {
            checkCancelled();
            long seed = candidateSeed(requirements.seed(), index);
            CandidateAttempt attempt = generateCandidate(
                    index,
                    seed,
                    requirements,
                    terrain,
                    style,
                    resolvedPalette.orElseThrow(),
                    graph,
                    validator);
            diagnostics.add(attempt.diagnostic());
            if (attempt.blueprint().isPresent()) {
                validCandidates.add(new ValidCandidate(index, attempt.blueprint().orElseThrow()));
            }
        }

        GenerationDiagnostics resultDiagnostics = new GenerationDiagnostics(
                CANDIDATE_COUNT,
                validCandidates.size(),
                diagnostics);
        Optional<ValidCandidate> selected = validCandidates.stream().max(
                Comparator.comparingDouble((ValidCandidate candidate) ->
                                candidate.blueprint().scoreBreakdown().total())
                        .thenComparingInt(candidate -> -candidate.index()));
        return selected.<GenerationResult>map(candidate -> new GenerationResult.Success(
                        candidate.blueprint(),
                        resultDiagnostics))
                .orElseGet(() -> new GenerationResult.Failure(
                        FailureReason.NO_VALID_CANDIDATE,
                        resultDiagnostics));
    }

    private CandidateAttempt generateCandidate(
            int index,
            long seed,
            HouseRequirements requirements,
            TerrainSnapshot terrain,
            StylePack style,
            Map<MaterialRole, BlockStateSpec> palette,
            RoomGraph graph,
            BlueprintValidator validator) {
        try {
            Footprint footprint = footprint(requirements, terrain, style, index);
            Optional<Footprint.Cell> chimneyCell = chimneyCell(requirements, footprint, terrain);
            if (requirements.chimney() && chimneyCell.isEmpty()) {
                return rejected(index, seed, "CHIMNEY_UNAVAILABLE");
            }
            Set<GridPos> terrainFootprint = new HashSet<>(footprint.cells().stream()
                    .map(cell -> new GridPos(cell.x(), 0, cell.z()))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
            chimneyCell.ifPresent(cell -> terrainFootprint.add(new GridPos(cell.x(), 0, cell.z())));
            Optional<TerrainPlan> terrainPlan = terrainPlanner.plan(
                    terrain,
                    terrainFootprint,
                    palette.get(MaterialRole.FOUNDATION_FILL),
                    TerrainBudget.light());
            if (terrainPlan.isEmpty()) {
                return rejected(index, seed, "TERRAIN_UNSUITABLE");
            }

            int baseY = terrainBaseY(terrain, footprint, terrainPlan.orElseThrow());
            List<String> rejectionCodes = new ArrayList<>();
            for (int attempt = 0; attempt < LAYOUT_ATTEMPT_COUNT; attempt++) {
                checkCancelled();
                long layoutSeed = attempt == 0 ? seed : candidateSeed(seed, attempt);
                Optional<FloorLayout> layout = layoutSolver.solve(
                        graph,
                        footprint,
                        requirements.entrancePreference(),
                        layoutSeed);
                if (layout.isEmpty()) {
                    rejectionCodes.add("LAYOUT_UNSATISFIABLE");
                    continue;
                }
                OpeningPlan openings;
                try {
                    openings = openingPlanner.plan(
                            graph,
                            layout.orElseThrow(),
                            footprint,
                            FLOOR_HEIGHT,
                            style,
                            requirements.entrancePreference(),
                            layoutSeed);
                } catch (OpeningPlanUnavailableException exception) {
                    rejectionCodes.add(openingRejectionCode(exception));
                    continue;
                }

                Blueprint unscored;
                try {
                    unscored = assembleBlueprint(
                            index,
                            seed,
                            requirements,
                            terrain,
                            style,
                            footprint,
                            graph,
                            layout.orElseThrow(),
                            openings,
                            terrainPlan.orElseThrow(),
                            palette,
                            chimneyCell,
                            baseY);
                } catch (CandidateRejectedException exception) {
                    rejectionCodes.add(exception.code());
                    continue;
                }
                BlueprintValidation validation = validator.validate(unscored);
                if (!validation.isValid()) {
                    validation.issues().stream()
                            .map(BlueprintValidation.Issue::code)
                            .forEach(rejectionCodes::add);
                    continue;
                }

                ScoreBreakdown score = score(
                        unscored,
                        graph,
                        layout.orElseThrow(),
                        footprint,
                        openings,
                        terrainPlan.orElseThrow(),
                        terrain,
                        style);
                Blueprint scored = copyWith(unscored, score, validation);
                return new CandidateAttempt(
                        Optional.of(scored),
                        new CandidateDiagnostic(
                                index,
                                seed,
                                CandidateStatus.VALID,
                                Optional.of(score),
                                List.of()));
            }
            return rejected(index, seed, rejectionCodes.stream().distinct().toList());
        } catch (CandidateRejectedException exception) {
            return rejected(index, seed, exception.code());
        }
    }

    private Blueprint assembleBlueprint(
            int candidateIndex,
            long seed,
            HouseRequirements requirements,
            TerrainSnapshot terrain,
            StylePack style,
            Footprint footprint,
            RoomGraph graph,
            FloorLayout layout,
            OpeningPlan openings,
            TerrainPlan terrainPlan,
            Map<MaterialRole, BlockStateSpec> palette,
            Optional<Footprint.Cell> chimneyCell,
            int baseY) {
        List<Room> rooms = graph.nodes().stream()
                .map(node -> new Room(
                        node.id(),
                        node.type(),
                        node.floor(),
                        layout.rooms().get(node.id()).cells().stream()
                                .map(cell -> new GridPos(
                                        cell.x(),
                                        baseY + node.floor() * FLOOR_HEIGHT + 1,
                                        cell.z()))
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                        graph.adjacentRoomIds(node.id())))
                .toList();

        StairPlan stairs = stairs(graph, layout, palette.get(MaterialRole.STAIR), baseY);
        Set<GridPos> openingVolume = openingVolume(openings, baseY);
        Map<GridPos, BlueprintBlock> blocks = new LinkedHashMap<>();
        addFoundation(blocks, footprint, palette.get(MaterialRole.FOUNDATION_STONE), baseY);
        addFloorsAndWalls(
                blocks,
                footprint,
                layout,
                requirements.floors(),
                palette,
                stairs.reservedVolume(),
                openingVolume,
                baseY);
        stairs.blocks().forEach(block -> putUnique(blocks, block));
        addOpenings(blocks, openings, palette, stairs.reservedVolume(), baseY);
        int roofLimitX = chimneyCell
                .filter(cell -> cell.x() >= footprint.width())
                .map(Footprint.Cell::x)
                .orElse(terrain.width());
        int roofLimitZ = chimneyCell
                .filter(cell -> cell.z() >= footprint.depth())
                .map(Footprint.Cell::z)
                .orElse(terrain.depth());
        List<BlueprintBlock> roof = roofPlanner.planWithinSnapshot(
                footprint,
                baseY + requirements.floors() * FLOOR_HEIGHT,
                style,
                palette.get(MaterialRole.ROOF_PRIMARY),
                palette.get(MaterialRole.STRUCTURAL_PRIMARY),
                roofLimitX,
                roofLimitZ);
        roof.forEach(block -> putUnique(blocks, block));
        chimneyCell.ifPresent(cell -> addChimney(blocks, roof, cell, palette, baseY));

        Set<GridPos> blueprintFootprint = footprint.cells().stream()
                .map(cell -> new GridPos(cell.x(), baseY, cell.z()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<BlueprintBlock> orderedBlocks = blocks.values().stream()
                .sorted(Comparator.comparing(BlueprintBlock::relativePosition, POSITION_ORDER))
                .toList();
        Blueprint.LocalBounds bounds = bounds(
                blueprintFootprint,
                rooms,
                orderedBlocks,
                terrainPlan);
        UUID id = UUID.nameUUIDFromBytes((requirements.seed()
                        + ":" + candidateIndex
                        + ":" + seed
                        + ":" + style.id()
                        + ":" + terrain.revisionFingerprint())
                .getBytes(StandardCharsets.UTF_8));
        return new Blueprint(
                id,
                seed,
                style.id(),
                bounds,
                blueprintFootprint,
                requirements.floors(),
                rooms,
                orderedBlocks,
                BuildPhase.canonicalOrder(),
                terrainPlan,
                ScoreBreakdown.unscored(),
                BlueprintValidation.valid(),
                Blueprint.CURRENT_FORMAT_VERSION);
    }

    private static void addFoundation(
            Map<GridPos, BlueprintBlock> blocks,
            Footprint footprint,
            BlockStateSpec state,
            int baseY) {
        footprint.cells().stream().sorted(CELL_ORDER).forEach(cell -> putUnique(
                blocks,
                new BlueprintBlock(
                        new GridPos(cell.x(), baseY, cell.z()),
                        BlockRole.FOUNDATION,
                        MaterialRole.FOUNDATION_STONE,
                        state,
                        BuildPhase.FOUNDATION,
                        Set.of())));
    }

    private static void addFloorsAndWalls(
            Map<GridPos, BlueprintBlock> blocks,
            Footprint footprint,
            FloorLayout layout,
            int floors,
            Map<MaterialRole, BlockStateSpec> palette,
            Set<GridPos> stairVolume,
            Set<GridPos> openingVolume,
            int baseY) {
        for (int floor = 0; floor < floors; floor++) {
            int floorY = baseY + floor * FLOOR_HEIGHT;
            Map<Footprint.Cell, String> roomByCell = new HashMap<>();
            for (FloorLayout.PlacedRoom room : layout.rooms().values()) {
                if (room.floor() == floor) {
                    room.cells().forEach(cell -> roomByCell.put(cell, room.roomId()));
                }
            }
            if (floor > 0) {
                for (Footprint.Cell cell : footprint.cells().stream().sorted(CELL_ORDER).toList()) {
                    GridPos position = new GridPos(cell.x(), floorY, cell.z());
                    if (!stairVolume.contains(position)) {
                        putUnique(blocks, new BlueprintBlock(
                                position,
                                BlockRole.STRUCTURAL,
                                MaterialRole.FLOOR_PRIMARY,
                                palette.get(MaterialRole.FLOOR_PRIMARY),
                                BuildPhase.UPPER_FLOOR,
                                Set.of()));
                    }
                }
            }

            int wallTop = floor == floors - 1 ? floorY + 2 : floorY + 3;
            for (Footprint.Cell cell : footprint.cells().stream().sorted(CELL_ORDER).toList()) {
                boolean exterior = footprint.touchesBoundary(Set.of(cell));
                boolean interior = ownsRoomBoundary(cell, roomByCell, footprint);
                boolean voidBoundary = isUnassignedBoundary(cell, roomByCell);
                boolean balcony = "balcony".equals(roomByCell.get(cell));
                boolean openBalconyEdge = balcony && exterior && !interior;
                if (!exterior && !interior && !voidBoundary) {
                    continue;
                }
                for (int y = floorY + 1; y <= wallTop; y++) {
                    if (openBalconyEdge && y > floorY + 1) {
                        continue;
                    }
                    GridPos position = new GridPos(cell.x(), y, cell.z());
                    if (stairVolume.contains(position) || openingVolume.contains(position)) {
                        continue;
                    }
                    putUnique(blocks, new BlueprintBlock(
                            position,
                            interior || (voidBoundary && !exterior)
                                    ? BlockRole.INTERIOR
                                    : BlockRole.ENVELOPE,
                            openBalconyEdge
                                    ? MaterialRole.RAILING
                                    : interior || voidBoundary
                                            ? MaterialRole.WALL_SECONDARY
                                            : MaterialRole.WALL_PRIMARY,
                            palette.get(openBalconyEdge
                                    ? MaterialRole.RAILING
                                    : interior || voidBoundary
                                            ? MaterialRole.WALL_SECONDARY
                                            : MaterialRole.WALL_PRIMARY),
                            floor == 0 ? BuildPhase.WALLS : BuildPhase.UPPER_FLOOR,
                            Set.of()));
                }
            }
        }
    }

    private static boolean ownsRoomBoundary(
            Footprint.Cell cell,
            Map<Footprint.Cell, String> roomByCell,
            Footprint footprint) {
        String roomId = roomByCell.get(cell);
        if (roomId == null) {
            return false;
        }
        for (Footprint.Cell neighbor : neighbors(cell)) {
            if (!footprint.cells().contains(neighbor)) {
                continue;
            }
            String adjacentRoomId = roomByCell.get(neighbor);
            if (adjacentRoomId != null
                    && (!adjacentRoomId.equals(roomId)
                            && roomId.compareTo(adjacentRoomId) < 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnassignedBoundary(
            Footprint.Cell cell,
            Map<Footprint.Cell, String> roomByCell) {
        return roomByCell.get(cell) == null
                && neighbors(cell).stream().anyMatch(neighbor -> roomByCell.get(neighbor) != null);
    }

    private static void addOpenings(
            Map<GridPos, BlueprintBlock> blocks,
            OpeningPlan plan,
            Map<MaterialRole, BlockStateSpec> palette,
            Set<GridPos> stairVolume,
            int baseY) {
        for (Opening opening : plan.openings()) {
            GridPos lower = shiftY(opening.relativePosition(), baseY);
            if (opening.type() == OpeningType.WINDOW) {
                if (!stairVolume.contains(lower)) {
                    putUnique(blocks, new BlueprintBlock(
                            lower,
                            BlockRole.OPENING,
                            MaterialRole.WINDOW,
                            palette.get(MaterialRole.WINDOW),
                            BuildPhase.WINDOWS_DOORS,
                            Set.of()));
                }
                continue;
            }
            GridPos upper = new GridPos(lower.x(), lower.y() + 1, lower.z());
            if (stairVolume.contains(lower) || stairVolume.contains(upper)) {
                continue;
            }
            BlockStateSpec lowerState = withProperties(
                    palette.get(MaterialRole.DOOR),
                    Map.of("facing", direction(opening), "half", "lower"));
            BlockStateSpec upperState = withProperties(
                    palette.get(MaterialRole.DOOR),
                    Map.of("facing", direction(opening), "half", "upper"));
            putUnique(blocks, new BlueprintBlock(
                    lower,
                    BlockRole.OPENING,
                    MaterialRole.DOOR,
                    lowerState,
                    BuildPhase.WINDOWS_DOORS,
                    Set.of()));
            putUnique(blocks, new BlueprintBlock(
                    upper,
                    BlockRole.OPENING,
                    MaterialRole.DOOR,
                    upperState,
                    BuildPhase.WINDOWS_DOORS,
                    Set.of(lower)));
        }
    }

    private static void addChimney(
            Map<GridPos, BlueprintBlock> blocks,
            List<BlueprintBlock> roof,
            Footprint.Cell chimneyCell,
            Map<MaterialRole, BlockStateSpec> palette,
            int baseY) {
        int topY = roof.stream()
                .map(BlueprintBlock::relativePosition)
                .mapToInt(GridPos::y)
                .max()
                .orElseThrow() + 1;
        GridPos previous = null;
        for (int y = baseY; y <= topY; y++) {
            GridPos position = new GridPos(chimneyCell.x(), y, chimneyCell.z());
            boolean foundation = y == baseY;
            putUnique(blocks, new BlueprintBlock(
                    position,
                    foundation ? BlockRole.FOUNDATION : BlockRole.STRUCTURAL,
                    foundation ? MaterialRole.FOUNDATION_STONE : MaterialRole.WALL_SECONDARY,
                    palette.get(foundation
                            ? MaterialRole.FOUNDATION_STONE
                            : MaterialRole.WALL_SECONDARY),
                    foundation ? BuildPhase.FOUNDATION : BuildPhase.WALLS,
                    previous == null ? Set.of() : Set.of(previous)));
            previous = position;
        }
    }

    private static Optional<Footprint.Cell> chimneyCell(
            HouseRequirements requirements,
            Footprint footprint,
            TerrainSnapshot terrain) {
        if (!requirements.chimney()) {
            return Optional.empty();
        }
        Footprint.Cell east = new Footprint.Cell(footprint.width(), footprint.depth() / 2);
        if (east.x() < terrain.width()) {
            return Optional.of(east);
        }
        Footprint.Cell south = new Footprint.Cell(footprint.width() / 2, footprint.depth());
        return south.z() < terrain.depth() ? Optional.of(south) : Optional.empty();
    }

    private static StairPlan stairs(
            RoomGraph graph,
            FloorLayout layout,
            BlockStateSpec stairState,
            int baseY) {
        List<BlueprintBlock> blocks = new ArrayList<>();
        Set<GridPos> reserved = new HashSet<>();
        Set<Footprint.Cell> usedColumns = new HashSet<>();
        List<RoomGraph.Edge> transitions = graph.edges().stream()
                .filter(edge -> edge.transition() == RoomGraph.Transition.STAIR)
                .sorted(Comparator.<RoomGraph.Edge>comparingInt(edge -> Math.min(
                                layout.rooms().get(edge.firstRoomId()).floor(),
                                layout.rooms().get(edge.secondRoomId()).floor()))
                        .thenComparing(RoomGraph.Edge::firstRoomId))
                .toList();
        for (RoomGraph.Edge edge : transitions) {
            FloorLayout.PlacedRoom first = layout.rooms().get(edge.firstRoomId());
            FloorLayout.PlacedRoom second = layout.rooms().get(edge.secondRoomId());
            FloorLayout.PlacedRoom lower = first.floor() < second.floor() ? first : second;
            Set<Footprint.Cell> overlap = new HashSet<>(first.cells());
            overlap.retainAll(second.cells());
            List<Footprint.Cell> path = stairPath(overlap, usedColumns)
                    .orElseThrow(() -> new CandidateRejectedException("STAIR_UNSATISFIABLE"));
            usedColumns.addAll(path);
            int startY = baseY + lower.floor() * FLOOR_HEIGHT + 1;
            for (int index = 0; index < path.size(); index++) {
                Footprint.Cell cell = path.get(index);
                GridPos position = new GridPos(cell.x(), startY + index, cell.z());
                String facing = index + 1 < path.size()
                        ? direction(cell, path.get(index + 1))
                        : direction(path.get(index - 1), cell);
                Set<GridPos> dependencies = index == 0
                        ? Set.of()
                        : Set.of(blocks.getLast().relativePosition());
                blocks.add(new BlueprintBlock(
                        position,
                        BlockRole.STRUCTURAL,
                        MaterialRole.STAIR,
                        withProperties(stairState, Map.of("facing", facing)),
                        BuildPhase.STAIRS,
                        dependencies));
                reserved.add(position);
                reserved.add(new GridPos(position.x(), position.y() + 1, position.z()));
                reserved.add(new GridPos(position.x(), position.y() + 2, position.z()));
            }
        }
        return new StairPlan(blocks, reserved);
    }

    private static Optional<List<Footprint.Cell>> stairPath(
            Set<Footprint.Cell> overlap,
            Set<Footprint.Cell> usedColumns) {
        List<Footprint.Cell> candidates = overlap.stream()
                .filter(cell -> !usedColumns.contains(cell))
                .sorted(CELL_ORDER)
                .toList();
        Set<Footprint.Cell> allowed = Set.copyOf(candidates);
        for (Footprint.Cell start : candidates) {
            List<Footprint.Cell> path = new ArrayList<>();
            path.add(start);
            if (extendStairPath(path, allowed)) {
                return Optional.of(List.copyOf(path));
            }
        }
        return Optional.empty();
    }

    private static boolean extendStairPath(
            List<Footprint.Cell> path,
            Set<Footprint.Cell> allowed) {
        if (path.size() == STAIR_RUN_LENGTH) {
            return true;
        }
        Footprint.Cell last = path.getLast();
        for (Footprint.Cell neighbor : neighbors(last).stream().sorted(CELL_ORDER).toList()) {
            if (allowed.contains(neighbor) && !path.contains(neighbor)) {
                path.add(neighbor);
                if (extendStairPath(path, allowed)) {
                    return true;
                }
                path.removeLast();
            }
        }
        return false;
    }

    private ScoreBreakdown score(
            Blueprint blueprint,
            RoomGraph graph,
            FloorLayout layout,
            Footprint footprint,
            OpeningPlan openings,
            TerrainPlan terrainPlan,
            TerrainSnapshot terrain,
            StylePack style) {
        int requiredArea = graph.nodes().stream().mapToInt(RoomGraph.Node::minimumArea).sum();
        int actualArea = layout.rooms().values().stream()
                .mapToInt(room -> room.cells().size())
                .sum();
        double layoutQuality = clamp((double) requiredArea / actualArea);
        double terrainFit = clamp(1.0 - (
                        (double) terrainPlan.removedCount() / TerrainBudget.light().maxRemovedBlocks()
                                + (double) terrainPlan.filledCount() / TerrainBudget.light().maxFilledBlocks()
                                + (double) terrainPlan.maxVerticalCut() / TerrainBudget.light().maxVerticalCut()
                                + (double) terrainPlan.maxVerticalFill() / TerrainBudget.light().maxVerticalFill())
                / 4.0);
        double actualRatio = (double) footprint.width() / footprint.depth();
        double preferredRatio = style.proportionRules().preferredWidthDepthRatio();
        double ratioFit = clamp(1.0 - Math.abs(actualRatio - preferredRatio) / preferredRatio);
        double symmetryFit = clamp(1.0 - Math.abs(
                symmetry(footprint) - style.proportionRules().symmetryBias()));
        double styleConsistency = 0.7 * ratioFit + 0.3 * symmetryFit;
        double accessibility = layout.allRoomsReachableFromEntrance() ? 1.0 : 0.0;
        double expectedPlacements = footprint.cells().size() * (2.0 + blueprint.floors());
        double materialEfficiency = clamp(expectedPlacements / blueprint.blocks().size());
        double scenicOrientation = scenicOrientation(openings, terrain);
        return scorer.score(new BlueprintScorer.Metrics(
                layoutQuality,
                terrainFit,
                styleConsistency,
                accessibility,
                materialEfficiency,
                scenicOrientation));
    }

    private static double symmetry(Footprint footprint) {
        int symmetric = 0;
        for (Footprint.Cell cell : footprint.cells()) {
            Footprint.Cell mirror = new Footprint.Cell(footprint.width() - 1 - cell.x(), cell.z());
            if (footprint.cells().contains(mirror)) {
                symmetric++;
            }
        }
        return (double) symmetric / footprint.cells().size();
    }

    private static double scenicOrientation(OpeningPlan openings, TerrainSnapshot terrain) {
        Optional<Opening> entrance = openings.openings().stream()
                .filter(opening -> opening.type() == OpeningType.ENTRANCE_DOOR)
                .findFirst();
        Optional<GridPos> feature = terrain.nearbyFeatureVectors().values().stream()
                .flatMap(List::stream)
                .min(Comparator.<GridPos>comparingDouble(position ->
                                Math.hypot(position.x(), position.z()))
                        .thenComparingInt(GridPos::x)
                        .thenComparingInt(GridPos::y)
                        .thenComparingInt(GridPos::z));
        if (entrance.isEmpty() || feature.isEmpty()) {
            return 0.5;
        }
        int directionX = switch (entrance.orElseThrow().facing()) {
            case EAST -> 1;
            case WEST -> -1;
            default -> 0;
        };
        int directionZ = switch (entrance.orElseThrow().facing()) {
            case SOUTH -> 1;
            case NORTH -> -1;
            default -> 0;
        };
        GridPos vector = feature.orElseThrow();
        double magnitude = Math.hypot(vector.x(), vector.z());
        if (magnitude == 0) {
            return 0.5;
        }
        double cosine = (directionX * vector.x() + directionZ * vector.z()) / magnitude;
        return clamp((cosine + 1.0) / 2.0);
    }

    private static Footprint footprint(
            HouseRequirements requirements,
            TerrainSnapshot terrain,
            StylePack style,
            int candidateIndex) {
        int width = requirements.targetWidth();
        int depth = requirements.targetDepth();
        if (width > terrain.width() || depth > terrain.depth()) {
            throw new IllegalArgumentException("Requested footprint does not fit the terrain snapshot");
        }
        int variant = candidateIndex % 4;
        if (style.roofRules().primaryFamily() == StylePack.RoofFamily.FLAT && variant == 2) {
            variant = 0;
        }
        return switch (variant) {
            case 1 -> Footprint.lShape(
                    width,
                    depth,
                    Math.max(2, width / 4),
                    Math.max(2, depth / 4));
            case 2 -> Footprint.tShape(
                    width,
                    depth,
                    Math.max(4, width / 2),
                    Math.max(3, depth / 3));
            default -> Footprint.rectangle(width, depth);
        };
    }

    private static int terrainBaseY(
            TerrainSnapshot terrain,
            Footprint footprint,
            TerrainPlan plan) {
        Optional<TerrainPlan.TerrainCellChange> removal = plan.changes().stream()
                .filter(change -> change.afterState().blockId().toString().equals("minecraft:air"))
                .min(Comparator.comparingInt(change -> change.pos().y()));
        if (removal.isPresent()) {
            return removal.orElseThrow().pos().y() - 1;
        }
        Optional<TerrainPlan.TerrainCellChange> fill = plan.changes().stream()
                .filter(change -> change.beforeState().blockId().toString().equals("minecraft:air"))
                .max(Comparator.comparingInt(change -> change.pos().y()));
        if (fill.isPresent()) {
            return fill.orElseThrow().pos().y();
        }
        Footprint.Cell first = footprint.cells().stream().min(CELL_ORDER).orElseThrow();
        return terrain.surfaceYAt(first.x(), first.z()) - terrain.origin().y();
    }

    private static Optional<Map<MaterialRole, BlockStateSpec>> resolvePalette(
            StylePack style,
            BlockCapabilityRegistry registry) {
        PaletteResolver resolver = new PaletteResolver(style);
        EnumMap<MaterialRole, BlockStateSpec> resolved = new EnumMap<>(MaterialRole.class);
        for (MaterialRole role : MaterialRole.values()) {
            Optional<BlockStateSpec> state = resolver.resolve(role, registry);
            if (state.isEmpty()) {
                return Optional.empty();
            }
            resolved.put(role, state.orElseThrow());
        }
        return Optional.of(Map.copyOf(resolved));
    }

    private static Blueprint copyWith(
            Blueprint blueprint,
            ScoreBreakdown score,
            BlueprintValidation validation) {
        return new Blueprint(
                blueprint.id(),
                blueprint.seed(),
                blueprint.styleId(),
                blueprint.localBounds(),
                blueprint.footprint(),
                blueprint.floors(),
                blueprint.rooms(),
                blueprint.blocks(),
                blueprint.buildPhases(),
                blueprint.terrainPlan(),
                score,
                validation,
                blueprint.formatVersion());
    }

    private static Blueprint.LocalBounds bounds(
            Set<GridPos> footprint,
            List<Room> rooms,
            List<BlueprintBlock> blocks,
            TerrainPlan terrainPlan) {
        List<GridPos> positions = new ArrayList<>(footprint);
        rooms.forEach(room -> positions.addAll(room.cells()));
        blocks.forEach(block -> {
            positions.add(block.relativePosition());
            positions.addAll(block.dependencies());
        });
        terrainPlan.changes().forEach(change -> positions.add(change.pos()));
        int minX = positions.stream().mapToInt(GridPos::x).min().orElseThrow();
        int minY = positions.stream().mapToInt(GridPos::y).min().orElseThrow();
        int minZ = positions.stream().mapToInt(GridPos::z).min().orElseThrow();
        int maxX = positions.stream().mapToInt(GridPos::x).max().orElseThrow();
        int maxY = positions.stream().mapToInt(GridPos::y).max().orElseThrow();
        int maxZ = positions.stream().mapToInt(GridPos::z).max().orElseThrow();
        return new Blueprint.LocalBounds(
                new GridPos(minX, minY, minZ),
                new GridPos(maxX, maxY, maxZ));
    }

    private static Set<GridPos> openingVolume(OpeningPlan plan, int baseY) {
        Set<GridPos> positions = new HashSet<>();
        for (Opening opening : plan.openings()) {
            GridPos lower = shiftY(opening.relativePosition(), baseY);
            positions.add(lower);
            if (opening.type() != OpeningType.WINDOW) {
                positions.add(new GridPos(lower.x(), lower.y() + 1, lower.z()));
            }
        }
        return Set.copyOf(positions);
    }

    private static BlockStateSpec withProperties(
            BlockStateSpec state,
            Map<String, String> overrides) {
        Map<String, String> properties = new HashMap<>(state.properties());
        properties.putAll(overrides);
        return new BlockStateSpec(state.blockId(), properties);
    }

    private static String direction(Opening opening) {
        return opening.facing().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String direction(Footprint.Cell from, Footprint.Cell to) {
        if (to.x() > from.x()) {
            return "east";
        }
        if (to.x() < from.x()) {
            return "west";
        }
        return to.z() > from.z() ? "south" : "north";
    }

    private static Set<Footprint.Cell> neighbors(Footprint.Cell cell) {
        return Set.of(
                new Footprint.Cell(cell.x() - 1, cell.z()),
                new Footprint.Cell(cell.x() + 1, cell.z()),
                new Footprint.Cell(cell.x(), cell.z() - 1),
                new Footprint.Cell(cell.x(), cell.z() + 1));
    }

    private static GridPos shiftY(GridPos position, int amount) {
        return new GridPos(position.x(), position.y() + amount, position.z());
    }

    private static void putUnique(
            Map<GridPos, BlueprintBlock> blocks,
            BlueprintBlock block) {
        if (blocks.putIfAbsent(block.relativePosition(), block) != null) {
            throw new IllegalStateException(
                    "Duplicate assembled block position: " + block.relativePosition());
        }
    }

    private static long candidateSeed(long requestSeed, int candidateIndex) {
        long value = requestSeed + 0x9E3779B97F4A7C15L * (candidateIndex + 1L);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static String openingRejectionCode(OpeningPlanUnavailableException exception) {
        String message = exception.getMessage();
        if (message.startsWith("No unoccupied shared wall")) {
            return "INTERIOR_DOOR_UNAVAILABLE";
        }
        if (message.startsWith("Entrance room")) {
            return "ENTRANCE_UNAVAILABLE";
        }
        return "WINDOW_UNAVAILABLE";
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Architect generation was cancelled");
        }
    }

    private static CandidateAttempt rejected(int index, long seed, String code) {
        return rejected(index, seed, List.of(code));
    }

    private static CandidateAttempt rejected(
            int index,
            long seed,
            List<String> codes) {
        return new CandidateAttempt(
                Optional.empty(),
                new CandidateDiagnostic(
                        index,
                        seed,
                        CandidateStatus.REJECTED,
                        Optional.empty(),
                        codes));
    }

    private static GenerationResult rejectedResult(long requestSeed, String code) {
        List<CandidateDiagnostic> candidates = new ArrayList<>(CANDIDATE_COUNT);
        for (int index = 0; index < CANDIDATE_COUNT; index++) {
            candidates.add(rejected(index, candidateSeed(requestSeed, index), code).diagnostic());
        }
        return new GenerationResult.Failure(
                FailureReason.NO_VALID_CANDIDATE,
                new GenerationDiagnostics(CANDIDATE_COUNT, 0, candidates));
    }

    public sealed interface GenerationResult {
        GenerationDiagnostics diagnostics();

        record Success(Blueprint blueprint, GenerationDiagnostics diagnostics)
                implements GenerationResult {
            public Success {
                Objects.requireNonNull(blueprint, "blueprint");
                Objects.requireNonNull(diagnostics, "diagnostics");
                if (!blueprint.validation().isValid()) {
                    throw new IllegalArgumentException("A successful generation requires a valid Blueprint");
                }
            }
        }

        record Failure(FailureReason reason, GenerationDiagnostics diagnostics)
                implements GenerationResult {
            public Failure {
                Objects.requireNonNull(reason, "reason");
                Objects.requireNonNull(diagnostics, "diagnostics");
                if (diagnostics.validCandidateCount() != 0) {
                    throw new IllegalArgumentException("A failed generation cannot retain valid candidates");
                }
            }
        }
    }

    public enum FailureReason {
        NO_VALID_CANDIDATE
    }

    public enum CandidateStatus {
        VALID,
        REJECTED
    }

    public record GenerationDiagnostics(
            int candidateCount,
            int validCandidateCount,
            List<CandidateDiagnostic> candidates) {
        public GenerationDiagnostics {
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            if (candidateCount != candidates.size()) {
                throw new IllegalArgumentException("Candidate count must match diagnostics");
            }
            long actualValid = candidates.stream()
                    .filter(candidate -> candidate.status() == CandidateStatus.VALID)
                    .count();
            if (validCandidateCount != actualValid) {
                throw new IllegalArgumentException("Valid candidate count must match diagnostics");
            }
        }
    }

    public record CandidateDiagnostic(
            int index,
            long seed,
            CandidateStatus status,
            Optional<ScoreBreakdown> score,
            List<String> rejectionCodes) {
        public CandidateDiagnostic {
            Objects.requireNonNull(status, "status");
            score = Objects.requireNonNull(score, "score");
            rejectionCodes = List.copyOf(Objects.requireNonNull(rejectionCodes, "rejectionCodes"));
            if (index < 0 || index >= CANDIDATE_COUNT) {
                throw new IllegalArgumentException("Candidate index is outside the bounded batch");
            }
            if ((status == CandidateStatus.VALID) != score.isPresent()) {
                throw new IllegalArgumentException("Only valid candidates may retain scores");
            }
            if ((status == CandidateStatus.REJECTED) != !rejectionCodes.isEmpty()) {
                throw new IllegalArgumentException("Only rejected candidates must retain rejection codes");
            }
        }
    }

    private record CandidateAttempt(
            Optional<Blueprint> blueprint,
            CandidateDiagnostic diagnostic) {
    }

    private record ValidCandidate(int index, Blueprint blueprint) {
    }

    private record StairPlan(
            List<BlueprintBlock> blocks,
            Set<GridPos> reservedVolume) {
        private StairPlan {
            blocks = List.copyOf(blocks);
            reservedVolume = Set.copyOf(reservedVolume);
        }
    }

    private static final class CandidateRejectedException extends RuntimeException {
        private final String code;

        private CandidateRejectedException(String code) {
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
