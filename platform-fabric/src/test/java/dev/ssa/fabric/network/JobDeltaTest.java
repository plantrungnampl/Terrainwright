package dev.ssa.fabric.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.model.GridPos;
import dev.ssa.construction.job.BuildJobState;
import dev.ssa.fabric.client.job.JobClientState;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

final class JobDeltaTest {
    private static final UUID OWNER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID HUT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void staleDeltaCannotOverwriteNewerClientState() {
        JobClientState state = new JobClientState();
        JobPayloads.JobSnapshot snapshot = snapshot(8, BuildJobState.BUILDING, 4);
        JobPayloads.JobDelta stale = delta(7, BuildJobState.PAUSED_MISSING_MATERIAL, 3);
        JobPayloads.JobDelta fresh = delta(9, BuildJobState.PAUSED_MISSING_MATERIAL, 4);

        assertTrue(state.accept(snapshot));
        assertFalse(state.accept(stale));
        assertEquals(snapshot, state.snapshot().orElseThrow());
        assertTrue(state.accept(fresh));
        assertEquals(9, state.snapshot().orElseThrow().revision());
        assertEquals(BuildJobState.PAUSED_MISSING_MATERIAL, state.snapshot().orElseThrow().state());
        assertEquals(Map.of("minecraft:oak_planks", 3), state.snapshot().orElseThrow().missingMaterials());
    }

    @Test
    void conflictingSnapshotAtTheSameRevisionIsIgnored() {
        JobClientState state = new JobClientState();
        JobPayloads.JobSnapshot current = snapshot(8, BuildJobState.BUILDING, 4);
        JobPayloads.JobSnapshot conflict = snapshot(8, BuildJobState.PAUSED, 4);

        assertTrue(state.accept(current));
        assertFalse(state.accept(conflict));
        assertEquals(current, state.snapshot().orElseThrow());
    }

    @Test
    void snapshotDeltaAndCommandsRoundTripWithBoundedRevisionedData() {
        JobPayloads.JobSnapshot snapshot = snapshot(8, BuildJobState.BUILDING, 4);
        JobPayloads.JobDelta delta = delta(9, BuildJobState.PAUSED_CONFLICT, 4);
        JobPayloads.RequestJobSnapshot request = new JobPayloads.RequestJobSnapshot(UUID.randomUUID());
        JobPayloads.JobCommand command = new JobPayloads.JobCommand(
                "job-1", 8, JobPayloads.JobCommand.Action.PAUSE);
        JobPayloads.JobCommandResult result = new JobPayloads.JobCommandResult(
                "job-1", false, JobReplicationService.Rejection.STALE_REVISION, 9);
        JobPayloads.HutSnapshot hut = new JobPayloads.HutSnapshot(HUT, 3, true, true);
        JobPayloads.LinkBuilderChest link = new JobPayloads.LinkBuilderChest(
                HUT, new BlockPos(1, 2, 3), new BlockPos(4, 2, 3));
        JobPayloads.BuilderChestLinkResult linkResult = new JobPayloads.BuilderChestLinkResult(
                HUT, false, JobPayloads.BuilderChestLinkResult.Failure.TOO_FAR);

        assertEquals(snapshot, roundTrip(snapshot, JobPayloads.JobSnapshot.CODEC));
        assertEquals(delta, roundTrip(delta, JobPayloads.JobDelta.CODEC));
        assertEquals(request, roundTrip(request, JobPayloads.RequestJobSnapshot.CODEC));
        assertEquals(command, roundTrip(command, JobPayloads.JobCommand.CODEC));
        assertEquals(result, roundTrip(result, JobPayloads.JobCommandResult.CODEC));
        assertEquals(hut, roundTrip(hut, JobPayloads.HutSnapshot.CODEC));
        assertEquals(link, roundTrip(link, JobPayloads.LinkBuilderChest.CODEC));
        assertEquals(linkResult, roundTrip(linkResult, JobPayloads.BuilderChestLinkResult.CODEC));
    }

    @Test
    void hutAndChestLinkStatusRemainVisibleWithoutAnActiveJob() {
        JobClientState state = new JobClientState();
        JobPayloads.HutSnapshot hut = new JobPayloads.HutSnapshot(HUT, 3, true, false);
        JobPayloads.BuilderChestLinkResult linkResult = new JobPayloads.BuilderChestLinkResult(
                HUT, true, JobPayloads.BuilderChestLinkResult.Failure.NONE);

        assertTrue(state.accept(hut));
        assertTrue(state.accept(linkResult));
        assertEquals(hut, state.hutSnapshot().orElseThrow());
        assertEquals(linkResult, state.lastChestLinkResult().orElseThrow());
        assertTrue(state.snapshot().isEmpty());
    }

    @Test
    void commandRejectionRemainsVisibleUntilNewerAuthorityArrives() {
        JobClientState state = new JobClientState();
        state.accept(snapshot(8, BuildJobState.BUILDING, 4));
        JobPayloads.JobCommandResult rejection = new JobPayloads.JobCommandResult(
                "job-1", false, JobReplicationService.Rejection.STALE_REVISION, 9);

        assertTrue(state.accept(rejection));
        assertEquals(rejection, state.lastCommandResult().orElseThrow());
        assertTrue(state.accept(delta(9, BuildJobState.PAUSED, 4)));
        assertTrue(state.lastCommandResult().isEmpty());
    }

    private static JobPayloads.JobSnapshot snapshot(
            long revision,
            BuildJobState state,
            int completed) {
        return new JobPayloads.JobSnapshot(
                "job-1",
                HUT,
                OWNER,
                revision,
                state,
                completed,
                10,
                Map.of(),
                List.of(),
                List.of(new JobPayloads.DiagnosticView(
                        "WAITING",
                        "Waiting for Builder progress",
                        true,
                        Optional.of(new GridPos(1, 2, 3)))));
    }

    private static JobPayloads.JobDelta delta(
            long revision,
            BuildJobState state,
            int completed) {
        return new JobPayloads.JobDelta(
                "job-1",
                HUT,
                OWNER,
                revision,
                state,
                completed,
                10,
                Map.of("minecraft:oak_planks", 3),
                List.of(new GridPos(4, 5, 6)),
                List.of());
    }

    private static <T> T roundTrip(
            T value,
            net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, T> codec) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            codec.encode(buffer, value);
            return codec.decode(buffer);
        } finally {
            buffer.release();
        }
    }
}
