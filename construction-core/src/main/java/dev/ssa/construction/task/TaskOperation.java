package dev.ssa.construction.task;

public enum TaskOperation {
    REMOVE,
    PLACE,
    REPLACE,
    TEMP_PLACE,
    TEMP_REMOVE;

    public boolean requiresMaterial() {
        return this == PLACE || this == REPLACE || this == TEMP_PLACE;
    }
}
