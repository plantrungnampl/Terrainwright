package dev.ssa.fabric.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class RecoveryControllerTest {
    @Test
    void exhaustsTheBoundedRecoveryLadderWithoutTeleport() {
        RecoveryController controller = new RecoveryController(2);

        assertEquals(RecoveryController.Action.RETRY_ROUTE, controller.next(true));
        assertEquals(RecoveryController.Action.RETRY_ROUTE, controller.next(true));
        assertEquals(RecoveryController.Action.LOCAL_RESET, controller.next(true));
        assertEquals(RecoveryController.Action.SCAFFOLD, controller.next(true));
        assertEquals(RecoveryController.Action.BLOCKED, controller.next(true));
        assertEquals(RecoveryController.Action.BLOCKED, controller.next(true));
    }

    @Test
    void skipsScaffoldingWhenNoBoundedPlanExists() {
        RecoveryController controller = new RecoveryController(1);

        assertEquals(RecoveryController.Action.RETRY_ROUTE, controller.next(false));
        assertEquals(RecoveryController.Action.LOCAL_RESET, controller.next(false));
        assertEquals(RecoveryController.Action.BLOCKED, controller.next(false));
    }

    @Test
    void resetStartsANewBoundedRecoverySequence() {
        RecoveryController controller = new RecoveryController(1);
        controller.next(true);
        controller.next(true);
        controller.next(true);

        controller.reset();

        assertEquals(RecoveryController.Action.RETRY_ROUTE, controller.next(true));
    }
}
