package dev.ssa.construction.job;

public enum BuildJobState {
    IDLE,
    PREPARING,
    WAIT_MATERIAL,
    FETCHING_MATERIAL,
    NAVIGATING,
    BUILDING,
    PAUSED,
    PAUSED_MISSING_MATERIAL,
    PAUSED_NO_CHEST,
    PAUSED_BLOCKED,
    PAUSED_CONFLICT,
    PAUSED_PROTECTED,
    SUSPENDED_CHUNK_UNLOADED,
    NO_BUILDER,
    ORPHANED,
    STOPPING,
    STOPPED,
    UNDOING,
    UNDO_COMPLETED,
    COMPLETED,
    QUARANTINED_RECOVERY;

    public boolean canTransitionTo(BuildJobState next) {
        if (this == next) {
            return true;
        }
        return switch (this) {
            case IDLE -> next == PREPARING || next == COMPLETED || isInterrupt(next);
            case PREPARING -> next == WAIT_MATERIAL
                    || next == FETCHING_MATERIAL
                    || next == NAVIGATING
                    || next == COMPLETED
                    || isInterrupt(next);
            case WAIT_MATERIAL -> next == FETCHING_MATERIAL || isInterrupt(next);
            case FETCHING_MATERIAL -> next == WAIT_MATERIAL
                    || next == NAVIGATING
                    || isInterrupt(next);
            case NAVIGATING -> next == WAIT_MATERIAL
                    || next == FETCHING_MATERIAL
                    || next == BUILDING
                    || isInterrupt(next);
            case BUILDING -> next == WAIT_MATERIAL
                    || next == FETCHING_MATERIAL
                    || next == NAVIGATING
                    || next == COMPLETED
                    || isInterrupt(next);
            case PAUSED,
                    PAUSED_MISSING_MATERIAL,
                    PAUSED_NO_CHEST,
                    PAUSED_BLOCKED,
                    PAUSED_CONFLICT,
                    PAUSED_PROTECTED,
                    SUSPENDED_CHUNK_UNLOADED -> next == PREPARING
                            || next == STOPPING
                            || next == QUARANTINED_RECOVERY;
            case NO_BUILDER, ORPHANED -> next == STOPPING;
            case QUARANTINED_RECOVERY -> false;
            case UNDO_COMPLETED -> false;
            case COMPLETED, STOPPED -> next == UNDOING;
            case STOPPING -> next == STOPPED || next == QUARANTINED_RECOVERY;
            case UNDOING -> next == UNDO_COMPLETED || next == QUARANTINED_RECOVERY;
        };
    }

    public boolean transitionRequiresDiagnostic() {
        return switch (this) {
            case PAUSED_BLOCKED,
                    PAUSED_CONFLICT,
                    PAUSED_PROTECTED,
                    QUARANTINED_RECOVERY -> true;
            default -> false;
        };
    }

    public boolean canRecordConstructionProgress() {
        return this == BUILDING;
    }

    private static boolean isInterrupt(BuildJobState state) {
        return switch (state) {
            case PAUSED,
                    PAUSED_MISSING_MATERIAL,
                    PAUSED_NO_CHEST,
                    PAUSED_BLOCKED,
                    PAUSED_CONFLICT,
                    PAUSED_PROTECTED,
                    SUSPENDED_CHUNK_UNLOADED,
                    STOPPING,
                    QUARANTINED_RECOVERY -> true;
            default -> false;
        };
    }
}
