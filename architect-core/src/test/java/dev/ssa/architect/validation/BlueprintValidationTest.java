package dev.ssa.architect.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BlueprintValidationTest {
    @Test
    void validityIsDerivedFromImmutableStructuredIssues() {
        List<BlueprintValidation.Issue> warnings = new ArrayList<>(List.of(
                new BlueprintValidation.Issue(
                        BlueprintValidation.Severity.WARNING,
                        "LOW_SCENIC_SCORE",
                        "The selected orientation has a low scenic score")));
        BlueprintValidation warningOnly = new BlueprintValidation(warnings);
        warnings.clear();

        assertTrue(warningOnly.isValid());
        assertFalse(new BlueprintValidation(List.of(new BlueprintValidation.Issue(
                BlueprintValidation.Severity.ERROR,
                "LAVA_INTERSECTION",
                "A required placement intersects lava"))).isValid());
        assertThrows(UnsupportedOperationException.class, () -> warningOnly.issues().clear());
    }

    @Test
    void rejectsBlankIssueFields() {
        assertThrows(IllegalArgumentException.class, () -> new BlueprintValidation.Issue(
                BlueprintValidation.Severity.ERROR, " ", "message"));
        assertThrows(IllegalArgumentException.class, () -> new BlueprintValidation.Issue(
                BlueprintValidation.Severity.ERROR, "CODE", " "));
    }
}
