package dev.ssa.fabric.builder;

import dev.ssa.fabric.entity.BuilderEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

public final class FabricNavigationAdapter {
    private static final double ARRIVAL_DISTANCE_SQUARED = 1.44;
    private static final int MAX_ROUTE_ATTEMPTS = 3;
    private static final int STUCK_TICK_THRESHOLD = 100;

    private final BuilderEntity builder;
    private final InteractionPositionResolver resolver;
    private List<BlockPos> candidates = List.of();
    private int candidateIndex;
    private BlockPos target;
    private BlockPos standingPosition;
    private int routeAttempts;
    private String diagnostic = "";
    private final List<String> pathTrace = new ArrayList<>();
    private StuckDetector stuckDetector = new StuckDetector(STUCK_TICK_THRESHOLD);

    public FabricNavigationAdapter(BuilderEntity builder, InteractionPositionResolver resolver) {
        this.builder = Objects.requireNonNull(builder, "builder");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public void begin(ServerLevel level, BlockPos target) {
        Objects.requireNonNull(level, "level");
        initialize(target, resolver.resolve(level, builder, target));
    }

    public void beginFromScaffold(
            ServerLevel level, BlockPos target, BlockPos standingPosition) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(standingPosition, "standingPosition");
        if (!level.isLoaded(standingPosition)) {
            initialize(target, List.of());
            return;
        }
        initialize(target, List.of(standingPosition.immutable()));
    }

    private void initialize(BlockPos target, List<BlockPos> candidates) {
        this.target = Objects.requireNonNull(target, "target").immutable();
        this.candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        candidateIndex = 0;
        standingPosition = null;
        routeAttempts = 0;
        diagnostic = "";
        pathTrace.clear();
        stuckDetector = new StuckDetector(STUCK_TICK_THRESHOLD);
        builder.getNavigation().stop();
    }

    public Status tick(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        if (target == null || !level.isPositionEntityTicking(target)) {
            builder.getNavigation().stop();
            return Status.SUSPENDED_CHUNK_UNLOADED;
        }
        if (resolver.canInteractFromCurrentPosition(level, builder, target)) {
            builder.getNavigation().stop();
            return Status.ARRIVED;
        }
        if (!builder.onGround()) {
            StuckDetector.Observation observation = observeMovement(level);
            if (!observation.stuck()) {
                return Status.MOVING;
            }
            pathTrace.add("off-ground-stuck=" + observation.stationaryObservations()
                    + "@" + builder.blockPosition());
            builder.getNavigation().stop();
            standingPosition = null;
            stuckDetector = new StuckDetector(STUCK_TICK_THRESHOLD);
            return recompute(level) ? Status.MOVING : Status.BLOCKED;
        }
        if (standingPosition == null) {
            return tryNextCandidate(level) || recompute(level) ? Status.MOVING : Status.BLOCKED;
        }
        if (builder.distanceToSqr(Vec3.atCenterOf(standingPosition)) <= ARRIVAL_DISTANCE_SQUARED) {
            builder.getNavigation().stop();
            return Status.ARRIVED;
        }
        if (builder.getNavigation().isDone()) {
            standingPosition = null;
            return tryNextCandidate(level) || recompute(level) ? Status.MOVING : Status.BLOCKED;
        }
        StuckDetector.Observation observation = observeMovement(level);
        if (observation.stuck()) {
            pathTrace.add("stuck=" + observation.stationaryObservations()
                    + "@" + builder.blockPosition());
            builder.getNavigation().stop();
            standingPosition = null;
            stuckDetector = new StuckDetector(STUCK_TICK_THRESHOLD);
            return tryNextCandidate(level) || recompute(level) ? Status.MOVING : Status.BLOCKED;
        }
        return Status.MOVING;
    }

    private StuckDetector.Observation observeMovement(ServerLevel level) {
        return stuckDetector.observe(
                level.getGameTime(),
                builder.blockPosition(),
                StuckDetector.NavigationStatus.MOVING);
    }

    private boolean tryNextCandidate(ServerLevel level) {
        while (candidateIndex < candidates.size()) {
            BlockPos candidate = candidates.get(candidateIndex++);
            if (builder.distanceToSqr(Vec3.atCenterOf(candidate)) <= ARRIVAL_DISTANCE_SQUARED) {
                standingPosition = candidate;
                return true;
            }
            Path path = builder.getNavigation().createPath(candidate, 0);
            boolean canReach = path != null && path.canReach();
            boolean moving = path != null && builder.getNavigation().moveTo(path, 1.0);
            pathTrace.add("candidate=" + candidate
                    + ", below=" + level.getBlockState(candidate.below()).getBlock()
                    + ", path=" + (path == null ? "null" : path.getNodeCount())
                    + ", canReach=" + canReach
                    + ", moveTo=" + moving);
            if (moving) {
                standingPosition = candidate;
                return true;
            }
        }
        return false;
    }

    private boolean recompute(ServerLevel level) {
        if (++routeAttempts >= MAX_ROUTE_ATTEMPTS) {
            diagnostic = "target=" + target
                    + ", builder=" + builder.blockPosition()
                    + ", candidates=" + candidates.size()
                    + ", onGround=" + builder.onGround()
                    + ", interaction=" + resolver.currentPositionDiagnostic(level, builder, target)
                    + ", trace=" + pathTrace;
            return false;
        }
        candidates = resolver.resolve(level, builder, target);
        candidateIndex = 0;
        standingPosition = null;
        return true;
    }

    public String diagnostic() {
        return diagnostic;
    }

    public enum Status {
        MOVING,
        ARRIVED,
        BLOCKED,
        SUSPENDED_CHUNK_UNLOADED
    }
}
