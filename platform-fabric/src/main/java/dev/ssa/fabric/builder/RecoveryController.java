package dev.ssa.fabric.builder;

/** Selects a finite recovery sequence after normal interaction positions and routes are exhausted. */
public final class RecoveryController {
    private final int maximumRouteRetries;
    private int routeRetries;
    private boolean localResetUsed;
    private boolean scaffoldUsed;
    private boolean blocked;

    public RecoveryController(int maximumRouteRetries) {
        if (maximumRouteRetries < 0) {
            throw new IllegalArgumentException("maximum route retries must not be negative");
        }
        this.maximumRouteRetries = maximumRouteRetries;
    }

    public Action next(boolean boundedScaffoldPlanAvailable) {
        if (blocked) {
            return Action.BLOCKED;
        }
        if (routeRetries < maximumRouteRetries) {
            routeRetries++;
            return Action.RETRY_ROUTE;
        }
        if (!localResetUsed) {
            localResetUsed = true;
            return Action.LOCAL_RESET;
        }
        if (boundedScaffoldPlanAvailable && !scaffoldUsed) {
            scaffoldUsed = true;
            return Action.SCAFFOLD;
        }
        blocked = true;
        return Action.BLOCKED;
    }

    public void reset() {
        routeRetries = 0;
        localResetUsed = false;
        scaffoldUsed = false;
        blocked = false;
    }

    public enum Action {
        RETRY_ROUTE,
        LOCAL_RESET,
        SCAFFOLD,
        BLOCKED
    }
}
