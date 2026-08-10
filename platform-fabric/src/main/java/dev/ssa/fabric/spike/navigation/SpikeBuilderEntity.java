package dev.ssa.fabric.spike.navigation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class SpikeBuilderEntity extends PathfinderMob {
    private static final int MAX_ATTEMPTS_PER_LEG = 3;
    private static final int STUCK_TICKS = 40;
    private static final double MINIMUM_PROGRESS = 0.05;
    private static final double ARRIVAL_DISTANCE_SQUARED = 1.44;

    private final SimpleContainer carriedItems = new SimpleContainer(1);
    private final List<Long> pathDurationsNanos = new ArrayList<>();
    private final List<String> pathTrace = new ArrayList<>();

    private State spikeState = State.IDLE;
    private BlockPos chestPos;
    private BlockPos targetPos;
    private boolean allowScaffold;
    private List<BlockPos> standingCandidates = List.of();
    private int candidateIndex;
    private int legAttempts;
    private int pathAttempts;
    private int ticksWithoutProgress;
    private BlockPos currentStandingPos;
    private double bestDistanceSquared = Double.MAX_VALUE;
    private double maxTickDisplacement;
    private String failureReason = "";

    public SpikeBuilderEntity(EntityType<? extends SpikeBuilderEntity> entityType, Level level) {
        super(entityType, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.30)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.STEP_HEIGHT, 1.0);
    }

    @Override
    protected void registerGoals() {
        // S2 drives the disposable entity through its measured scenario state machine.
    }

    public void beginScenario(BlockPos chestPos, BlockPos targetPos, boolean allowScaffold) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            throw new IllegalStateException("S2 scenarios are server-only");
        }

        this.chestPos = chestPos.immutable();
        this.targetPos = targetPos.immutable();
        this.allowScaffold = allowScaffold;
        this.spikeState = State.NAVIGATE_CHEST;
        beginLeg(serverLevel, this.chestPos);
    }

    @Override
    public void tick() {
        Vec3 before = position();
        super.tick();
        if (spikeState != State.IDLE) {
            maxTickDisplacement = Math.max(maxTickDisplacement, before.distanceTo(position()));
        }
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (spikeState == State.NAVIGATE_CHEST || spikeState == State.NAVIGATE_SITE) {
            tickScenario(level);
        }
    }

    private void tickScenario(ServerLevel level) {
        if (!level.isLoaded(chestPos) || !level.isLoaded(targetPos)) {
            getNavigation().stop();
            spikeState = State.SUSPENDED_CHUNK_UNLOADED;
            return;
        }

        if (currentStandingPos == null) {
            if (!onGround()) {
                return;
            }
            attemptPath(level);
            return;
        }

        double distanceSquared = distanceToSqr(Vec3.atCenterOf(currentStandingPos));
        if (distanceSquared <= ARRIVAL_DISTANCE_SQUARED) {
            getNavigation().stop();
            if (spikeState == State.NAVIGATE_CHEST) {
                extractTestItem(level);
            } else {
                executePlacement(level);
            }
            return;
        }

        if (bestDistanceSquared - distanceSquared >= MINIMUM_PROGRESS) {
            bestDistanceSquared = distanceSquared;
            ticksWithoutProgress = 0;
        } else {
            ticksWithoutProgress++;
        }

        if (getNavigation().isDone() || ticksWithoutProgress >= STUCK_TICKS) {
            getNavigation().stop();
            currentStandingPos = null;
            attemptPath(level);
        }
    }

    private void beginLeg(ServerLevel level, BlockPos interactionTarget) {
        standingCandidates = resolveStandingCandidates(level, interactionTarget);
        candidateIndex = 0;
        legAttempts = 0;
        currentStandingPos = null;
    }

    private List<BlockPos> resolveStandingCandidates(ServerLevel level, BlockPos interactionTarget) {
        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = interactionTarget.relative(direction);
            if (isWalkable(level, candidate) && hasInteractionLine(level, candidate, interactionTarget)) {
                candidates.add(candidate.immutable());
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate -> distanceToSqr(Vec3.atCenterOf(candidate))));
        return List.copyOf(candidates);
    }

    private boolean isWalkable(ServerLevel level, BlockPos feetPos) {
        return level.getBlockState(feetPos).getCollisionShape(level, feetPos).isEmpty()
                && level.getBlockState(feetPos.above()).getCollisionShape(level, feetPos.above()).isEmpty()
                && !level.getBlockState(feetPos.below()).getCollisionShape(level, feetPos.below()).isEmpty();
    }

    private boolean hasInteractionLine(ServerLevel level, BlockPos feetPos, BlockPos interactionTarget) {
        Vec3 hypotheticalEye = Vec3.atCenterOf(feetPos).add(0.0, 0.9, 0.0);
        Vec3 targetCenter = Vec3.atCenterOf(interactionTarget);
        if (hypotheticalEye.distanceToSqr(targetCenter) > 4.5 * 4.5) {
            return false;
        }

        BlockHitResult hit = level.clip(new ClipContext(
                hypotheticalEye,
                targetCenter,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                this));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(interactionTarget);
    }

    private void attemptPath(ServerLevel level) {
        while (candidateIndex < standingCandidates.size() && legAttempts < MAX_ATTEMPTS_PER_LEG) {
            BlockPos candidate = standingCandidates.get(candidateIndex++);
            if (distanceToSqr(Vec3.atCenterOf(candidate)) <= ARRIVAL_DISTANCE_SQUARED) {
                currentStandingPos = candidate;
                bestDistanceSquared = distanceToSqr(Vec3.atCenterOf(candidate));
                ticksWithoutProgress = 0;
                return;
            }

            long started = System.nanoTime();
            Path path = getNavigation().createPath(candidate, 0);
            pathDurationsNanos.add(System.nanoTime() - started);
            pathAttempts++;
            legAttempts++;

            boolean canReach = path != null && path.canReach();
            boolean startedMoving = canReach && getNavigation().moveTo(path, 1.0);
            pathTrace.add("from=" + blockPosition()
                    + ", onGround=" + onGround()
                    + ", below=" + level.getBlockState(blockPosition().below()).getBlock()
                    + ", candidate=" + candidate
                    + ", path=" + (path == null ? "null" : path.getNodeCount())
                    + ", canReach=" + canReach
                    + ", moveTo=" + startedMoving);

            if (startedMoving) {
                currentStandingPos = candidate;
                bestDistanceSquared = distanceToSqr(Vec3.atCenterOf(candidate));
                ticksWithoutProgress = 0;
                return;
            }
        }

        if (allowScaffold && spikeState == State.NAVIGATE_SITE) {
            allowScaffold = false;
        }
        block("No reachable standing position for " + spikeState
                + "; candidates=" + standingCandidates.size()
                + ", attempts=" + legAttempts
                + ", trace=" + pathTrace);
    }

    private void extractTestItem(ServerLevel level) {
        BlockEntity blockEntity = level.getBlockEntity(chestPos);
        if (!(blockEntity instanceof Container container)) {
            block("Linked chest is not a container: " + chestPos);
            return;
        }

        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(Items.COBBLESTONE)) {
                ItemStack extracted = container.removeItem(slot, 1);
                container.setChanged();
                carriedItems.setItem(0, extracted);
                spikeState = State.NAVIGATE_SITE;
                beginLeg(level, targetPos);
                return;
            }
        }
        block("Linked chest contains no cobblestone");
    }

    private void executePlacement(ServerLevel level) {
        if (SpikePlacementExecutor.placeCobblestone(level, this, targetPos, carriedItems)) {
            spikeState = State.SUCCESS;
        } else {
            block("Server placement executor rejected target " + targetPos);
        }
    }

    private void block(String reason) {
        failureReason = reason;
        spikeState = State.BLOCKED;
        getNavigation().stop();
    }

    public State spikeState() {
        return spikeState;
    }

    public int carriedCobblestone() {
        ItemStack stack = carriedItems.getItem(0);
        return stack.is(Items.COBBLESTONE) ? stack.getCount() : 0;
    }

    public int pathAttempts() {
        return pathAttempts;
    }

    public double maxTickDisplacement() {
        return maxTickDisplacement;
    }

    public String failureReason() {
        return failureReason;
    }

    public List<Long> pathDurationsNanos() {
        return List.copyOf(pathDurationsNanos);
    }

    public enum State {
        IDLE,
        NAVIGATE_CHEST,
        NAVIGATE_SITE,
        SUSPENDED_CHUNK_UNLOADED,
        BLOCKED,
        SUCCESS
    }
}
