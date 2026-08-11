package dev.ssa.architect.style;

import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.StyleId;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MedievalStyle implements StylePack {
    private static final StyleId ID = StyleId.parse("smart_survival_architect:medieval");
    private static final ProportionRules PROPORTIONS = new ProportionRules(1.0, 0.45, 0.25);
    private static final FoundationRules FOUNDATION =
            new FoundationRules(FoundationFamily.STONE_BASE, new IntRange(1, 2));
    private static final RoofRules ROOF = new RoofRules(
            RoofFamily.GABLE,
            Set.of(RoofFamily.GABLE, RoofFamily.CROSS_GABLE),
            new IntRange(2, 4),
            new IntRange(1, 2));
    private static final OpeningRules OPENINGS = new OpeningRules(new IntRange(1, 2), 0.25, false);
    private static final Map<MaterialRole, List<PaletteCandidate>> PALETTE = BuiltinStylePalettes.medieval();

    public MedievalStyle() {
        StylePack.validate(this);
    }

    @Override
    public StyleId id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Medieval";
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
