package dev.ssa.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.model.GridPos;
import dev.ssa.common.permission.PermissionPort;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ArchitectureBoundaryTest {
    @Test
    void permissionPortUsesOnlyPureCoreValueTypes() throws NoSuchMethodException {
        Method method = PermissionPort.class.getMethod("canModify", UUID.class, GridPos.class);

        assertEquals(boolean.class, method.getReturnType());
        assertTrue(PermissionPort.class.isInterface());
    }

    @Test
    void dependenciesPointFromAdaptersTowardPureCores() throws IOException {
        Path root = repositoryRoot();
        assertNoImports(root.resolve("architect-core/src/main/java"),
                List.of("dev.ssa.common", "net.minecraft", "net.fabricmc"));
        assertNoImports(root.resolve("construction-core/src/main/java"),
                List.of("dev.ssa.common", "net.minecraft", "net.fabricmc"));
        assertNoImports(root.resolve("minecraft-common/src/main/java"),
                List.of("net.minecraft", "net.fabricmc"));
    }

    private static void assertNoImports(Path sourceRoot, List<String> forbidden) throws IOException {
        if (!Files.exists(sourceRoot)) {
            return;
        }
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            List<String> violations = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(ArchitectureBoundaryTest::sourceLines)
                    .filter(line -> forbidden.stream().anyMatch(line::contains))
                    .toList();
            assertTrue(violations.isEmpty(), () -> "Forbidden dependencies: " + violations);
        }
    }

    private static Stream<String> sourceLines(Path path) {
        try {
            return Files.readAllLines(path).stream()
                    .filter(line -> line.stripLeading().startsWith("import "))
                    .map(line -> path + ": " + line.trim());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + path, exception);
        }
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
