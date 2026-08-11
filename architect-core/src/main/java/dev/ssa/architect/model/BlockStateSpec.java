package dev.ssa.architect.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

public record BlockStateSpec(NamespacedId blockId, Map<String, String> properties) {
    private static final Pattern PROPERTY_NAME = Pattern.compile("^[a-z0-9_]+$");
    private static final Pattern PROPERTY_VALUE = Pattern.compile("^[a-z0-9_.-]+$");
    private static final int MAX_PROPERTY_TOKEN_LENGTH = 64;

    public BlockStateSpec {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(properties, "properties");
        TreeMap<String, String> canonical = new TreeMap<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "property name");
            String value = Objects.requireNonNull(entry.getValue(), "property value");
            if (name.length() > MAX_PROPERTY_TOKEN_LENGTH || !PROPERTY_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid block property name: " + name);
            }
            if (value.length() > MAX_PROPERTY_TOKEN_LENGTH || !PROPERTY_VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("Invalid block property value for " + name + ": " + value);
            }
            canonical.put(name, value);
        }
        properties = Collections.unmodifiableMap(canonical);
    }

    public static BlockStateSpec of(NamespacedId blockId, Map<String, String> properties) {
        return new BlockStateSpec(blockId, properties);
    }
}
