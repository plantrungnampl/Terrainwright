package dev.ssa.fabric.spike.restart;

import dev.ssa.construction.spike.persistence.BlockStateSnapshot;
import dev.ssa.construction.spike.persistence.DropPolicy;
import dev.ssa.construction.spike.persistence.InventoryDelta;
import dev.ssa.construction.spike.persistence.OperationIntent;
import dev.ssa.construction.spike.persistence.OperationKind;
import dev.ssa.construction.spike.persistence.StackSnapshot;
import dev.ssa.construction.spike.persistence.WorldDelta;
import dev.ssa.fabric.spike.persistence.AppendProbe;
import dev.ssa.fabric.spike.persistence.DurableAcknowledgement;
import dev.ssa.fabric.spike.persistence.OperationBoundaryListener;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class S5RestartScenario {
    private final S5CrashBoundary boundary;
    private final Consumer<S5CrashBoundary> terminator;

    public S5RestartScenario(S5CrashBoundary boundary, Consumer<S5CrashBoundary> terminator) {
        this.boundary = Objects.requireNonNull(boundary, "boundary");
        this.terminator = Objects.requireNonNull(terminator, "terminator");
    }

    public AppendProbe appendProbe() {
        return boundary == S5CrashBoundary.AFTER_APPEND_BEFORE_FSYNC_ACK
                ? path -> terminate(S5CrashBoundary.AFTER_APPEND_BEFORE_FSYNC_ACK)
                : AppendProbe.NONE;
    }

    public OperationBoundaryListener listener() {
        return new OperationBoundaryListener() {
            @Override
            public void beforePrepare() {
                terminate(S5CrashBoundary.BEFORE_PREPARED_APPEND);
            }

            @Override
            public void afterPrepared(DurableAcknowledgement acknowledgement) {
                terminate(S5CrashBoundary.AFTER_DURABLE_PREPARED);
            }

            @Override
            public void afterDelta(int deltaIndex) {
                terminate(switch (deltaIndex) {
                    case 0 -> S5CrashBoundary.AFTER_DELTA_1;
                    case 1 -> S5CrashBoundary.AFTER_DELTA_2;
                    case 2 -> S5CrashBoundary.AFTER_DELTA_3;
                    default -> throw new IllegalArgumentException("unsupported S5 delta index: " + deltaIndex);
                });
            }

            @Override
            public void afterAllDeltas() {
                terminate(S5CrashBoundary.AFTER_ALL_DELTAS_BEFORE_COMMIT);
            }

            @Override
            public void afterJournalCommit() {
                terminate(S5CrashBoundary.AFTER_JOURNAL_COMMIT_BEFORE_WAL_COMMIT);
            }

            @Override
            public void afterCommit() {
                terminate(S5CrashBoundary.AFTER_WAL_COMMIT_BEFORE_CLEAR);
            }

            @Override
            public void afterClear() {
                terminate(S5CrashBoundary.AFTER_CLEAR_CHECKPOINT);
            }
        };
    }

    private void terminate(S5CrashBoundary reached) {
        if (boundary == reached) {
            terminator.accept(reached);
        }
    }

    public static RestartFixture fixture(String fixtureId, String worldIdentity) {
        return new RestartFixture(
                fixtureId,
                worldIdentity,
                fixtureId + "-job",
                7,
                "BUILDING",
                "sha256:4A9E69E30A4B44CB92F75A1855A27B25",
                "minecraft:overworld@10,64,10",
                3,
                fixtureId + "-builder",
                "ACTIVE",
                false,
                "minecraft:overworld@11,64,10",
                BlockStateSnapshot.of("minecraft:cobblestone", bytes("temporary=true")));
    }

    public static OperationIntent intent(String fixtureId) {
        byte[] components = bytes("{minecraft:custom_name='S5 restart fixture'}");
        return OperationIntent.prepared(
                fixtureId + "-intent",
                fixtureId + "-job",
                7,
                OperationKind.WORLD_MUTATION,
                List.of(
                        new InventoryDelta(
                                fixtureId + "-builder",
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
