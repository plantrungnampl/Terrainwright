package dev.ssa.architect.style;

import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.StyleId;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ModernStyle implements StylePack {
    private static final StyleId ID = StyleId.parse("smart_survival_architect:modern");
    private static final ProportionRules PROPORTIONS = new ProportionRules(1.2, 0.25, 0.90);
    private static final FoundationRules FOUNDATION =
            new FoundationRules(FoundationFamily.SLAB, new IntRange(0, 1));
    private static final RoofRules ROOF = new RoofRules(
            RoofFamily.FLAT,
            Set.of(RoofFamily.FLAT, RoofFamily.SHED),
            new IntRange(0, 1),
            new IntRange(0, 1));
    private static final OpeningRules OPENINGS = new OpeningRules(new IntRange(2, 5), 0.70, false);
    private static final Map<MaterialRole, List<PaletteCandidate>> PALETTE = BuiltinStylePalettes.modern();

    public ModernStyle() {
        StylePack.validate(this);
    }

    @Override
    public StyleId id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Modern";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public ProportionRules proportionRules() {
        return PROPORTIONS;
    }

    @Override
    public FoundationRules foundationRules() {
        return FOUNDATION;
    }

    @Override
    public RoofRules roofRules() {
        return ROOF;
    }

    @Override
    public OpeningRules openingRules() {
        return OPENINGS;
    }

    @Override
    public Map<MaterialRole, List<PaletteCandidate>> fallbackPalette() {
        return PALETTE;
    }
}
