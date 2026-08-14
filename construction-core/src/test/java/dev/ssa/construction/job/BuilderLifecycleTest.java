package dev.ssa.construction.job;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import org.junit.jupiter.api.Test;

final class BuilderLifecycleTest {
    @Test
    void activeJobsRetainProgressAcrossBuilderDeathAndHutLoss() {
        BuildJob active = job().transitionTo(BuildJobState.PREPARING);

        assertEquals(BuildJobState.NO_BUILDER, active.transitionTo(BuildJobState.NO_BUILDER).state());
        assertEquals(BuildJobState.ORPHANED, active.transitionTo(BuildJobState.ORPHANED).state());
    }

    @Test
    void explicitReplacementOrRebindCanReturnToReconciliation() {
        BuildJob active = job().transitionTo(BuildJobState.PREPARING);

        assertEquals(
                BuildJobState.PREPARING,
                active.transitionTo(BuildJobState.NO_BUILDER)
                        .transitionTo(BuildJobState.PREPARING)
                        .state());
        assertEquals(
                BuildJobState.PREPARING,
                active.transitionTo(BuildJobState.ORPHANED)
                        .transitionTo(BuildJobState.PREPARING)
                        .state());
    }

    private static BuildJob job() {
        return BuildJob.create(
                "job-1",
                "owner-1",
                "hut-1",
                "blueprint-1",
                "aaaaaaaaaaaaaaaa",
                NamespacedId.parse("minecraft:overworld"),
                new GridPos(0, 64, 0),
                0);
    }
}
