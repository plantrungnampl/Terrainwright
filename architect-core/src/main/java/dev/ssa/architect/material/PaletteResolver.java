package dev.ssa.architect.material;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.style.StylePack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class PaletteResolver {
    private final Map<MaterialRole, List<StylePack.PaletteCandidate>> fallbackPalette;
    private final Map<MaterialRole, Set<BlockCapability>> requiredCapabilities;
    private final Map<MaterialRole, List<StylePack.PaletteCandidate>> overrides;

    public PaletteResolver(StylePack stylePack) {
        this(stylePack, Map.of());
    }

    public PaletteResolver(
            StylePack stylePack,
            Map<MaterialRole, ? extends List<StylePack.PaletteCandidate>> overrides) {
        Objects.requireNonNull(stylePack, "stylePack");
        StylePack.validate(stylePack);
        this.fallbackPalette = StylePack.immutablePalette(stylePack.fallbackPalette());
        EnumMap<MaterialRole, Set<BlockCapability>> roleRequirements = new EnumMap<>(MaterialRole.class);
        fallbackPalette.forEach((role, candidates) -> roleRequirements.put(
                role,
                Set.copyOf(candidates.getFirst().requiredCapabilities())));
        this.requiredCapabilities = Collections.unmodifiableMap(roleRequirements);
        Objects.requireNonNull(overrides, "overrides");
        EnumMap<MaterialRole, List<StylePack.PaletteCandidate>> copy = new EnumMap<>(MaterialRole.class);
        overrides.forEach((role, candidates) -> copy.put(
                Objects.requireNonNull(role, "role"),
                List.copyOf(Objects.requireNonNull(candidates, "candidates"))));
        this.overrides = Collections.unmodifiableMap(copy);
    }

    public Optional<BlockStateSpec> resolve(
            MaterialRole role,
            BlockCapabilityRegistry registry) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(registry, "registry");
        List<StylePack.PaletteCandidate> candidates = new ArrayList<>();
        candidates.addAll(overrides.getOrDefault(role, List.of()));
        candidates.addAll(fallbackPalette.get(role));
        for (StylePack.PaletteCandidate candidate : candidates) {
            Set<BlockCapability> candidateRequirements = new HashSet<>(requiredCapabilities.get(role));
            candidateRequirements.addAll(candidate.requiredCapabilities());
            Optional<Set<BlockCapability>> capabilities = registry.capabilities(candidate.state().blockId());
            if (capabilities.isPresent()
                    && capabilities.orElseThrow().containsAll(candidateRequirements)
                    && registry.supports(candidate.state())) {
                return Optional.of(candidate.state());
            }
        }
        return Optional.empty();
    }
}
