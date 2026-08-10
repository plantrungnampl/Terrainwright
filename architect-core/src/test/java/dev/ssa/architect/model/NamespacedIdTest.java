package dev.ssa.architect.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class NamespacedIdTest {
    @Test
    void parsesCanonicalNamespacedId() {
        NamespacedId id = NamespacedId.parse("smart_survival_architect:spike_marker/path");

        assertEquals("smart_survival_architect", id.namespace());
        assertEquals("spike_marker/path", id.path());
        assertEquals("smart_survival_architect:spike_marker/path", id.toString());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "spike_marker",
            ":spike_marker",
            "smart_survival_architect:",
            "Smart_Survival_Architect:spike_marker",
            "smart_survival_architect:Spike_Marker",
            "smart survival architect:spike_marker"
    })
    void rejectsNoncanonicalNamespacedId(String value) {
        assertThrows(IllegalArgumentException.class, () -> NamespacedId.parse(value));
    }
}
