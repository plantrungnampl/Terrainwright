package dev.ssa.fabric.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BuilderStateMachine {
    private final List<State> history = new ArrayList<>(List.of(State.IDLE));
    private State state = State.IDLE;

    public State state() {
        return state;
    }

    public List<State> history() {
        return List.copyOf(history);
    }

    public void transition(State next) {
        Objects.requireNonNull(next, "next");
        if (state == next) {
            return;
        }
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal Builder transition: " + state + " -> " + next);
        }
        state = next;
        history.add(next);
    }

    public enum State {
        IDLE,
        RECOVERING,
        CHECK_MATERIALS,
        WAIT_MATERIAL,
        NAVIGATE_CHEST,
        FETCH_MATERIAL,
        NAVIGATE_SITE,
        EXECUTE_TASK,
        SELECT_NEXT_TASK,
        NO_CHEST,
        SUSPENDED_CHUNK_UNLOADED,
        BLOCKED;

        private boolean canTransitionTo(State next) {
            return switch (this) {
                case IDLE -> next == RECOVERING;
                case RECOVERING -> next == CHECK_MATERIALS
                        || next == NO_CHEST
                        || next == SUSPENDED_CHUNK_UNLOADED
                        || next == BLOCKED;
                case CHECK_MATERIALS -> next == WAIT_MATERIAL
                        || next == NAVIGATE_CHEST
                        || next == NAVIGATE_SITE
                        || next == IDLE
                        || next == NO_CHEST
                        || next == SUSPENDED_CHUNK_UNLOADED
                        || next == BLOCKED;
                case WAIT_MATERIAL -> next == CHECK_MATERIALS
                        || next == SUSPENDED_CHUNK_UNLOADED
                        || next == BLOCKED;
                case NAVIGATE_CHEST -> next == FETCH_MATERIAL
                        || next == NO_CHEST
                        || next == SUSPENDED_CHUNK_UNLOADED
                        || next == BLOCKED;
                case FETCH_MATERIAL -> next == NAVIGATE_SITE
                        || next == WAIT_MATERIAL
                        || next == NO_CHEST
                        || next == SUSPENDED_CHUNK_UNLOADED
                        || next == BLOCKED;
                case NAVIGATE_SITE -> next == EXECUTE_TASK
                        || next == SUSPENDED_CHUNK_UNLOADED
                        || next == BLOCKED;
                case EXECUTE_TASK -> next == SELECT_NEXT_TASK
                        || next == SUSPENDED_CHUNK_UNLOADED
                        || next == BLOCKED;
                case SELECT_NEXT_TASK -> next == CHECK_MATERIALS
                        || next == NAVIGATE_SITE
                        || next == IDLE
                        || next == SUSPENDED_CHUNK_UNLOADED
                        || next == BLOCKED;
                case NO_CHEST -> next == RECOVERING || next == BLOCKED;
                case SUSPENDED_CHUNK_UNLOADED -> next == RECOVERING || next == BLOCKED;
                case BLOCKED -> false;
            };
        }
    }
}
