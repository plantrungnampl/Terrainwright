package dev.ssa.fabric.job;

import dev.ssa.construction.job.BuildJob;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.fabric.lifecycle.BuilderLifecycleTombstone;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Persists Builder chunk suspension without turning absence into replacement evidence. */
public final class ChunkSuspensionService {
    private final ServerBuildJobRepository repository;

    public ChunkSuspensionService(ServerBuildJobRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public void suspendBuilder(UUID builderId) {
        owner(builderId).ifPresent(hut -> {
            BuilderLifecycleTombstone current = hut.builderLifecycle().orElseThrow();
            BuilderLifecycleTombstone suspended = current.observeUnload();
            if (suspended == current) {
                return;
            }
            transitionJob(hut, BuildJobState.SUSPENDED_CHUNK_UNLOADED);
            saveLifecycle(hut, suspended);
        });
    }

    public void resumeBuilder(UUID builderId) {
        owner(builderId).ifPresent(hut -> {
            BuilderLifecycleTombstone current = hut.builderLifecycle().orElseThrow();
            BuilderLifecycleTombstone active = current.observeLoad();
            if (active == current) {
                return;
            }
            saveLifecycle(hut, active);
            transitionJob(hut, BuildJobState.PREPARING);
        });
    }

    private Optional<ServerBuildJobRepository.HutState> owner(UUID builderId) {
        Objects.requireNonNull(builderId, "builderId");
        List<ServerBuildJobRepository.HutState> owners = repository.huts().values().stream()
                .filter(hut -> hut.builderLifecycle().stream()
                        .anyMatch(lifecycle -> lifecycle.builderId().equals(builderId)))
                .toList();
        if (owners.size() > 1) {
            throw new IllegalStateException("One Builder identity is linked to multiple Huts");
        }
        return owners.stream().findFirst();
    }

    private void transitionJob(ServerBuildJobRepository.HutState hut, BuildJobState target) {
        hut.activeJobId().flatMap(repository::findJob).ifPresent(job -> {
            if (job.state().canTransitionTo(target)) {
                repository.saveJob(job.transitionTo(target));
            }
        });
    }

    private void saveLifecycle(
            ServerBuildJobRepository.HutState hut,
            BuilderLifecycleTombstone lifecycle) {
        repository.saveHutState(new ServerBuildJobRepository.HutState(
                hut.hutId(),
                hut.ownerId(),
                hut.activeJobId(),
                hut.containerBinding(),
                Optional.of(lifecycle),
                hut.revision() + 1));
    }
}
