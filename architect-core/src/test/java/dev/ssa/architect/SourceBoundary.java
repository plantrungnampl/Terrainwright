package dev.ssa.architect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

final class SourceBoundary {
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "import net.minecraft",
            "import net.fabricmc");

    private SourceBoundary() {}

    static void assertNoPlatformImports(Path repository, List<String> modules) throws IOException {
        for (String module : modules) {
            Path sourceRoot = repository.resolve(module).resolve("src/main/java");
            if (!Files.exists(sourceRoot)) {
                continue;
            }

            try (Stream<Path> sources = Files.walk(sourceRoot)) {
                for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                    assertSourceHasNoPlatformImports(repository, source);
                }
            }
        }
    }

    private static void assertSourceHasNoPlatformImports(Path repository, Path source) throws IOException {
        List<String> lines = Files.readAllLines(source);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).stripLeading();
            for (String forbiddenImport : FORBIDDEN_IMPORTS) {
                if (line.startsWith(forbiddenImport)) {
                    throw new AssertionError("Forbidden platform import at "
                            + repository.relativize(source) + ":" + (index + 1) + " -> " + line);
                }
            }
        }
    }
}
