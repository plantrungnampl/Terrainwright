package dev.ssa.architect.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BlockStateSpecTest {
    @Test
    void propertiesAreCopiedSortedAndUnmodifiable() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("waterlogged", "false");
        input.put("half", "top");
        input.put("facing", "north");

        BlockStateSpec state = BlockStateSpec.of(
                NamespacedId.parse("minecraft:oak_stairs"), input);
        input.put("shape", "inner_left");

        assertEquals(
                java.util.List.of("facing", "half", "waterlogged"),
                new ArrayList<>(state.properties().keySet()));
        assertEquals("north", state.properties().get("facing"));
        assertFalse(state.properties().containsKey("shape"));
        assertThrows(UnsupportedOperationException.class,
                () -> state.properties().put("shape", "inner_left"));
    }

    @Test
    void emptyPropertiesRepresentTheCanonicalDefaultState() {
        BlockStateSpec state = BlockStateSpec.of(
                NamespacedId.parse("minecraft:stone"), Map.of());

        assertEquals(Map.of(), state.properties());
    }

    @Test
    void rejectsNullAndNonCanonicalProperties() {
        NamespacedId stairs = NamespacedId.parse("minecraft:oak_stairs");

        assertThrows(NullPointerException.class, () -> BlockStateSpec.of(null, Map.of()));
        assertThrows(NullPointerException.class, () -> BlockStateSpec.of(stairs, null));
        assertThrows(IllegalArgumentException.class,
                () -> BlockStateSpec.of(stairs, Map.of("Facing", "north")));
        assertThrows(IllegalArgumentException.class,
                () -> BlockStateSpec.of(stairs, Map.of("facing", "")));
        assertThrows(IllegalArgumentException.class,
                () -> BlockStateSpec.of(stairs, Map.of("facing", "north west")));
    }
}
