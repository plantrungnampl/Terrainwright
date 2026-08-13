package dev.ssa.fabric.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class BuilderStateMachineTest {
    @Test
    void recordsTheMaterialFetchAndExecutionPath() {
        BuilderStateMachine machine = new BuilderStateMachine();

        machine.transition(BuilderStateMachine.State.RECOVERING);
        machine.transition(BuilderStateMachine.State.CHECK_MATERIALS);
        machine.transition(BuilderStateMachine.State.NAVIGATE_CHEST);
        machine.transition(BuilderStateMachine.State.FETCH_MATERIAL);
        machine.transition(BuilderStateMachine.State.NAVIGATE_SITE);
        machine.transition(BuilderStateMachine.State.EXECUTE_TASK);
        machine.transition(BuilderStateMachine.State.SELECT_NEXT_TASK);
        machine.transition(BuilderStateMachine.State.IDLE);

        assertEquals(List.of(
                BuilderStateMachine.State.IDLE,
                BuilderStateMachine.State.RECOVERING,
                BuilderStateMachine.State.CHECK_MATERIALS,
                BuilderStateMachine.State.NAVIGATE_CHEST,
                BuilderStateMachine.State.FETCH_MATERIAL,
                BuilderStateMachine.State.NAVIGATE_SITE,
                BuilderStateMachine.State.EXECUTE_TASK,
                BuilderStateMachine.State.SELECT_NEXT_TASK,
                BuilderStateMachine.State.IDLE), machine.history());
    }

    @Test
    void missingMaterialCanOnlyResumeThroughARecheck() {
        BuilderStateMachine machine = new BuilderStateMachine();
        machine.transition(BuilderStateMachine.State.RECOVERING);
        machine.transition(BuilderStateMachine.State.CHECK_MATERIALS);
        machine.transition(BuilderStateMachine.State.WAIT_MATERIAL);

        assertThrows(IllegalStateException.class,
                () -> machine.transition(BuilderStateMachine.State.NAVIGATE_CHEST));

        machine.transition(BuilderStateMachine.State.CHECK_MATERIALS);
        assertEquals(BuilderStateMachine.State.CHECK_MATERIALS, machine.state());
    }

    @Test
    void missingChestAndChunkSuspensionResumeThroughRecovery() {
        BuilderStateMachine missingChest = new BuilderStateMachine();
        missingChest.transition(BuilderStateMachine.State.RECOVERING);
        missingChest.transition(BuilderStateMachine.State.NO_CHEST);
        missingChest.transition(BuilderStateMachine.State.RECOVERING);

        BuilderStateMachine unloaded = new BuilderStateMachine();
        unloaded.transition(BuilderStateMachine.State.RECOVERING);
        unloaded.transition(BuilderStateMachine.State.SUSPENDED_CHUNK_UNLOADED);
        unloaded.transition(BuilderStateMachine.State.RECOVERING);

        assertEquals(BuilderStateMachine.State.RECOVERING, missingChest.state());
        assertEquals(BuilderStateMachine.State.RECOVERING, unloaded.state());
    }

    @Test
    void scaffoldPlacementAndCleanupRemainExplicitRuntimeStates() {
        BuilderStateMachine machine = new BuilderStateMachine();
        machine.transition(BuilderStateMachine.State.RECOVERING);
        machine.transition(BuilderStateMachine.State.CHECK_MATERIALS);
        machine.transition(BuilderStateMachine.State.NAVIGATE_SITE);
        machine.transition(BuilderStateMachine.State.CHECK_MATERIALS);
        machine.transition(BuilderStateMachine.State.NAVIGATE_SCAFFOLD);
        machine.transition(BuilderStateMachine.State.EXECUTE_SCAFFOLD);
        machine.transition(BuilderStateMachine.State.NAVIGATE_SITE);
        machine.transition(BuilderStateMachine.State.EXECUTE_TASK);
        machine.transition(BuilderStateMachine.State.NAVIGATE_SCAFFOLD);
        machine.transition(BuilderStateMachine.State.EXECUTE_SCAFFOLD);
        machine.transition(BuilderStateMachine.State.SELECT_NEXT_TASK);

        assertEquals(BuilderStateMachine.State.SELECT_NEXT_TASK, machine.state());
    }
}
