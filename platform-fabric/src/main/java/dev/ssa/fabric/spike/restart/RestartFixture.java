package dev.ssa.fabric.spike.restart;

import dev.ssa.construction.spike.persistence.BlockStateSnapshot;
import java.util.Objects;

public record RestartFixture(
        String fixtureId,
        String worldIdentity,
        String buildJobId,
        long jobRevision,
        String jobState,
        String blueprintHash,
        String containerBindingId,
        int containerBindingRevision,
        String builderId,
        String builderLifecycle,
        boolean builderTombstone,
        String temporaryScaffoldKey,
        BlockStateSnapshot temporaryScaffoldState) {
    public RestartFixture {
        requireText(fixtureId, "fixtureId");
        requireText(worldIdentity, "worldIdentity");
        requireText(buildJobId, "buildJobId");
        requireText(jobState, "jobState");
        requireText(blueprintHash, "blueprintHash");
        requireText(containerBindingId, "containerBindingId");
        requireText(builderId, "builderId");
        requireText(builderLifecycle, "builderLifecycle");
        requireText(temporaryScaffoldKey, "temporaryScaffoldKey");
        Objects.requireNonNull(temporaryScaffoldState, "temporaryScaffoldState");
        if (jobRevision < 0 || containerBindingRevision < 0) {
            throw new IllegalArgumentException("fixture revisions must not be negative");
        }
    }

    private static void requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException(label + " must contain 1 to 512 characters");
        }
    }
}
