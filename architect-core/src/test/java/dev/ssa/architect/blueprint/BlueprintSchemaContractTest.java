package dev.ssa.architect.blueprint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class BlueprintSchemaContractTest {
    @Test
    void runtimeSchemaUsesCanonicalBuildPhaseAndCompleteScoreBreakdown() throws IOException {
        String schema = loadRuntimeSchema();

        assertTrue(schema.contains("\"UPPER_FLOOR\""));
        assertFalse(schema.contains("\"UPPER_FLOORS\""));
        assertTrue(schema.contains("\"scenicOrientation\""));
    }

    private static String loadRuntimeSchema() throws IOException {
        try (InputStream input = BlueprintSchemaContractTest.class.getResourceAsStream(
                "/dev/ssa/architect/schema/blueprint.schema.json")) {
            if (input == null) {
                throw new IOException("runtime blueprint schema is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
