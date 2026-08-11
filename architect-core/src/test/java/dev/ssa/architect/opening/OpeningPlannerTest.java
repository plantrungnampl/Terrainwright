package dev.ssa.architect.opening;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.layout.FloorLayout;
import dev.ssa.architect.layout.Footprint;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.room.RoomGraph;
import dev.ssa.architect.style.MedievalStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class OpeningPlannerTest {
    @Test
    void placesEntranceTransitionDoorAndRoomAwareWindowsDeterministically() {
        Footprint footprint = Footprint.rectangle(9, 6);
        RoomGraph graph = graph();
        FloorLayout layout = layout(footprint, graph);
        OpeningPlanner planner = new OpeningPlanner();

        OpeningPlanner.OpeningPlan first = planner.plan(
                graph, layout, footprint, 4, new MedievalStyle(), 91L);
        OpeningPlanner.OpeningPlan second = planner.plan(
                graph, layout, footprint, 4, new MedievalStyle(), 91L);

        assertEquals(first, second);
        assertEquals(1, first.count(OpeningPlanner.OpeningType.ENTRANCE_DOOR));
        assertEquals(3, first.count(OpeningPlanner.OpeningType.INTERIOR_DOOR));
        assertEquals(4, first.count(OpeningPlanner.OpeningType.WINDOW));
        assertEquals(first.openings().size(), first.openings().stream()
                .map(OpeningPlanner.Opening::relativePosition)
                .distinct()
                .count());
        assertTrue(first.openings().stream()
                .filter(opening -> opening.type() == OpeningPlanner.OpeningType.WINDOW)
                .allMatch(opening -> Set.of("living", "kitchen", "bedroom").contains(opening.roomId())));
    }

    @Test
    void keepsWindowsClearOfCornersAdjacentBaysAndTwoBlockDoors() {
        Footprint footprint = Footprint.rectangle(9, 6);
        RoomGraph graph = graph();
        FloorLayout layout = layout(footprint, graph);
        OpeningPlanner planner = new OpeningPlanner();

        for (long seed = 0; seed < 32; seed++) {
            OpeningPlanner.OpeningPlan plan = planner.plan(
                    graph, layout, footprint, 4, new MedievalStyle(), seed);
            List<OpeningPlanner.Opening> windows = plan.openings().stream()
                    .filter(opening -> opening.type() == OpeningPlanner.OpeningType.WINDOW)
                    .toList();
            assertTrue(windows.stream().noneMatch(opening -> isFootprintCorner(
                    opening.relativePosition(), footprint)));
            for (int first = 0; first < windows.size(); first++) {
                for (int second = first + 1; second < windows.size(); second++) {
                    if (windows.get(first).roomId().equals(windows.get(second).roomId())) {
                        assertTrue(horizontalDistance(
                                windows.get(first).relativePosition(),
                                windows.get(second).relativePosition()) > 1);
                    }
                }
            }
            assertTrue(plan.openings().stream()
                    .filter(opening -> opening.type() == OpeningPlanner.OpeningType.WINDOW)
                    .noneMatch(window -> plan.openings().stream()
                            .filter(opening -> opening.type() != OpeningPlanner.OpeningType.WINDOW)
                            .anyMatch(door -> window.relativePosition().equals(new dev.ssa.architect.model.GridPos(
                                    door.relativePosition().x(),
                                    door.relativePosition().y() + 1,
                                    door.relativePosition().z())))));
            for (OpeningPlanner.Opening window : windows) {
                assertTrue(plan.openings().stream()
                        .filter(opening -> opening != window)
                        .filter(opening -> Math.abs(
                                opening.relativePosition().y() - window.relativePosition().y()) <= 1)
                        .allMatch(opening -> horizontalDistance(
                                opening.relativePosition(), window.relativePosition()) > 1));
            }
        }
    }

    @Test
    void failsWhenOnlyCornerOpeningsExist() {
        Footprint footprint = Footprint.rectangle(1, 1);
        RoomGraph graph = new RoomGraph(
                List.of(node("entrance", "entrance", 1, RoomGraph.ExteriorPreference.REQUIRED)),
                Set.of(),
                "entrance");
        FloorLayout layout = new FloorLayout(
                Map.of("entrance", placed("entrance", Set.of(new Footprint.Cell(0, 0)))),
                Set.of(),
                "entrance");

        assertThrows(IllegalStateException.class, () -> new OpeningPlanner().plan(
                graph, layout, footprint, 4, new MedievalStyle(), 1L));
    }

    private static RoomGraph graph() {
        List<RoomGraph.Node> nodes = List.of(
                node("entrance", "entrance", 6, RoomGraph.ExteriorPreference.REQUIRED),
                node("living", "living", 18, RoomGraph.ExteriorPreference.PREFERRED),
                node("kitchen", "kitchen", 12, RoomGraph.ExteriorPreference.PREFERRED),
                node("bedroom", "bedroom", 18, RoomGraph.ExteriorPreference.PREFERRED));
        Set<RoomGraph.Edge> edges = Set.of(
                new RoomGraph.Edge("entrance", "living"),
                new RoomGraph.Edge("living", "kitchen"),
                new RoomGraph.Edge("kitchen", "bedroom"));
        return new RoomGraph(nodes, edges, "entrance");
    }

    private static FloorLayout layout(Footprint footprint, RoomGraph graph) {
        Map<String, FloorLayout.PlacedRoom> rooms = new LinkedHashMap<>();
        rooms.put("entrance", placed("entrance", cells(0, 0, 1, 6)));
        rooms.put("living", placed("living", cells(1, 0, 3, 6)));
        rooms.put("kitchen", placed("kitchen", cells(4, 0, 2, 6)));
        rooms.put("bedroom", placed("bedroom", cells(6, 0, 3, 6)));
        FloorLayout layout = new FloorLayout(rooms, graph.edges(), "entrance");
        assertTrue(layout.realizes(graph));
        return layout;
    }

    private static RoomGraph.Node node(
            String id,
            String type,
            int minimumArea,
            RoomGraph.ExteriorPreference exteriorPreference) {
        return new RoomGraph.Node(
                id,
                NamespacedId.parse("smart_survival_architect:" + type),
                0,
                minimumArea,
                exteriorPreference);
    }

    private static FloorLayout.PlacedRoom placed(String id, Set<Footprint.Cell> cells) {
        return new FloorLayout.PlacedRoom(id, 0, cells);
    }

    private static Set<Footprint.Cell> cells(int startX, int startZ, int width, int depth) {
        java.util.HashSet<Footprint.Cell> cells = new java.util.HashSet<>();
        for (int x = startX; x < startX + width; x++) {
            for (int z = startZ; z < startZ + depth; z++) {
                cells.add(new Footprint.Cell(x, z));
            }
        }
        return Set.copyOf(cells);
    }

    private static boolean isFootprintCorner(
            dev.ssa.architect.model.GridPos position,
            Footprint footprint) {
        boolean edgeX = position.x() == 0 || position.x() == footprint.width() - 1;
        boolean edgeZ = position.z() == 0 || position.z() == footprint.depth() - 1;
        return edgeX && edgeZ;
    }

    private static int horizontalDistance(
            dev.ssa.architect.model.GridPos first,
            dev.ssa.architect.model.GridPos second) {
        return Math.abs(first.x() - second.x()) + Math.abs(first.z() - second.z());
    }
}
