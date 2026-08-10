package dev.ssa.architect.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record NamespacedId(String namespace, String path) {
    private static final Pattern CANONICAL =
            Pattern.compile("^([a-z0-9_.-]+):([a-z0-9_./-]+)$");

    public NamespacedId {
        if (namespace == null || path == null
                || !CANONICAL.matcher(namespace + ":" + path).matches()) {
            throw new IllegalArgumentException("Invalid namespaced ID: " + namespace + ":" + path);
        }
    }

    public static NamespacedId parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Namespaced ID must not be null");
        }

        Matcher matcher = CANONICAL.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid namespaced ID: " + value);
        }

        return new NamespacedId(matcher.group(1), matcher.group(2));
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
