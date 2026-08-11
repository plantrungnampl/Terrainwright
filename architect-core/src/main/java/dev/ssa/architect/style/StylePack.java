package dev.ssa.architect.style;

import dev.ssa.architect.material.BlockCapability;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.StyleId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public interface StylePack {
    StyleId id();

    String displayName();

    int version();

    ProportionRules proportionRules();

    FoundationRules foundationRules();

    RoofRules roofRules();

    OpeningRules openingRules();

    Map<MaterialRole, List<PaletteCandidate>> fallbackPalette();

    default Set<BlockCapability> requiredCapabilities(MaterialRole role) {
        Objects.requireNonNull(role, "role");
        return fallbackPalette().get(role).getFirst().requiredCapabilities();
    }

    static void validate(StylePack style) {
        Objects.requireNonNull(style.id(), "id");
        if (style.displayName() == null || style.displayName().isBlank()) {
            throw new IllegalArgumentException("Style display name must not be blank");
        }
        if (style.version() <= 0) {
            throw new IllegalArgumentException("Style version must be positive");
        }
        Objects.requireNonNull(style.proportionRules(), "proportionRules");
        Objects.requireNonNull(style.foundationRules(), "foundationRules");
        Objects.requireNonNull(style.roofRules(), "roofRules");
        Objects.requireNonNull(style.openingRules(), "openingRules");
        if (!style.fallbackPalette().keySet().equals(Set.of(MaterialRole.values()))) {
            throw new IllegalArgumentException("Style fallback palette must cover every canonical material role");
        }
        if (style.fallbackPalette().values().stream().anyMatch(List::isEmpty)) {
            throw new IllegalArgumentException("Style fallback palette entries must not be empty");
        }
    }

    static Map<MaterialRole, List<PaletteCandidate>> immutablePalette(
            Map<MaterialRole, ? extends List<PaletteCandidate>> palette) {
        Objects.requireNonNull(palette, "palette");
        EnumMap<MaterialRole, List<PaletteCandidate>> copy = new EnumMap<>(MaterialRole.class);
        palette.forEach((role, candidates) -> copy.put(
                Objects.requireNonNull(role, "role"),
                List.copyOf(Objects.requireNonNull(candidates, "candidates"))));
        return Collections.unmodifiableMap(copy);
    }

    enum FoundationFamily {
        STONE_BASE,
        RAISED_PILLAR,
        SLAB
    }

    enum RoofFamily {
        GABLE,
        CROSS_GABLE,
        HIP,
        WIDE_OVERHANG_HIP,
        SHED,
        FLAT
    }

    record IntRange(int minimum, int maximum) {
        public IntRange {
            if (minimum < 0 || minimum > maximum) {
                throw new IllegalArgumentException("Style range must satisfy 0 <= minimum <= maximum");
            }
        }
    }

    record ProportionRules(
            double preferredWidthDepthRatio,
            double symmetryBias,
            double openPlanBias) {
        public ProportionRules {
            if (!Double.isFinite(preferredWidthDepthRatio) || preferredWidthDepthRatio <= 0) {
                throw new IllegalArgumentException("Preferred width/depth ratio must be finite and positive");
            }
            requireUnitInterval(symmetryBias, "symmetryBias");
            requireUnitInterval(openPlanBias, "openPlanBias");
        }
    }

    record FoundationRules(
            FoundationFamily primaryFamily,
            IntRange raisedHeightRange) {
        public FoundationRules {
            Objects.requireNonNull(primaryFamily, "primaryFamily");
            Objects.requireNonNull(raisedHeightRange, "raisedHeightRange");
        }
    }

    record RoofRules(
            RoofFamily primaryFamily,
            Set<RoofFamily> supportedFamilies,
            IntRange pitchRiseRange,
            IntRange overhangRange) {
        public RoofRules {
            Objects.requireNonNull(primaryFamily, "primaryFamily");
            supportedFamilies = Set.copyOf(Objects.requireNonNull(supportedFamilies, "supportedFamilies"));
            Objects.requireNonNull(pitchRiseRange, "pitchRiseRange");
            Objects.requireNonNull(overhangRange, "overhangRange");
            if (!supportedFamilies.contains(primaryFamily)) {
                throw new IllegalArgumentException("Supported roof families must include the primary family");
            }
        }
    }

    record OpeningRules(
            IntRange windowWidthRange,
            double glazingRatio,
            boolean structuralBayAligned) {
        public OpeningRules {
            Objects.requireNonNull(windowWidthRange, "windowWidthRange");
            requireUnitInterval(glazingRatio, "glazingRatio");
        }
    }

    record PaletteCandidate(
            BlockStateSpec state,
            Set<BlockCapability> requiredCapabilities) {
        public PaletteCandidate {
            Objects.requireNonNull(state, "state");
            requiredCapabilities = Set.copyOf(
                    Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
        }
    }

    private static void requireUnitInterval(double value, String label) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(label + " must be finite and between 0 and 1");
        }
    }
}
