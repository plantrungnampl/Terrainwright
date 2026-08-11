package dev.ssa.architect.roof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.blueprint.BlueprintBlock;
import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.layout.Footprint;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.style.JapaneseStyle;
import dev.ssa.architect.style.MedievalStyle;
import dev.ssa.architect.style.ModernStyle;
import dev.ssa.architect.style.StylePack;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class RoofPlannerTest {
    @Test
    void everyBuiltInRoofCoversTheUpperFloorFootprintDeterministically() {
        Footprint footprint = Footprint.lShape(7, 6, 2, 2);
        RoofPlanner planner = new RoofPlanner();

        for (StylePack style : List.of(new MedievalStyle(), new JapaneseStyle(), new ModernStyle())) {
            BlockStateSpec roofState = state(style, MaterialRole.ROOF_PRIMARY);
            BlockStateSpec supportState = state(style, MaterialRole.STRUCTURAL_PRIMARY);
            List<BlueprintBlock> first = planner.plan(footprint, 8, style, roofState, supportState);
            List<BlueprintBlock> second = planner.plan(footprint, 8, style, roofState, supportState);
            List<BlueprintBlock> roofBlocks = first.stream()
                    .filter(block -> block.phase() == BuildPhase.ROOF)
                    .toList();
            Set<String> coveredColumns = roofBlocks.stream()
                    .map(block -> block.relativePosition().x() + ":" + block.relativePosition().z())
                    .collect(Collectors.toSet());
            Set<GridPos> plannedPositions = first.stream()
                    .map(BlueprintBlock::relativePosition)
                    .collect(Collectors.toSet());

            assertEquals(first, second);
            assertTrue(footprint.cells().stream().allMatch(cell ->
                    coveredColumns.contains(cell.x() + ":" + cell.z())));
            assertEquals(first.size(), first.stream().map(BlueprintBlock::relativePosition).distinct().count());
            assertTrue(roofBlocks.stream().allMatch(block ->
                    !block.dependencies().isEmpty() && plannedPositions.containsAll(block.dependencies())));
        }
    }

    @Test
    void styleFamiliesProduceDistinctBoundedEnvelopes() {
        Footprint footprint = Footprint.rectangle(7, 5);
        RoofPlanner planner = new RoofPlanner();
        StylePack medievalStyle = new MedievalStyle();
        StylePack japaneseStyle = new JapaneseStyle();
        StylePack modernStyle = new ModernStyle();
        List<BlueprintBlock> medieval = roofs(planner.plan(
                footprint, 8, medievalStyle,
                state(medievalStyle, MaterialRole.ROOF_PRIMARY),
                state(medievalStyle, MaterialRole.STRUCTURAL_PRIMARY)));
        List<BlueprintBlock> japanese = roofs(planner.plan(
                footprint, 8, japaneseStyle,
                state(japaneseStyle, MaterialRole.ROOF_PRIMARY),
                state(japaneseStyle, MaterialRole.STRUCTURAL_PRIMARY)));
        List<BlueprintBlock> modern = roofs(planner.plan(
                footprint, 8, modernStyle,
                state(modernStyle, MaterialRole.ROOF_PRIMARY),
                state(modernStyle, MaterialRole.STRUCTURAL_PRIMARY)));

        assertTrue(medieval.stream().map(block -> block.relativePosition().y()).distinct().count() > 1);
        assertTrue(japanese.stream().anyMatch(block -> outside(block.relativePosition(), footprint)));
        assertEquals(Set.of(8), modern.stream()
                .map(block -> block.relativePosition().y())
                .collect(Collectors.toSet()));
        assertTrue(japanese.size() > medieval.size());
        assertTrue(medieval.stream()
                .map(block -> block.placementState().properties().get("facing"))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count() > 1);
    }

    @Test
    void stairRoofFacingsFollowThePitchGradient() {
        Footprint footprint = Footprint.rectangle(9, 7);
        StylePack style = new MedievalStyle();
        List<BlueprintBlock> roofs = roofs(new RoofPlanner().plan(
                footprint,
                8,
                style,
                state(style, MaterialRole.ROOF_PRIMARY),
                state(style, MaterialRole.STRUCTURAL_PRIMARY)));
        Map<String, BlueprintBlock> byColumn = roofs.stream().collect(Collectors.toMap(
                block -> column(block.relativePosition()),
                block -> block));

        for (BlueprintBlock roof : roofs) {
            String facing = roof.placementState().properties().get("facing");
            if (facing == null) {
                continue;
            }
            List<BlueprintBlock> neighbors = neighbors(roof.relativePosition()).stream()
                    .map(position -> byColumn.get(column(position)))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            Set<String> uphill = neighbors.stream()
                    .filter(neighbor -> neighbor.relativePosition().y() > roof.relativePosition().y())
                    .map(neighbor -> direction(roof.relativePosition(), neighbor.relativePosition()))
                    .collect(Collectors.toSet());
            if (!uphill.isEmpty()) {
                assertTrue(uphill.contains(facing));
                continue;
            }
            Set<String> awayFromDownhill = neighbors.stream()
                    .filter(neighbor -> neighbor.relativePosition().y() < roof.relativePosition().y())
                    .map(neighbor -> opposite(direction(
                            roof.relativePosition(), neighbor.relativePosition())))
                    .collect(Collectors.toSet());
            assertTrue(awayFromDownhill.isEmpty() || awayFromDownhill.contains(facing));
        }
    }

    private static boolean outside(GridPos position, Footprint footprint) {
        return position.x() < 0
                || position.z() < 0
                || position.x() >= footprint.width()
                || position.z() >= footprint.depth();
    }

    private static List<BlueprintBlock> roofs(List<BlueprintBlock> blocks) {
        return blocks.stream().filter(block -> block.phase() == BuildPhase.ROOF).toList();
    }

    private static BlockStateSpec state(StylePack style, MaterialRole role) {
        return style.fallbackPalette().get(role).getFirst().state();
    }

    private static Set<GridPos> neighbors(GridPos position) {
        return Set.of(
                new GridPos(position.x(), position.y(), position.z() - 1),
                new GridPos(position.x() + 1, position.y(), position.z()),
                new GridPos(position.x(), position.y(), position.z() + 1),
                new GridPos(position.x() - 1, position.y(), position.z()));
    }

    private static String column(GridPos position) {
        return position.x() + ":" + position.z();
    }

    private static String direction(GridPos from, GridPos to) {
        if (to.x() > from.x()) {
            return "east";
        }
        if (to.x() < from.x()) {
            return "west";
        }
        return to.z() > from.z() ? "south" : "north";
    }

    private static String opposite(String direction) {
        return switch (direction) {
            case "north" -> "south";
            case "east" -> "west";
            case "south" -> "north";
            case "west" -> "east";
            default -> throw new IllegalArgumentException("Unknown direction: " + direction);
        };
    }
}
