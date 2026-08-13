package dev.ssa.fabric.persistence;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface AppendProbe {
    AppendProbe NONE = path -> { };

    void afterWriteBeforeForce(Path path) throws IOException;
}
