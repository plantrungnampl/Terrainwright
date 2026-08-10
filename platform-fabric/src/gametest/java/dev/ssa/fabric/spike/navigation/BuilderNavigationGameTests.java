package dev.ssa.fabric.spike.navigation;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class BuilderNavigationGameTests {
    @GameTest(maxTicks = 40)
    public void disposableBuilderSpawns(GameTestHelper context) {
        SpikeBuilderEntity builder = context.spawn(SpikeEntityTypes.BUILDER, 0, 1, 0);

        context.assertTrue(builder.isAlive(), "Disposable Builder did not spawn alive");
        context.succeed();
    }
}
