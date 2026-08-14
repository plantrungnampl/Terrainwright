package dev.ssa.fabric.entity;

import dev.ssa.fabric.builder.BuilderController;
import dev.ssa.fabric.builder.BuilderRuntimeService;
import dev.ssa.fabric.link.ContainerBinding;
import java.util.Map;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

public final class BuilderEntity extends PathfinderMob {
    public static final int CARRIED_SLOTS = 9;
    private static final String CARRIED_SLOT_PREFIX = "carried_slot_";

    private final SimpleContainer carriedItems = new SimpleContainer(CARRIED_SLOTS);
    private BuilderController controller;
    private double maxTickDisplacement;

    public BuilderEntity(EntityType<? extends BuilderEntity> entityType, Level level) {
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
        // BuilderController is the only production behavior owner.
    }

    public void attachController(BuilderController controller) {
        Objects.requireNonNull(controller, "controller");
        if (this.controller != null && this.controller != controller) {
            throw new IllegalStateException("Builder already has a controller");
        }
        this.controller = controller;
    }

    public boolean hasController() {
        return controller != null;
    }

    public void relinkChest(ContainerBinding binding) {
        if (controller != null) {
            controller.relinkChest(Objects.requireNonNull(binding, "binding"));
        }
    }

    public Map<String, Integer> missingMaterials(ServerLevel level) {
        return controller == null ? Map.of() : controller.missingMaterials(level);
    }

    public SimpleContainer carriedItems() {
        return carriedItems;
    }

    public int carriedItemCount(Item item) {
        Objects.requireNonNull(item, "item");
        int count = 0;
        for (int slot = 0; slot < carriedItems.getContainerSize(); slot++) {
            ItemStack stack = carriedItems.getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public double maxTickDisplacement() {
        return maxTickDisplacement;
    }

    @Override
    public void tick() {
        Vec3 before = position();
        super.tick();
        maxTickDisplacement = Math.max(maxTickDisplacement, before.distanceTo(position()));
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (controller != null) {
            controller.tick(level);
        }
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        for (int slot = 0; slot < carriedItems.getContainerSize(); slot++) {
            output.store(CARRIED_SLOT_PREFIX + slot, ItemStack.OPTIONAL_CODEC, carriedItems.getItem(slot));
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        for (int slot = 0; slot < carriedItems.getContainerSize(); slot++) {
            carriedItems.setItem(
                    slot,
                    input.read(CARRIED_SLOT_PREFIX + slot, ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
        }
    }

    @Override
    public void die(DamageSource damageSource) {
        if (level() instanceof ServerLevel serverLevel) {
            BuilderRuntimeService.observeDeath(serverLevel, this);
        }
        super.die(damageSource);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (level() instanceof ServerLevel serverLevel) {
            if (reason == RemovalReason.DISCARDED) {
                BuilderRuntimeService.observeRemoval(serverLevel, this);
            } else if (reason == RemovalReason.KILLED) {
                BuilderRuntimeService.observeDeath(serverLevel, this);
            }
        }
        super.remove(reason);
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, damageSource, recentlyHit);
        for (int slot = 0; slot < carriedItems.getContainerSize(); slot++) {
            ItemStack stack = carriedItems.removeItemNoUpdate(slot);
            if (!stack.isEmpty()) {
                spawnAtLocation(level, stack);
            }
        }
    }
}
