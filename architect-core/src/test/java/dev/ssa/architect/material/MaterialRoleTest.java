package dev.ssa.architect.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class MaterialRoleTest {
    private static final Set<String> CANONICAL_ROLES = Set.of(
            "FOUNDATION_STONE",
            "FOUNDATION_FILL",
            "STRUCTURAL_WOOD",
            "STRUCTURAL_PRIMARY",
            "WALL_PRIMARY",
            "WALL_SECONDARY",
            "FLOOR_PRIMARY",
            "FLOOR_SECONDARY",
            "ROOF_PRIMARY",
            "ROOF_ACCENT",
            "TRIM",
            "WINDOW",
            "DOOR",
            "RAILING",
            "STAIR",
            "INTERIOR_PRIMARY",
            "LIGHTING",
            "TEMP_SCAFFOLD");

    @Test
    void exposesExactlyTheCanonicalPersistedVocabulary() {
        assertEquals(
                CANONICAL_ROLES,
                Stream.of(MaterialRole.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet()));
    }

    @Test
    void rejectsAliasesInsteadOfSilentlyMigratingThem() {
        assertEquals(MaterialRole.FOUNDATION_STONE, MaterialRole.parse("FOUNDATION_STONE"));
        assertThrows(IllegalArgumentException.class, () -> MaterialRole.parse("STONE_BASE"));
        assertThrows(IllegalArgumentException.class, () -> MaterialRole.parse("foundation_stone"));
    }
}
