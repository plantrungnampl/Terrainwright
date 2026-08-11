package dev.ssa.architect.model;

import java.util.Objects;

public record StyleId(NamespacedId value) {
    public StyleId {
        Objects.requireNonNull(value, "value");
    }

    public static StyleId parse(String value) {
        return new StyleId(NamespacedId.parse(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
