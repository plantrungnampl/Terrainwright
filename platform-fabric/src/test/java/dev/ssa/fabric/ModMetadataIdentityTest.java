package dev.ssa.fabric;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class ModMetadataIdentityTest {
    @Test
    void exposesTerrainwrightAsTheProductName() throws IOException {
        String metadata = metadata();

        assertTrue(metadata.contains("\"name\": \"Terrainwright\""), metadata);
    }

    @Test
    void usesTerrainwrightEntrypointNames() throws IOException {
        String metadata = metadata();

        assertTrue(metadata.contains("dev.ssa.fabric.TerrainwrightMod"), metadata);
        assertTrue(metadata.contains("dev.ssa.fabric.client.TerrainwrightClient"), metadata);
    }

    private static String metadata() throws IOException {
        try (InputStream input = ModMetadataIdentityTest.class.getResourceAsStream("/fabric.mod.json")) {
            assertNotNull(input, "fabric.mod.json");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
