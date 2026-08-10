package dev.ssa.fabric.spike.restart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.construction.spike.persistence.BlockStateSnapshot;
import dev.ssa.construction.spike.persistence.DropPolicy;
import dev.ssa.construction.spike.persistence.EvidenceObservation;
import dev.ssa.construction.spike.persistence.InventoryDelta;
import dev.ssa.construction.spike.persistence.OperationIntent;
import dev.ssa.construction.spike.persistence.OperationKind;
import dev.ssa.construction.spike.persistence.StackSnapshot;
import dev.ssa.construction.spike.persistence.WorldDelta;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RestartFixtureRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void completeProjectMetadataAndExactEvidenceSurviveReopen() {
        OperationIntent intent = intent();
        RestartFixture fixture = fixture();
        Path path = temporaryDirectory.resolve("fixture.properties");

        RestartFixtureRepository.create(path, fixture, intent);
        RestartFixtureRepository reopened = RestartFixtureRepository.open(path);

        assertEquals(fixture, reopened.fixture());
        List<EvidenceObservation> observations = reopened.observe(intent).observations();
        assertEquals(intent.deltas().size(), observations.size());
        for (int index = 0; index < observations.size(); index++) {
            assertEquals(intent.deltas().get(index).evidenceKey(), observations.get(index).evidenceKey());
            assertEquals(intent.deltas().get(index).before(), observations.get(index).value());
        }
        assertEquals(0, reopened.applyCount());
        assertEquals(0, reopened.commitCount());
        assertEquals(0, reopened.scheduleCount());
        assertFalse(reopened.recoveryComplete());
    }

    @Test
    void mutationJournalAndSchedulingGateAreDurableAndIdempotent() {
        OperationIntent intent = intent();
        Path path = temporaryDirectory.resolve("fixture.properties");
        RestartFixtureRepository repository = RestartFixtureRepository.create(path, fixture(), intent);

        repository.apply(intent.deltas().get(0));
        RestartFixtureRepository reopened = RestartFixtureRepository.open(path);
        assertEquals(intent.deltas().get(0).after(), reopened.observe(intent).observations().get(0).value());
        assertEquals(intent.deltas().get(1).before(), reopened.observe(intent).observations().get(1).value());
        assertEquals(1, reopened.applyCount());

        reopened.commit(intent);
        reopened.commit(intent);
        assertEquals(1, RestartFixtureRepository.open(path).commitCount());
        assertThrows(IllegalStateException.class, reopened::scheduleOnce);

        reopened.markRecoveryComplete("COMMITTED");
        assertTrue(reopened.scheduleOnce());
        assertFalse(reopened.scheduleOnce());
        RestartFixtureRepository finalState = RestartFixtureRepository.open(path);
        assertTrue(finalState.recoveryComplete());
        assertEquals("COMMITTED", finalState.recoveryDiagnostic());
        assertEquals(1, finalState.scheduleCount());
    }

    @Test
    void foreignEvidenceIsExactAndDoesNotTouchJournalOrScheduling() {
        OperationIntent intent = intent();
        Path path = temporaryDirectory.resolve("fixture.properties");
        RestartFixtureRepository repository = RestartFixtureRepository.create(path, fixture(), intent);
        String beforeHash = repository.evidenceSha256();

        repository.setForeign(0);
        RestartFixtureRepository reopened = RestartFixtureRepository.open(path);
        EvidenceObservation foreign = reopened.observe(intent).observations().get(0);

        assertNotEquals(intent.deltas().get(0).before(), foreign.value());
        assertNotEquals(intent.deltas().get(0).after(), foreign.value());
        assertNotEquals(beforeHash, reopened.evidenceSha256());
        assertEquals(0, reopened.commitCount());
        assertEquals(0, reopened.scheduleCount());
    }

    private static RestartFixture fixture() {
        return new RestartFixture(
                "fixture-1",
                "world-identity-1",
                "job-1",
                7,
                "BUILDING",
                "sha256:blueprint-1",
                "minecraft:overworld@10,64,10",
                3,
                "builder-uuid-1",
                "ACTIVE",
                false,
                "minecraft:overworld@11,64,10",
                BlockStateSnapshot.of("minecraft:cobblestone", bytes("")));
    }

    private static OperationIntent intent() {
        byte[] components = bytes("{minecraft:custom_name='S5'}");
        return OperationIntent.prepared("intent-1", "job-1", 7, OperationKind.WORLD_MUTATION, List.of(
                new InventoryDelta(
                        "builder-uuid-1",
                        3,
                        0,
                        StackSnapshot.of("minecraft:oak_planks", 1, components),
                        StackSnapshot.empty()),
                new WorldDelta(
                        "minecraft:overworld",
                        12,
                        64,
                        10,
                        BlockStateSnapshot.of("minecraft:air", bytes("")),
                        BlockStateSnapshot.of("minecraft:oak_planks", bytes("")),
                        DropPolicy.NOT_APPLICABLE),
                new WorldDelta(
                        "minecraft:overworld",
                        13,
                        64,
                        10,
                        BlockStateSnapshot.of("minecraft:air", bytes("")),
                        BlockStateSnapshot.of("minecraft:oak_stairs", bytes("facing=east;half=top")),
                        DropPolicy.NOT_APPLICABLE)));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
