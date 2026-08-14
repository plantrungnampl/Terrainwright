package dev.ssa.fabric.style;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.ssa.architect.style.MedievalStyle;
import dev.ssa.architect.style.JapaneseStyle;
import dev.ssa.architect.style.ModernStyle;
import dev.ssa.architect.style.StylePack;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class StyleDataLoaderTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void allBundledStyleDocumentsPublishCompleteCompatiblePalettes() {
        loadBundled(new MedievalStyle(), "medieval");
        loadBundled(new JapaneseStyle(), "japanese");
        loadBundled(new ModernStyle(), "modern");
    }

    @Test
    void rejectsUnknownMaterialRoleAlias() {
        JsonObject document = medievalDocument();
        JsonObject fallback = document.getAsJsonObject("fallbackPalette");
        fallback.add("STONE_BASE", fallback.remove("FOUNDATION_STONE"));

        assertThrows(
                IllegalArgumentException.class,
                () -> StyleDataLoader.parse(new MedievalStyle(), document));
    }

    @Test
    void rejectsIncompleteFallbackPalette() {
        JsonObject document = medievalDocument();
        document.getAsJsonObject("fallbackPalette").remove("LIGHTING");

        assertThrows(
                IllegalArgumentException.class,
                () -> StyleDataLoader.parse(new MedievalStyle(), document));
    }

    @Test
    void rejectsNonFiniteCandidateWeight() {
        JsonObject document = medievalDocument();
        document.getAsJsonObject("materialPalette")
                .getAsJsonArray("ROOF_PRIMARY")
                .get(0)
                .getAsJsonObject()
                .add("weight", new JsonPrimitive(Double.NaN));

        assertThrows(
                IllegalArgumentException.class,
                () -> StyleDataLoader.parse(new MedievalStyle(), document));
    }

    @Test
    void rejectsNonCanonicalCapabilityAlias() {
        JsonObject document = medievalDocument();
        document.getAsJsonObject("materialPalette")
                .getAsJsonArray("ROOF_PRIMARY")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("requiredCapabilities")
                .set(0, new JsonPrimitive("FULL_BLOCK"));

        assertThrows(
                IllegalArgumentException.class,
                () -> StyleDataLoader.parse(new MedievalStyle(), document));
    }

    private static JsonObject medievalDocument() {
        return document("medieval");
    }

    private static void loadBundled(StylePack style, String resourceName) {
        StyleDataLoader.parse(style, document(resourceName));
    }

    private static JsonObject document(String resourceName) {
        try (var stream = StyleDataLoaderTest.class.getResourceAsStream(
                        "/data/smart_survival_architect/styles/" + resourceName + ".json");
                var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception exception) {
            throw new AssertionError("Bundled Medieval style data could not be read", exception);
        }
    }
}
