package dev.ssa.architect.terrain;

public record TerrainBudget(
        int maxRemovedBlocks,
        int maxFilledBlocks,
        int maxVerticalCut,
        int maxVerticalFill,
        boolean waterModification,
        boolean lavaModification) {
    public TerrainBudget {
        if (maxRemovedBlocks < 0
                || maxFilledBlocks < 0
                || maxVerticalCut < 0
                || maxVerticalFill < 0) {
            throw new IllegalArgumentException("terrain budget limits must not be negative");
        }
    }

    public static TerrainBudget light() {
        return new TerrainBudget(150, 180, 3, 4, false, false);
    }
}
