package dev.ssa.fabric.construction;

import dev.ssa.construction.operation.BlockStateSnapshot;
import dev.ssa.construction.operation.StackSnapshot;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MinecraftSnapshotGameTests {
    private static final Logger LOGGER = LoggerFactory.getLogger("smart_survival_architect_s4");

    @GameTest(maxTicks = 20)
    public void componentAwareStackSnapshotRoundTripsExactly(GameTestHelper context) {
        MinecraftSnapshotAdapter adapter = new MinecraftSnapshotAdapter(context.getLevel().registryAccess());
        ItemStack named = new ItemStack(Items.DIAMOND_PICKAXE);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Architect's Exact Pick"));
        named.set(DataComponents.DAMAGE, 17);
        ItemStack foreignName = named.copy();
        foreignName.set(DataComponents.CUSTOM_NAME, Component.literal("Foreign Pick"));

        StackSnapshot snapshot = adapter.snapshot(named);
        StackSnapshot foreignSnapshot = adapter.snapshot(foreignName);
        ItemStack restored = adapter.restore(snapshot);

        context.assertTrue(!snapshot.equals(foreignSnapshot),
                "Same item/count with different data components compared equal");
        context.assertTrue(ItemStack.matches(named, restored), "Component-bearing stack did not round-trip exactly");
        context.assertTrue(adapter.restore(adapter.snapshot(ItemStack.EMPTY)).isEmpty(),
                "Empty stack did not round-trip exactly");
        LOGGER.info(
                "SSA_S4_CODEC stack_item=minecraft:diamond_pickaxe count={} components={} foreign_component=detected empty=roundtrip",
                restored.getCount(),
                restored.getComponentsPatch().size());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void exactBlockStatePropertiesRoundTrip(GameTestHelper context) {
        MinecraftSnapshotAdapter adapter = new MinecraftSnapshotAdapter(context.getLevel().registryAccess());
        BlockState eastTop = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.HALF, Half.TOP);
        BlockState westTop = eastTop.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);

        BlockStateSnapshot snapshot = adapter.snapshot(eastTop);
        BlockStateSnapshot foreignSnapshot = adapter.snapshot(westTop);
        BlockState restored = adapter.restore(snapshot);

        context.assertTrue(!snapshot.equals(foreignSnapshot),
                "Same block ID with different properties compared equal");
        context.assertValueEqual(restored, eastTop, "Exact block-state round-trip");
        LOGGER.info(
                "SSA_S4_CODEC block=minecraft:oak_stairs facing=east half=top foreign_property=detected roundtrip=exact");
        context.succeed();
    }
}
