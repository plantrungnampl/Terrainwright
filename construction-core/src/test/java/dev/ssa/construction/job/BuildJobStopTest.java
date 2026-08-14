package dev.ssa.construction.job;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.construction.journal.JournalEntry;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BuildJobStopTest {
    @Test
    void stoppingJobCanCommitTheWorldIntentThatWasAlreadyInFlight() {
        BuildJob building = BuildJob.create(
                        "job-stop-drain",
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                        "ssa:blueprint",
                        "0123456789abcdef",
                        NamespacedId.parse("minecraft:overworld"),
                        new GridPos(0, 64, 0),
                        0)
                .transitionTo(BuildJobState.PREPARING)
                .transitionTo(BuildJobState.NAVIGATING)
                .transitionTo(BuildJobState.BUILDING);
        long intentRevision = building.revision();
        BuildJob stopping = building.transitionTo(BuildJobState.STOPPING);
        JournalEntry drained = new JournalEntry(
                0,
                "entry-stop",
                "operation-stop",
                "task-stop",
                new GridPos(1, 0, 1),
                state("minecraft:air"),
                state("minecraft:stone"),
                stopping.revision() + 1);

        BuildJob committed = stopping.recordCompletion(drained.taskId(), drained);

        assertEquals(intentRevision + 1, stopping.revision());
        assertEquals(BuildJobState.STOPPING, committed.state());
        assertEquals(1, committed.blockJournal().size());
    }

    private static BlockStateSpec state(String id) {
        return new BlockStateSpec(NamespacedId.parse(id), Map.of());
    }
}
