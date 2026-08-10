package dev.ssa.construction.spike.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

final class OperationRecoveryClassifierTest {
    private final OperationRecoveryClassifier classifier = new OperationRecoveryClassifier();

    @Test
    void materialTransferClassifiesAllBeforeAllAfterAndKnownPrefix() {
        OperationIntent intent = materialTransfer();

        assertEquals(RecoveryDecision.abortPrepared(), classifier.classify(intent, observe(intent, index -> intent.deltas().get(index).before())));
        assertEquals(RecoveryDecision.finalizeCommit(2), classifier.classify(intent, observe(intent, index -> intent.deltas().get(index).after())));
        assertEquals(RecoveryDecision.completeSuffix(1), classifier.classify(intent, observe(intent, index ->
                index == 0 ? intent.deltas().get(index).after() : intent.deltas().get(index).before())));
    }

    @Test
    void placementConsumptionUsesOneOrderedPrefixAcrossInventoryAndWorld() {
        OperationIntent intent = placementWithConsumption();

        assertEquals(RecoveryDecision.completeSuffix(1), classifier.classify(intent, observe(intent, index ->
                index == 0 ? intent.deltas().get(index).after() : intent.deltas().get(index).before())));
        assertEquals(RecoveryDecision.quarantine(), classifier.classify(intent, observe(intent, index ->
                index == 0 ? intent.deltas().get(index).before() : intent.deltas().get(index).after())));
    }

    @Test
    void everyAtomicMultiBlockPrefixCompletesOnlyItsRemainingSuffix() {
        OperationIntent intent = atomicWorldMutation();

        assertEquals(RecoveryDecision.completeSuffix(1), classifier.classify(intent, prefix(intent, 1)));
        assertEquals(RecoveryDecision.completeSuffix(2), classifier.classify(intent, prefix(intent, 2)));
    }

    @Test
    void sameItemAndCountWithForeignComponentsQuarantines() {
        OperationIntent intent = materialTransfer();
        StackSnapshot foreign = stack("minecraft:oak_planks", 64, "custom-name=foreign");
        List<EvidenceObservation> observations = observations(intent, index -> intent.deltas().get(index).before());
        observations.set(0, new EvidenceObservation(intent.deltas().getFirst().evidenceKey(), foreign));

        assertEquals(RecoveryDecision.quarantine(), classifier.classify(intent, new ObservedEvidence(observations)));
    }

    @Test
    void sameBlockWithForeignPropertyQuarantines() {
        OperationIntent intent = atomicWorldMutation();
        BlockStateSnapshot foreign = block("minecraft:oak_stairs", "facing=west;half=bottom");
        List<EvidenceObservation> observations = observations(intent, index -> intent.deltas().get(index).before());
        observations.set(1, new EvidenceObservation(intent.deltas().get(1).evidenceKey(), foreign));

        assertEquals(RecoveryDecision.quarantine(), classifier.classify(intent, new ObservedEvidence(observations)));
    }

    @Test
    void changedBindingIdentityQuarantinesEvenWhenStackMatches() {
        OperationIntent intent = materialTransfer();
        List<EvidenceObservation> observations = observations(intent, index -> intent.deltas().get(index).before());
        observations.set(0, new EvidenceObservation("inventory:chest@revision=8/slot=0", observations.getFirst().value()));

        assertEquals(RecoveryDecision.quarantine(), classifier.classify(intent, new ObservedEvidence(observations)));
    }

    @Test
    void terminalStatusesCannotResumeMutation() {
        OperationIntent prepared = materialTransfer();
        ObservedEvidence allBefore = observe(prepared, index -> prepared.deltas().get(index).before());
        ObservedEvidence prefix = prefix(prepared, 1);
        ObservedEvidence allAfter = observe(prepared, index -> prepared.deltas().get(index).after());

        assertEquals(RecoveryDecision.quarantine(),
                classifier.classify(prepared.withStatus(OperationStatus.QUARANTINED), allBefore));
        assertEquals(RecoveryDecision.quarantine(),
                classifier.classify(prepared.withStatus(OperationStatus.QUARANTINED), prefix));
        assertEquals(RecoveryDecision.abortPrepared(),
                classifier.classify(prepared.withStatus(OperationStatus.ABORTED), allBefore));
        assertEquals(RecoveryDecision.quarantine(),
                classifier.classify(prepared.withStatus(OperationStatus.ABORTED), prefix));
        assertEquals(RecoveryDecision.finalizeCommit(2),
                classifier.classify(prepared.withStatus(OperationStatus.COMMITTED), allAfter));
        assertEquals(RecoveryDecision.quarantine(),
                classifier.classify(prepared.withStatus(OperationStatus.COMMITTED), prefix));
    }

    private static OperationIntent materialTransfer() {
        byte[] components = bytes("{}");
        return OperationIntent.prepared("transfer-1", "job-1", 3, OperationKind.MATERIAL_TRANSFER, List.of(
                new InventoryDelta("chest", 7, 0,
                        StackSnapshot.of("minecraft:oak_planks", 64, components),
                        StackSnapshot.of("minecraft:oak_planks", 60, components)),
                new InventoryDelta("builder", 11, 2,
                        StackSnapshot.empty(),
                        StackSnapshot.of("minecraft:oak_planks", 4, components))));
    }

    private static OperationIntent placementWithConsumption() {
        return OperationIntent.prepared("place-1", "job-1", 4, OperationKind.WORLD_MUTATION, List.of(
                new InventoryDelta("builder", 11, 2, stack("minecraft:oak_planks", 1, "{}"), StackSnapshot.empty()),
                new WorldDelta("minecraft:overworld", 12, 65, -3,
                        block("minecraft:air", ""),
                        block("minecraft:oak_planks", ""),
                        DropPolicy.NOT_APPLICABLE)));
    }

    private static OperationIntent atomicWorldMutation() {
        return OperationIntent.prepared("world-1", "job-1", 5, OperationKind.WORLD_MUTATION, List.of(
                new WorldDelta("minecraft:overworld", 0, 64, 0,
                        block("minecraft:air", ""), block("minecraft:oak_stairs", "facing=north;half=bottom"), DropPolicy.NOT_APPLICABLE),
                new WorldDelta("minecraft:overworld", 1, 64, 0,
                        block("minecraft:air", ""), block("minecraft:oak_stairs", "facing=east;half=bottom"), DropPolicy.NOT_APPLICABLE),
                new WorldDelta("minecraft:overworld", 2, 64, 0,
                        block("minecraft:stone", ""), block("minecraft:air", ""), DropPolicy.SUPPRESS)));
    }

    private static ObservedEvidence prefix(OperationIntent intent, int afterCount) {
        return observe(intent, index -> index < afterCount
                ? intent.deltas().get(index).after()
                : intent.deltas().get(index).before());
    }

    private static ObservedEvidence observe(OperationIntent intent, IntFunction<EvidenceSnapshot> values) {
        return new ObservedEvidence(observations(intent, values));
    }

    private static List<EvidenceObservation> observations(
            OperationIntent intent,
            IntFunction<EvidenceSnapshot> values) {
        List<EvidenceObservation> observations = new ArrayList<>();
        for (int index = 0; index < intent.deltas().size(); index++) {
            observations.add(new EvidenceObservation(intent.deltas().get(index).evidenceKey(), values.apply(index)));
        }
        return observations;
    }

    private static StackSnapshot stack(String itemId, int count, String components) {
        return StackSnapshot.of(itemId, count, bytes(components));
    }

    private static BlockStateSnapshot block(String blockId, String properties) {
        return BlockStateSnapshot.of(blockId, bytes(properties));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
