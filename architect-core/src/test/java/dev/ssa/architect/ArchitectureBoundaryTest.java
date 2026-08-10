package dev.ssa.architect;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchitectureBoundaryTest {
    private static final List<String> PURE_MODULES = List.of(
            "architect-core",
            "construction-core",
            "minecraft-common");

    @Test
    void pureModulesDoNotImportMinecraftOrFabric() {
        assertDoesNotThrow(() -> SourceBoundary.assertNoPlatformImports(repositoryRoot(), PURE_MODULES));
    }

    @Test
    void reportsTheForbiddenImportLocation(@TempDir Path repository) throws IOException {
        Path source = repository.resolve("architect-core/src/main/java/example/Leak.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;

                import net.minecraft.world.level.block.Block;

                final class Leak {}
                """);

        AssertionError error = assertThrows(
                AssertionError.class,
                () -> SourceBoundary.assertNoPlatformImports(repository, List.of("architect-core")));

        assertTrue(error.getMessage().contains("architect-core\\src\\main\\java\\example\\Leak.java:3"));
        assertTrue(error.getMessage().contains("net.minecraft"));
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Could not locate repository root");
        }
        return current;
    }
}
