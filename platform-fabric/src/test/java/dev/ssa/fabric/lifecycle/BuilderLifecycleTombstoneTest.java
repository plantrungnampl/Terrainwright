package dev.ssa.fabric.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class BuilderLifecycleTombstoneTest {
    @Test
    void spawnIdentityRemainsPendingUntilTheEntityIsActivated() {
        UUID builderId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        BuilderLifecycleTombstone pending = BuilderLifecycleTombstone.spawning(builderId);
        BuilderLifecycleTombstone active = pending.activate();

        assertEquals(BuilderLifecycleTombstone.Status.SPAWN_PENDING, pending.status());
        assertFalse(pending.canReplace());
        assertEquals(BuilderLifecycleTombstone.Status.ACTIVE, active.status());
        assertEquals(builderId, active.builderId());
        assertEquals(pending.revision() + 1, active.revision());
    }

    @Test
    void unloadPreservesIdentityAndDoesNotPermitReplacement() {
        BuilderLifecycleTombstone lifecycle = BuilderLifecycleTombstone.active(
                UUID.fromString("11111111-1111-1111-1111-111111111111"));

        BuilderLifecycleTombstone suspended = lifecycle.observeUnload();
        BuilderLifecycleTombstone resumed = suspended.observeLoad();

        assertEquals(BuilderLifecycleTombstone.Status.SUSPENDED, suspended.status());
        assertEquals(lifecycle.builderId(), suspended.builderId());
        assertFalse(suspended.canReplace());
        assertFalse(suspended.isTombstoned());
        assertEquals(BuilderLifecycleTombstone.Status.ACTIVE, resumed.status());
        assertEquals(lifecycle.builderId(), resumed.builderId());
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
        assertEquals(BuilderLifecycleTombstone.Status.TOMBSTONED, dead.status());
    }

    @Test
    void replacementRequiresATombstoneAndCreatesANewEmptyIdentity() {
        UUID originalId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID replacementId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        BuilderLifecycleTombstone active = BuilderLifecycleTombstone.active(originalId);

        assertThrows(IllegalStateException.class, () -> active.replaceWith(replacementId));

        BuilderLifecycleTombstone replacement = active.observeDeath(1200).replaceWith(replacementId);
        assertEquals(replacementId, replacement.builderId());
        assertEquals(BuilderLifecycleTombstone.Status.SPAWN_PENDING, replacement.status());
        assertFalse(replacement.canReplace());
        assertTrue(replacement.tombstone().isEmpty());
    }
}
