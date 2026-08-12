package dev.ssa.fabric.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class BuilderLifecycleTombstoneTest {
    @Test
    void unloadPreservesIdentityAndDoesNotPermitReplacement() {
        BuilderLifecycleTombstone lifecycle = BuilderLifecycleTombstone.active(
                UUID.fromString("11111111-1111-1111-1111-111111111111"));

        assertSame(lifecycle, lifecycle.observeUnload());
        assertFalse(lifecycle.canReplace());
        assertFalse(lifecycle.isTombstoned());
    }

    @Test
    void onlyObservedDeathOrRemovalWritesReplacementEvidence() {
        BuilderLifecycleTombstone active = BuilderLifecycleTombstone.active(
                UUID.fromString("11111111-1111-1111-1111-111111111111"));

        BuilderLifecycleTombstone dead = active.observeDeath(1200);
        BuilderLifecycleTombstone removed = active.observeRemoval(1300);

        assertTrue(dead.canReplace());
        assertTrue(dead.isTombstoned());
        assertTrue(removed.canReplace());
        assertTrue(removed.isTombstoned());
    }
}
