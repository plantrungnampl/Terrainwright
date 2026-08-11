package dev.ssa.architect.style;

import dev.ssa.architect.material.BlockCapability;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.NamespacedId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BuiltinStylePalettes {
    private BuiltinStylePalettes() {
    }

    static Map<MaterialRole, List<StylePack.PaletteCandidate>> medieval() {
        EnumMap<MaterialRole, List<StylePack.PaletteCandidate>> palette = palette();
        put(palette, MaterialRole.FOUNDATION_STONE, full("minecraft:cobblestone"));
        put(palette, MaterialRole.FOUNDATION_FILL, full("minecraft:dirt"));
        put(palette, MaterialRole.STRUCTURAL_WOOD, axis("minecraft:oak_log"));
        put(palette, MaterialRole.STRUCTURAL_PRIMARY, axis("minecraft:oak_log"));
        put(palette, MaterialRole.WALL_PRIMARY, full("minecraft:oak_planks"));
        put(palette, MaterialRole.WALL_SECONDARY, full("minecraft:white_wool"));
        put(palette, MaterialRole.FLOOR_PRIMARY, full("minecraft:oak_planks"));
        put(palette, MaterialRole.FLOOR_SECONDARY, full("minecraft:stone_bricks"));
        put(palette, MaterialRole.ROOF_PRIMARY, stair("minecraft:dark_oak_stairs"));
        put(palette, MaterialRole.ROOF_ACCENT, slab("minecraft:dark_oak_slab"));
        put(palette, MaterialRole.TRIM, axis("minecraft:stripped_oak_log"));
        put(palette, MaterialRole.WINDOW, pane("minecraft:glass_pane"));
        put(palette, MaterialRole.DOOR, door("minecraft:oak_door"));
        put(palette, MaterialRole.RAILING, fence("minecraft:oak_fence"));
        put(palette, MaterialRole.STAIR, stair("minecraft:oak_stairs"));
        put(palette, MaterialRole.INTERIOR_PRIMARY, full("minecraft:oak_planks"));
        put(palette, MaterialRole.LIGHTING, light("minecraft:lantern"));
        put(palette, MaterialRole.TEMP_SCAFFOLD, present("minecraft:scaffolding"));
        return StylePack.immutablePalette(palette);
    }

    static Map<MaterialRole, List<StylePack.PaletteCandidate>> japanese() {
        EnumMap<MaterialRole, List<StylePack.PaletteCandidate>> palette = palette();
        put(palette, MaterialRole.FOUNDATION_STONE, full("minecraft:stone_bricks"));
        put(palette, MaterialRole.FOUNDATION_FILL, full("minecraft:dirt"));
        put(palette, MaterialRole.STRUCTURAL_WOOD, axis("minecraft:dark_oak_log"));
        put(palette, MaterialRole.STRUCTURAL_PRIMARY, axis("minecraft:dark_oak_log"));
        put(palette, MaterialRole.WALL_PRIMARY, full("minecraft:white_concrete"));
        put(palette, MaterialRole.WALL_SECONDARY, full("minecraft:spruce_planks"));
        put(palette, MaterialRole.FLOOR_PRIMARY, full("minecraft:spruce_planks"));
        put(palette, MaterialRole.FLOOR_SECONDARY, full("minecraft:dark_oak_planks"));
        put(palette, MaterialRole.ROOF_PRIMARY, stair("minecraft:deepslate_tile_stairs"));
        put(palette, MaterialRole.ROOF_ACCENT, slab("minecraft:deepslate_tile_slab"));
        put(palette, MaterialRole.TRIM, axis("minecraft:stripped_dark_oak_log"));
        put(palette, MaterialRole.WINDOW, pane("minecraft:glass_pane"));
        put(palette, MaterialRole.DOOR, door("minecraft:dark_oak_door"));
        put(palette, MaterialRole.RAILING, fence("minecraft:dark_oak_fence"));
        put(palette, MaterialRole.STAIR, stair("minecraft:spruce_stairs"));
        put(palette, MaterialRole.INTERIOR_PRIMARY, full("minecraft:white_wool"));
        put(palette, MaterialRole.LIGHTING, light("minecraft:lantern"));
        put(palette, MaterialRole.TEMP_SCAFFOLD, present("minecraft:scaffolding"));
        return StylePack.immutablePalette(palette);
    }

    static Map<MaterialRole, List<StylePack.PaletteCandidate>> modern() {
        EnumMap<MaterialRole, List<StylePack.PaletteCandidate>> palette = palette();
        put(palette, MaterialRole.FOUNDATION_STONE, full("minecraft:smooth_stone"));
        put(palette, MaterialRole.FOUNDATION_FILL, full("minecraft:dirt"));
        put(palette, MaterialRole.STRUCTURAL_WOOD, axis("minecraft:stripped_oak_log"));
        put(palette, MaterialRole.STRUCTURAL_PRIMARY, full("minecraft:iron_block"));
        put(palette, MaterialRole.WALL_PRIMARY, full("minecraft:white_concrete"));
        put(palette, MaterialRole.WALL_SECONDARY, full("minecraft:gray_concrete"));
        put(palette, MaterialRole.FLOOR_PRIMARY, full("minecraft:smooth_stone"));
        put(palette, MaterialRole.FLOOR_SECONDARY, full("minecraft:oak_planks"));
        put(palette, MaterialRole.ROOF_PRIMARY, slab("minecraft:smooth_quartz_slab"));
        put(palette, MaterialRole.ROOF_ACCENT, full("minecraft:gray_concrete"));
        put(palette, MaterialRole.TRIM, full("minecraft:black_concrete"));
        put(palette, MaterialRole.WINDOW, full("minecraft:glass"));
        put(palette, MaterialRole.DOOR, door("minecraft:iron_door"));
        put(palette, MaterialRole.RAILING, pane("minecraft:glass_pane"));
        put(palette, MaterialRole.STAIR, stair("minecraft:quartz_stairs"));
        put(palette, MaterialRole.INTERIOR_PRIMARY, full("minecraft:white_concrete"));
        put(palette, MaterialRole.LIGHTING, light("minecraft:sea_lantern"));
        put(palette, MaterialRole.TEMP_SCAFFOLD, present("minecraft:scaffolding"));
        return StylePack.immutablePalette(palette);
    }

    private static EnumMap<MaterialRole, List<StylePack.PaletteCandidate>> palette() {
        return new EnumMap<>(MaterialRole.class);
    }

    private static void put(
            EnumMap<MaterialRole, List<StylePack.PaletteCandidate>> palette,
            MaterialRole role,
            StylePack.PaletteCandidate candidate) {
        palette.put(role, List.of(candidate));
    }

    private static StylePack.PaletteCandidate full(String blockId) {
        return candidate(blockId, Map.of(), Set.of(BlockCapability.FULL_CUBE));
    }

    private static StylePack.PaletteCandidate axis(String blockId) {
        return candidate(
                blockId,
                Map.of("axis", "y"),
                Set.of(BlockCapability.ORIENTABLE_AXIS));
    }

    private static StylePack.PaletteCandidate stair(String blockId) {
        return candidate(
                blockId,
                Map.of("facing", "north"),
                Set.of(BlockCapability.STAIR, BlockCapability.HORIZONTAL_FACING));
    }

    private static StylePack.PaletteCandidate slab(String blockId) {
        return candidate(blockId, Map.of("type", "bottom"), Set.of(BlockCapability.SLAB));
    }

    private static StylePack.PaletteCandidate pane(String blockId) {
        return candidate(blockId, Map.of(), Set.of(BlockCapability.PANE));
    }

    private static StylePack.PaletteCandidate door(String blockId) {
        return candidate(
                blockId,
                Map.of("facing", "north"),
                Set.of(BlockCapability.DOOR, BlockCapability.HORIZONTAL_FACING));
    }

    private static StylePack.PaletteCandidate fence(String blockId) {
        return candidate(blockId, Map.of(), Set.of(BlockCapability.FENCE_OR_WALL));
    }

    private static StylePack.PaletteCandidate light(String blockId) {
        return candidate(blockId, Map.of(), Set.of(BlockCapability.LIGHT_SOURCE));
    }

    private static StylePack.PaletteCandidate present(String blockId) {
        return candidate(blockId, Map.of(), Set.of());
    }

    private static StylePack.PaletteCandidate candidate(
            String blockId,
            Map<String, String> properties,
            Set<BlockCapability> capabilities) {
        return new StylePack.PaletteCandidate(
                new BlockStateSpec(NamespacedId.parse(blockId), properties),
                capabilities);
    }
}
