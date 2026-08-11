package dev.ssa.architect.validation;

import java.util.List;
import java.util.Objects;

public record BlueprintValidation(List<Issue> issues) {
    public BlueprintValidation {
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
    }

    public static BlueprintValidation valid() {
        return new BlueprintValidation(List.of());
    }

    public boolean isValid() {
        return issues.stream().noneMatch(issue -> issue.severity() == Severity.ERROR);
    }

    public enum Severity {
        ERROR,
        WARNING
    }

    public record Issue(Severity severity, String code, String message) {
        public Issue {
            Objects.requireNonNull(severity, "severity");
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("Validation issue code must not be blank");
            }
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("Validation issue message must not be blank");
            }
        }
    }
}
