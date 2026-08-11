package dev.ssa.architect.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.model.EntrancePreference;
import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.StyleId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class RoomGraphGeneratorTest {
    @Test
    void generatesEveryRequestedRoomWithStableFloorsAndConnections() {
        RoomGraph graph = new RoomGraphGenerator().generate(requirements(3, 4, true, true, true));

        assertEquals("entrance", graph.entranceRoomId());
        assertEquals(
                Set.of(
                        "entrance", "living", "kitchen", "storage",
                        "stairs_1", "upper_hall_1", "stairs_2", "upper_hall_2",
                        "bedroom_1", "bedroom_2", "bedroom_3", "bedroom_4", "balcony"),
                graph.nodes().stream().map(RoomGraph.Node::id).collect(Collectors.toUnmodifiableSet()));
        assertEquals(1, graph.node("bedroom_1").floor());
        assertEquals(2, graph.node("bedroom_2").floor());
        assertEquals(1, graph.node("bedroom_3").floor());
        assertEquals(RoomGraph.ExteriorPreference.REQUIRED, graph.node("entrance").exteriorPreference());
        assertEquals(RoomGraph.ExteriorPreference.PREFERRED, graph.node("kitchen").exteriorPreference());
        assertTrue(graph.isConnected());
        assertTrue(graph.edges().contains(new RoomGraph.Edge("entrance", "living")));
        assertTrue(graph.edges().contains(new RoomGraph.Edge("kitchen", "storage")));
        assertTrue(graph.edges().contains(new RoomGraph.Edge(
                "living", "stairs_1", RoomGraph.Transition.STAIR)));
        assertThrows(UnsupportedOperationException.class, () -> graph.nodes().clear());
    }

    @Test
    void omitsUnrequestedOptionalRooms() {
        RoomGraph graph = new RoomGraphGenerator().generate(requirements(1, 0, false, false, false));

        assertEquals(Set.of("entrance", "living"),
                graph.nodes().stream().map(RoomGraph.Node::id).collect(Collectors.toUnmodifiableSet()));
        assertEquals(Set.of(new RoomGraph.Edge("entrance", "living")), graph.edges());
    }

    @Test
    void rejectsDisconnectedOrMalformedGraphs() {
        RoomGraph.Node entrance = node("entrance");
        RoomGraph.Node living = node("living");

        assertThrows(IllegalArgumentException.class,
                () -> new RoomGraph(List.of(entrance, living), Set.of(), "entrance"));
        assertThrows(IllegalArgumentException.class,
                () -> new RoomGraph(List.of(entrance, entrance), Set.of(), "entrance"));
        assertThrows(IllegalArgumentException.class,
                () -> new RoomGraph.Edge("entrance", "entrance"));
        assertThrows(IllegalArgumentException.class,
                () -> new RoomGraph(List.of(entrance), Set.of(), "missing"));

        RoomGraph.Node upperBedroom = new RoomGraph.Node(
                "upper_bedroom",
                NamespacedId.parse("smart_survival_architect:bedroom"),
                1,
                6,
                RoomGraph.ExteriorPreference.PREFERRED);
        assertThrows(IllegalArgumentException.class, () -> new RoomGraph(
                List.of(entrance, upperBedroom),
                Set.of(new RoomGraph.Edge("entrance", "upper_bedroom")),
                "entrance"));
        assertThrows(IllegalArgumentException.class, () -> new RoomGraph(
                List.of(entrance, upperBedroom),
                Set.of(new RoomGraph.Edge(
                        "entrance", "upper_bedroom", RoomGraph.Transition.STAIR)),
                "entrance"));
    }

    private static RoomGraph.Node node(String id) {
        return new RoomGraph.Node(
                id,
                NamespacedId.parse("smart_survival_architect:" + id),
                0,
                4,
                RoomGraph.ExteriorPreference.NONE);
    }

    private static HouseRequirements requirements(
            int floors,
            int bedrooms,
            boolean kitchen,
            boolean storage,
            boolean balcony) {
        return new HouseRequirements(
                StyleId.parse("smart_survival_architect:medieval"),
                15,
                19,
                floors,
                bedrooms,
                kitchen,
                storage,
                balcony,
                false,
                EntrancePreference.AUTO,
                77L);
    }
}
