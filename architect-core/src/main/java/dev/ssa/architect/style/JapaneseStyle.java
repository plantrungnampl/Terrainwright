package dev.ssa.architect.style;

import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.StyleId;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JapaneseStyle implements StylePack {
    private static final StyleId ID = StyleId.parse("smart_survival_architect:japanese");
    private static final ProportionRules PROPORTIONS = new ProportionRules(1.0, 0.85, 0.45);
    private static final FoundationRules FOUNDATION =
            new FoundationRules(FoundationFamily.RAISED_PILLAR, new IntRange(1, 3));
    private static final RoofRules ROOF = new RoofRules(
            RoofFamily.WIDE_OVERHANG_HIP,
            Set.of(RoofFamily.WIDE_OVERHANG_HIP, RoofFamily.HIP),
            new IntRange(1, 3),
            new IntRange(2, 4));
    private static final OpeningRules OPENINGS = new OpeningRules(new IntRange(2, 3), 0.40, true);
    private static final Map<MaterialRole, List<PaletteCandidate>> PALETTE = BuiltinStylePalettes.japanese();

    public JapaneseStyle() {
        StylePack.validate(this);
    }

    @Override
    public StyleId id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Japanese";
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
