package dev.ssa.fabric.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.fabric.lifecycle.BuilderLifecycleTombstone;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ChunkSuspensionServiceTest {
    private static final UUID HUT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BUILDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void unloadSuspendsWithoutAuthorizingReplacementAndLoadResumesSameIdentity() {
        ServerBuildJobRepository repository = LifecycleTestFixtures.repository(
                HUT_ID, OWNER_ID, BUILDER_ID, BuildJobState.PREPARING);
        ChunkSuspensionService service = new ChunkSuspensionService(repository);

        service.suspendBuilder(BUILDER_ID);

        ServerBuildJobRepository.HutState suspended = repository.findHut(HUT_ID).orElseThrow();
        BuildJob suspendedJob = repository.findJob(LifecycleTestFixtures.JOB_ID).orElseThrow();
        assertEquals(BuilderLifecycleTombstone.Status.SUSPENDED,
                suspended.builderLifecycle().orElseThrow().status());
        assertFalse(suspended.builderLifecycle().orElseThrow().canReplace());
        assertEquals(BuildJobState.SUSPENDED_CHUNK_UNLOADED, suspendedJob.state());

        service.resumeBuilder(BUILDER_ID);

        assertEquals(BuilderLifecycleTombstone.Status.ACTIVE,
                repository.findHut(HUT_ID).orElseThrow().builderLifecycle().orElseThrow().status());
        assertEquals(BuildJobState.PREPARING,
                repository.findJob(LifecycleTestFixtures.JOB_ID).orElseThrow().state());
        assertEquals(BUILDER_ID,
                repository.findHut(HUT_ID).orElseThrow().builderLifecycle().orElseThrow().builderId());
    }
}
