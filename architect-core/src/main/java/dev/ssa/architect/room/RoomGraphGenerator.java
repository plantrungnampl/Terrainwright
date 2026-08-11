package dev.ssa.architect.room;

import dev.ssa.architect.model.HouseRequirements;
import dev.ssa.architect.model.NamespacedId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class RoomGraphGenerator {
    private static final String TYPE_NAMESPACE = "smart_survival_architect:";

    public RoomGraph generate(HouseRequirements requirements) {
        Objects.requireNonNull(requirements, "requirements");
        List<RoomGraph.Node> nodes = new ArrayList<>();
        Set<RoomGraph.Edge> edges = new LinkedHashSet<>();

        add(nodes, "entrance", "entrance", 0, 4, RoomGraph.ExteriorPreference.REQUIRED);
        add(nodes, "living", "living", 0, 12, RoomGraph.ExteriorPreference.PREFERRED);
        connect(edges, "entrance", "living");

        if (requirements.kitchen()) {
            add(nodes, "kitchen", "kitchen", 0, 6, RoomGraph.ExteriorPreference.PREFERRED);
            connect(edges, "living", "kitchen");
        }
        if (requirements.storage()) {
            add(nodes, "storage", "storage", 0, 4, RoomGraph.ExteriorPreference.NONE);
            connect(edges, requirements.kitchen() ? "kitchen" : "living", "storage");
        }

        for (int floor = 1; floor < requirements.floors(); floor++) {
            String stairs = "stairs_" + floor;
            String hall = "upper_hall_" + floor;
            add(nodes, stairs, "stairs", floor, 4, RoomGraph.ExteriorPreference.NONE);
            add(nodes, hall, "upper_hall", floor, 6, RoomGraph.ExteriorPreference.PREFERRED);
            connect(
                    edges,
                    floor == 1 ? "living" : "upper_hall_" + (floor - 1),
                    stairs,
                    RoomGraph.Transition.STAIR);
            connect(edges, stairs, hall);
        }

        for (int index = 1; index <= requirements.bedrooms(); index++) {
            int floor = requirements.floors() == 1
                    ? 0
                    : 1 + ((index - 1) % (requirements.floors() - 1));
            String bedroom = "bedroom_" + index;
            add(nodes, bedroom, "bedroom", floor, 6, RoomGraph.ExteriorPreference.PREFERRED);
            connect(edges, floor == 0 ? "living" : "upper_hall_" + floor, bedroom);
        }

        if (requirements.balcony()) {
            int floor = requirements.floors() == 1 ? 0 : 1;
            add(nodes, "balcony", "balcony", floor, 4, RoomGraph.ExteriorPreference.REQUIRED);
            connect(edges, floor == 0 ? "living" : "upper_hall_1", "balcony");
        }

        return new RoomGraph(nodes, edges, "entrance");
    }

    private static void add(
            List<RoomGraph.Node> nodes,
            String id,
            String type,
            int floor,
            int minimumArea,
            RoomGraph.ExteriorPreference exteriorPreference) {
        nodes.add(new RoomGraph.Node(
                id,
                NamespacedId.parse(TYPE_NAMESPACE + type),
                floor,
                minimumArea,
                exteriorPreference));
    }

    private static void connect(Set<RoomGraph.Edge> edges, String first, String second) {
        edges.add(new RoomGraph.Edge(first, second));
    }

    private static void connect(
            Set<RoomGraph.Edge> edges,
            String first,
            String second,
            RoomGraph.Transition transition) {
        edges.add(new RoomGraph.Edge(first, second, transition));
    }
}
