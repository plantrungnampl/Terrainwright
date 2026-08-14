package dev.ssa.fabric.style;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.material.PaletteResolver;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.style.MedievalStyle;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class PaletteFallbackGameTest {
    @GameTest
    public void missingOptionalModBlockFallsBackToBundledVanilla(GameTestHelper context) {
        JsonObject document;
        try (var stream = PaletteFallbackGameTest.class.getResourceAsStream(
                        "/data/smart_survival_architect/styles/medieval.json");
                var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            document = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception exception) {
            throw new AssertionError("Bundled Medieval style data could not be read", exception);
        }

        JsonObject missingCandidate = new JsonObject();
        missingCandidate.addProperty("blockId", "example:roof_tile");
        JsonArray capabilities = new JsonArray();
        capabilities.add("STAIR");
        capabilities.add("HORIZONTAL_FACING");
        missingCandidate.add("requiredCapabilities", capabilities);
        missingCandidate.addProperty("weight", 1.0);
        missingCandidate.add("stateTemplate", new JsonObject());
        JsonArray missingOnly = new JsonArray();
        missingOnly.add(missingCandidate);
        document.getAsJsonObject("materialPalette").add("ROOF_PRIMARY", missingOnly);

        StyleDataLoader.LoadedStyle loaded = StyleDataLoader.parse(
                new MedievalStyle(), document);
        var resolved = new PaletteResolver(loaded.style())
                .resolve(MaterialRole.ROOF_PRIMARY, loaded.capabilities())
                .orElseThrow();

        context.assertValueEqual(
                resolved.blockId(),
                NamespacedId.parse("minecraft:dark_oak_stairs"),
                "missing optional roof block fallback");
        context.succeed();
    }
}
