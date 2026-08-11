package dev.ssa.architect.material;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.NamespacedId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public interface BlockCapabilityRegistry {
    Optional<Set<BlockCapability>> capabilities(NamespacedId blockId);

    boolean supports(BlockStateSpec state);

    static BlockCapabilityRegistry of(Map<NamespacedId, ? extends Set<BlockCapability>> entries) {
        Objects.requireNonNull(entries, "entries");
        Map<NamespacedId, Set<BlockCapability>> copy = new HashMap<>();
        entries.forEach((blockId, capabilities) -> copy.put(
                Objects.requireNonNull(blockId, "blockId"),
                Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"))));
        Map<NamespacedId, Set<BlockCapability>> snapshot = Map.copyOf(copy);
        return new BlockCapabilityRegistry() {
            @Override
            public Optional<Set<BlockCapability>> capabilities(NamespacedId blockId) {
                return Optional.ofNullable(snapshot.get(Objects.requireNonNull(blockId, "blockId")));
            }

            @Override
            public boolean supports(BlockStateSpec state) {
                Objects.requireNonNull(state, "state");
                Optional<Set<BlockCapability>> capabilities = capabilities(state.blockId());
                if (capabilities.isEmpty()) {
                    return false;
                }
                return state.properties().entrySet().stream()
                        .allMatch(property -> supportsProperty(
                                capabilities.orElseThrow(),
                                property.getKey(),
                                property.getValue()));
            }
        };
    }

    private static boolean supportsProperty(
            Set<BlockCapability> capabilities,
            String property,
            String value) {
        return switch (property) {
            case "facing" -> capabilities.contains(BlockCapability.HORIZONTAL_FACING)
                    && Set.of("north", "east", "south", "west").contains(value);
            case "axis" -> capabilities.contains(BlockCapability.ORIENTABLE_AXIS)
                    && Set.of("x", "y", "z").contains(value);
            case "type" -> capabilities.contains(BlockCapability.SLAB)
                    && Set.of("bottom", "top", "double").contains(value);
            case "half" -> ((capabilities.contains(BlockCapability.STAIR)
                            && Set.of("bottom", "top").contains(value))
                    || (capabilities.contains(BlockCapability.DOOR)
                            && Set.of("lower", "upper").contains(value)));
            default -> false;
        };
    }
}
