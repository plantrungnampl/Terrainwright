package dev.ssa.fabric.link;

import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.common.permission.PermissionPort;
import dev.ssa.construction.job.BuildJob;
import dev.ssa.fabric.block.BuilderHutBlockEntity;
import dev.ssa.fabric.block.ModBlocks;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

public final class HutChestLinkGameTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FOREIGN_OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @GameTest(maxTicks = 20, padding = 20)
    public void boundaryAndRejectedContainersKeepPriorBinding(GameTestHelper context) {
        ServerLevel level = context.getLevel();
        BlockPos hut = context.absolutePos(new BlockPos(0, 1, 0));
        BlockPos accepted = hut.offset(16, 0, 0);
        BlockPos rejected = hut.offset(16, 0, 1);
        BlockPos barrel = hut.offset(1, 0, 0);
        level.setBlock(accepted, Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(rejected, Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), 3);
        BuilderChestLinkService service = new BuilderChestLinkService((owner, position) -> OWNER.equals(owner));

        BuilderChestLinkService.LinkResult linked = service.link(level, hut, OWNER, accepted, Optional.empty());
        BuilderChestLinkService.LinkResult tooFar = service.link(level, hut, OWNER, rejected, linked.binding());
        BuilderChestLinkService.LinkResult wrongContainer = service.link(level, hut, OWNER, barrel, linked.binding());
        BuilderChestLinkService.LinkResult wrongOwner = service.link(
                level, hut, FOREIGN_OWNER, accepted, linked.binding());

        context.assertTrue(linked.linked(), "distance squared 256 was rejected");
        context.assertValueEqual(linked.binding().orElseThrow().revision(), 1L, "initial binding revision");
        context.assertTrue(!tooFar.linked(), "distance squared 257 was accepted");
        context.assertValueEqual(tooFar.binding(), linked.binding(), "binding after distance rejection");
        context.assertTrue(!wrongContainer.linked(), "barrel was accepted as a V1 chest");
        context.assertValueEqual(wrongContainer.binding(), linked.binding(), "binding after barrel rejection");
        context.assertTrue(!wrongOwner.linked(), "non-owner linked the chest");
        context.assertValueEqual(wrongOwner.binding(), linked.binding(), "binding after ownership rejection");
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void linkingUsesTargetDimensionPermissionBoundary(GameTestHelper context) {
        ServerLevel level = context.getLevel();
        BlockPos hut = context.absolutePos(new BlockPos(0, 1, 0));
        BlockPos chest = context.absolutePos(new BlockPos(3, 1, 2));
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        PermissionPort permissions = new PermissionPort() {
            @Override
            public boolean canModify(UUID owner, GridPos position) {
                return true;
            }

            @Override
            public boolean canModify(UUID owner, String worldId, GridPos position) {
                return false;
            }
        };
        BuilderChestLinkService service = new BuilderChestLinkService(permissions);

        BuilderChestLinkService.LinkResult result = service.link(level, hut, OWNER, chest, Optional.empty());

        context.assertTrue(!result.linked(), "chest link bypassed target-dimension permission boundary");
        context.assertValueEqual(
                result.failure(),
                Optional.of(BuilderChestLinkService.LinkFailure.PERMISSION_DENIED),
                "dimension-aware permission rejection");
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void eitherDoubleChestHalfResolvesToOneCanonicalBinding(GameTestHelper context) {
        ServerLevel level = context.getLevel();
        BlockPos hut = context.absolutePos(new BlockPos(0, 1, 0));
        BlockPos left = context.absolutePos(new BlockPos(3, 1, 2));
        BlockPos right = left.east();
        setDoubleChest(level, left, right);
        BuilderChestLinkService service = new BuilderChestLinkService((owner, position) -> true);

        ContainerBinding fromLeft = service.link(level, hut, OWNER, left, Optional.empty())
                .binding().orElseThrow();
        ContainerBinding fromRight = service.link(level, hut, OWNER, right, Optional.empty())
                .binding().orElseThrow();

        context.assertValueEqual(fromLeft, fromRight, "canonical double chest binding");
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void splitPausesTransferUntilExplicitRelink(GameTestHelper context) {
        ServerLevel level = context.getLevel();
        BlockPos hut = context.absolutePos(new BlockPos(0, 1, 0));
        BlockPos left = context.absolutePos(new BlockPos(3, 1, 2));
        BlockPos right = left.east();
        setDoubleChest(level, left, right);
        BuilderChestLinkService service = new BuilderChestLinkService((owner, position) -> true);
        ContainerBinding doubleBinding = service.link(level, hut, OWNER, left, Optional.empty())
                .binding().orElseThrow();

        level.setBlock(right, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(left, Blocks.CHEST.defaultBlockState(), 3);

        context.assertTrue(!service.isTransferEligible(level, doubleBinding),
                "split double chest remained transfer eligible");
        ContainerBinding singleBinding = service.link(level, hut, OWNER, left, Optional.of(doubleBinding))
                .binding().orElseThrow();
        context.assertValueEqual(singleBinding.revision(), 2L, "binding revision after relink");
        context.assertTrue(service.isTransferEligible(level, singleBinding),
                "explicitly relinked single chest was not eligible");

        setDoubleChest(level, left, right);
        context.assertTrue(!service.isTransferEligible(level, singleBinding),
                "merged chest remained eligible under the single-chest binding");
        ContainerBinding mergedBinding = service.link(level, hut, OWNER, right, Optional.of(singleBinding))
                .binding().orElseThrow();
        context.assertValueEqual(mergedBinding.revision(), 3L, "binding revision after merge relink");
        context.assertTrue(service.isTransferEligible(level, mergedBinding),
                "explicitly relinked double chest was not eligible");
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void productionHutAndWorldRepositoryRemainIndependent(GameTestHelper context) {
        ServerLevel level = context.getLevel();
        BlockPos relativeHut = new BlockPos(1, 1, 1);
        context.setBlock(relativeHut, ModBlocks.BUILDER_HUT);
        BuilderHutBlockEntity hut = context.getBlockEntity(relativeHut, BuilderHutBlockEntity.class);
        UUID hutId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        String jobId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        BuildJob job = BuildJob.create(
                jobId,
                OWNER.toString(),
                hutId.toString(),
                "blueprint-1",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                NamespacedId.parse(level.dimension().identifier().toString()),
                new GridPos(1, 1, 1),
                0);
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(level);

        repository.saveJob(job);
        hut.setReferences(new BuilderHutBlockEntity.ReferenceState(
                hutId,
                Optional.of(OWNER),
                Optional.of(jobId),
                Optional.empty(),
                Optional.empty()));
        hut.setReferences(new BuilderHutBlockEntity.ReferenceState(
                hutId,
                Optional.of(OWNER),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));

        context.assertValueEqual(ServerBuildJobRepository.get(level), repository, "world repository instance");
        context.assertValueEqual(repository.findJob(jobId), Optional.of(job), "job after Hut reference removal");
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void linkingNeverForcesAnUnloadedChunk(GameTestHelper context) {
        ServerLevel level = context.getLevel();
        BlockPos hut = context.absolutePos(new BlockPos(0, 1, 0));
        BlockPos unloadedChest = context.absolutePos(new BlockPos(512, 1, 0));
        BuilderChestLinkService service = new BuilderChestLinkService((owner, position) -> true);

        context.assertTrue(!level.isLoaded(unloadedChest), "unloaded-chunk fixture was already loaded");
        BuilderChestLinkService.LinkResult result = service.link(
                level, hut, OWNER, unloadedChest, Optional.empty());

        context.assertTrue(!result.linked(), "unloaded chest target linked");
        context.assertValueEqual(
                result.failure(),
                Optional.of(BuilderChestLinkService.LinkFailure.CHUNK_UNLOADED),
                "unloaded chest failure");
        context.assertTrue(!level.isLoaded(unloadedChest), "chest link forced the target chunk");
        context.succeed();
    }

    private static void setDoubleChest(ServerLevel level, BlockPos left, BlockPos right) {
        BlockState leftState = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockState rightState = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.RIGHT);
        level.setBlock(left, leftState, 3);
        level.setBlock(right, rightState, 3);
    }
}
