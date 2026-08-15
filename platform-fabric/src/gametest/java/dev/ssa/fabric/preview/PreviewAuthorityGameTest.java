package dev.ssa.fabric.preview;

import dev.ssa.architect.blueprint.BlockRole;
import dev.ssa.architect.blueprint.Blueprint;
import dev.ssa.architect.blueprint.BlueprintBlock;
import dev.ssa.architect.blueprint.BuildPhase;
import dev.ssa.architect.material.MaterialRole;
import dev.ssa.architect.model.BlockStateSpec;
import dev.ssa.architect.model.GridPos;
import dev.ssa.architect.model.NamespacedId;
import dev.ssa.architect.model.StyleId;
import dev.ssa.architect.model.TerrainSnapshot;
import dev.ssa.architect.scoring.ScoreBreakdown;
import dev.ssa.architect.terrain.TerrainPlan;
import dev.ssa.architect.validation.BlueprintValidation;
import dev.ssa.fabric.block.ModBlocks;
import dev.ssa.fabric.link.ContainerBinding;
import dev.ssa.fabric.network.PreviewPayloads.ConfirmPreview;
import dev.ssa.fabric.persistence.ServerBuildJobRepository;
import dev.ssa.fabric.survey.SurveyModeService;
import dev.ssa.fabric.world.FabricTerrainScanner;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

public final class PreviewAuthorityGameTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @GameTest(maxTicks = 20, padding = 20)
    public void surveyRequiresServerRayTraceAndIssuesSingleUseToken(GameTestHelper context) {
        BlockPos table = context.absolutePos(new BlockPos(1, 1, 1));
        BlockPos anchor = context.absolutePos(new BlockPos(5, 1, 5));
        context.getLevel().setBlock(table, ModBlocks.ARCHITECT_TABLE.defaultBlockState(), 3);
        context.getLevel().setBlock(anchor, Blocks.STONE.defaultBlockState(), 3);
        context.getLevel().setBlock(anchor.above(), Blocks.AIR.defaultBlockState(), 3);
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        player.setPos(Vec3.atCenterOf(anchor.above(3)));
        SurveyModeService service = new SurveyModeService((owner, position) -> true);

        SurveyModeService.SelectionResult noSession = service.selectSite(player, anchor, "world-r1", 10);
        context.assertValueEqual(
                noSession.failure(),
                Optional.of(SurveyModeService.Failure.NO_ACTIVE_SESSION),
                "selection without Survey Mode");
        context.assertTrue(service.start(player, table, 10).session().isPresent(), "Survey Mode did not start");
        SurveyModeService.SelectionResult tooFar = service.selectSite(
                player, table.offset(SurveyModeService.MAX_RANGE + 1, 0, 0), "world-r1", 11);
        context.assertValueEqual(
                tooFar.failure(),
                Optional.of(SurveyModeService.Failure.ANCHOR_OUT_OF_RANGE),
                "anchor beyond 64 blocks");

        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(anchor.east(4)));
        SurveyModeService.SelectionResult mismatched = service.selectSite(player, anchor, "world-r1", 11);
        context.assertValueEqual(
                mismatched.failure(),
                Optional.of(SurveyModeService.Failure.RAY_TRACE_MISMATCH),
                "server ray trace mismatch");

        ServerLevel nether = context.getLevel().getServer().getLevel(Level.NETHER);
        context.assertTrue(nether != null, "Nether level was unavailable");
        context.assertTrue(player.teleportTo(
                nether,
                player.getX(),
                player.getY(),
                player.getZ(),
                Set.<Relative>of(),
                player.getYRot(),
                player.getXRot(),
                false), "mock player did not enter a different dimension");
        SurveyModeService.SelectionResult wrongDimension = service.selectSite(player, anchor, "world-r1", 12);
        context.assertValueEqual(
                wrongDimension.failure(),
                Optional.of(SurveyModeService.Failure.WRONG_DIMENSION),
                "cross-dimension Survey selection");
        context.assertTrue(player.teleportTo(
                context.getLevel(),
                Vec3.atCenterOf(anchor.above(3)).x(),
                Vec3.atCenterOf(anchor.above(3)).y(),
                Vec3.atCenterOf(anchor.above(3)).z(),
                Set.<Relative>of(),
                player.getYRot(),
                player.getXRot(),
                false), "mock player did not return to the test dimension");
        context.assertTrue(service.start(player, table, 12).session().isPresent(), "Survey Mode did not restart");

        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(anchor).add(0, 0.5, 0));
        SurveyModeService.IssuedSiteToken issued = service.selectSite(player, anchor, "world-r1", 13)
                .token().orElseThrow();
        context.assertTrue(
                service.validateToken(player.getUUID(), issued.rawToken(), 13).isPresent(),
                "issued Survey token was not valid");
        context.assertTrue(service.start(player, table, 14).session().isPresent(), "replacement Survey did not start");
        context.assertTrue(
                service.validateToken(player.getUUID(), issued.rawToken(), 14).isEmpty(),
                "starting a new Survey retained the prior owner token");
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(anchor).add(0, 0.5, 0));
        SurveyModeService.IssuedSiteToken replacement = service.selectSite(player, anchor, "world-r2", 15)
                .token().orElseThrow();
        context.assertTrue(
                service.consumeToken(player.getUUID(), replacement.rawToken(), 15).isPresent(),
                "valid Survey token was not consumable");
        context.assertTrue(
                service.consumeToken(player.getUUID(), replacement.rawToken(), 15).isEmpty(),
                "Survey token was reusable");
        SurveyModeService.IssuedSiteToken successor = service.reissue(replacement.token(), 16)
                .orElseThrow();
        context.assertTrue(
                service.validateToken(player.getUUID(), replacement.rawToken(), 16).isEmpty(),
                "reissuing a token revived its consumed predecessor");
        context.assertTrue(
                service.validateToken(player.getUUID(), successor.rawToken(), 16).isPresent(),
                "successor Survey token was not valid for regeneration");
        context.assertTrue(
                successor.token().anchor().equals(replacement.token().anchor()),
                "successor Survey token changed the server-selected anchor");
        context.assertTrue(service.start(player, table, 17).session().isPresent(), "new Survey did not start");
        context.assertTrue(
                service.reissue(replacement.token(), 17).isEmpty(),
                "consumed token restored authority over a newer active Survey");
        context.assertTrue(
                service.activeSession(player.getUUID()).isPresent(),
                "stale token reissue removed the newer Survey session");
        context.succeed();
    }

    @GameTest(maxTicks = 20, padding = 20)
    public void terrainScanIsBoundedDeterministicAndNeverLoadsChunks(GameTestHelper context) {
        ServerLevel level = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(1, 1, 1));
        int surfaceY = Integer.MIN_VALUE;
        for (int z = 0; z < 9; z++) {
            for (int x = 0; x < 9; x++) {
                surfaceY = Math.max(
                        surfaceY,
                        level.getHeight(
                                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                start.getX() + x,
                                start.getZ() + z));
            }
        }
        BlockPos anchor = new BlockPos(start.getX(), surfaceY, start.getZ());
        for (int z = 0; z < 9; z++) {
            for (int x = 0; x < 9; x++) {
                BlockPos surface = anchor.offset(x, 0, z);
                level.setBlock(surface, Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(surface.above(), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        FabricTerrainScanner scanner = new FabricTerrainScanner();

        TerrainSnapshot first = scanner.scan(level, anchor, 9, 9).orElseThrow();
        TerrainSnapshot second = scanner.scan(level, anchor, 9, 9).orElseThrow();
        context.assertValueEqual(first.revisionFingerprint(), second.revisionFingerprint(), "stable fingerprint");
        context.assertValueEqual(first.width(), 9, "snapshot width");
        context.assertValueEqual(first.depth(), 9, "snapshot depth");
        context.assertValueEqual(
                first.surfaceMaterialAt(0, 0),
                NamespacedId.parse("minecraft:stone"),
                "surface material");

        level.setBlock(anchor, Blocks.DIRT.defaultBlockState(), 3);
        TerrainSnapshot changed = scanner.scan(level, anchor, 9, 9).orElseThrow();
        context.assertTrue(
                !first.revisionFingerprint().equals(changed.revisionFingerprint()),
                "world mutation did not change snapshot fingerprint");

        BlockPos unloaded = anchor.offset(512, 0, 0);
        context.assertTrue(!level.isLoaded(unloaded), "unloaded scanner fixture was already loaded");
        context.assertTrue(scanner.scan(level, unloaded, 9, 9).isEmpty(), "unloaded scan succeeded");
        context.assertTrue(!level.isLoaded(unloaded), "terrain scan forced an unloaded chunk");
        context.succeed();
    }

    @GameTest(maxTicks = 20, padding = 20)
    public void confirmationRescansTerrainAndNeverCreatesAClientChosenJob(GameTestHelper context) {
        ServerLevel level = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(1, 1, 1));
        int surfaceY = Integer.MIN_VALUE;
        for (int z = 0; z < 9; z++) {
            for (int x = 0; x < 9; x++) {
                surfaceY = Math.max(
                        surfaceY,
                        level.getHeight(
                                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                start.getX() + x,
                                start.getZ() + z));
            }
        }
        BlockPos anchor = new BlockPos(start.getX(), surfaceY, start.getZ());
        for (int z = 0; z < 9; z++) {
            for (int x = 0; x < 9; x++) {
                level.setBlock(anchor.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(anchor.offset(x, 1, z), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        FabricTerrainScanner scanner = new FabricTerrainScanner();
        TerrainSnapshot snapshot = scanner.scan(level, anchor, 9, 9).orElseThrow();
        PreviewSessionService sessions = new PreviewSessionService();
        Blueprint blueprint = blueprint();
        SurveyModeService.SiteToken token = new SurveyModeService.SiteToken(
                OWNER,
                level.dimension().identifier(),
                anchor.west(),
                anchor,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                snapshot.revisionFingerprint(),
                700);
        PreviewSessionService.PreviewSession session = sessions.store(
                OWNER, token, blueprint, 0, 500, 1, 9, 9);
        UUID hutId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        ServerBuildJobRepository repository = ServerBuildJobRepository.get(level);
        repository.saveHutState(new ServerBuildJobRepository.HutState(
                hutId, OWNER, Optional.empty(), Optional.empty(), Optional.empty(), 0));
        int jobsBefore = repository.jobs().size();

        PreviewSessionService.ConfirmationResult noChest = sessions.confirmDetailed(
                level,
                scanner,
                (owner, position) -> true,
                repository,
                OWNER,
                new ConfirmPreview(session.id(), blueprint.hash(), hutId),
                99);
        context.assertValueEqual(
                noChest.failure(),
                Optional.of(PreviewSessionService.ConfirmationFailure.HUT_CHEST_NOT_LINKED),
                "unlinked Hut confirmation failure");
        context.assertTrue(
                sessions.activeSession(OWNER).isPresent(),
                "unlinked Hut confirmation consumed the server preview session");
        context.assertValueEqual(repository.jobs().size(), jobsBefore, "jobs after unlinked Hut confirmation");

        ContainerBinding binding = ContainerBinding.resolve(
                level.dimension().identifier(),
                anchor.offset(12, 0, 0),
                Optional.empty(),
                Optional.empty());
        repository.saveHutState(new ServerBuildJobRepository.HutState(
                hutId, OWNER, Optional.empty(), Optional.of(binding), Optional.empty(), 1));

        Optional<PreviewSessionService.ConfirmationAuthority> forged = sessions.confirm(
                level,
                scanner,
                (owner, position) -> true,
                repository,
                OWNER,
                new ConfirmPreview(
                        session.id(),
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        hutId),
                100);
        context.assertTrue(forged.isEmpty(), "client-forged Blueprint hash confirmed");
        context.assertValueEqual(repository.jobs().size(), jobsBefore, "jobs after forged confirmation");

        level.setBlock(anchor, Blocks.DIRT.defaultBlockState(), 3);
        Optional<PreviewSessionService.ConfirmationAuthority> stale = sessions.confirm(
                level,
                scanner,
                (owner, position) -> true,
                repository,
                OWNER,
                new ConfirmPreview(session.id(), blueprint.hash(), hutId),
                101);
        context.assertTrue(stale.isEmpty(), "terrain-stale preview confirmed");
        context.assertValueEqual(repository.jobs().size(), jobsBefore, "jobs after stale confirmation");

        level.setBlock(anchor, Blocks.STONE.defaultBlockState(), 3);
        Optional<PreviewSessionService.ConfirmationAuthority> protectedArea = sessions.confirm(
                level,
                scanner,
                (owner, position) -> false,
                repository,
                OWNER,
                new ConfirmPreview(session.id(), blueprint.hash(), hutId),
                102);
        context.assertTrue(protectedArea.isEmpty(), "protection-denied preview confirmed");
        context.assertTrue(
                sessions.activeSession(OWNER).isPresent(),
                "protection denial consumed the server preview session");
        context.assertValueEqual(repository.jobs().size(), jobsBefore, "jobs after protection denial");

        Optional<PreviewSessionService.ConfirmationAuthority> valid = sessions.confirm(
                level,
                scanner,
                (owner, position) -> true,
                repository,
                OWNER,
                new ConfirmPreview(session.id(), blueprint.hash(), hutId),
                103);
        context.assertTrue(valid.isPresent(), "unchanged authoritative preview did not confirm");
        context.assertValueEqual(repository.jobs().size(), jobsBefore, "confirmation created a BuildJob");
        context.succeed();
    }

    private static Blueprint blueprint() {
        GridPos position = new GridPos(0, 0, 0);
        BlueprintBlock block = new BlueprintBlock(
                position,
                BlockRole.FOUNDATION,
                MaterialRole.FOUNDATION_STONE,
                new BlockStateSpec(NamespacedId.parse("minecraft:cobblestone"), Map.of()),
                BuildPhase.FOUNDATION,
                Set.of());
        return new Blueprint(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                1,
                StyleId.parse("smart_survival_architect:medieval"),
                new Blueprint.LocalBounds(position, position),
                Set.of(position),
                1,
                List.of(),
                List.of(block),
                BuildPhase.canonicalOrder(),
                new TerrainPlan(
                        TerrainPlan.Strategy.FLAT,
                        0,
                        0,
                        0,
                        0,
                        false,
                        false,
                        TerrainPlan.SalvagePolicy.DISCARD_NO_DROPS,
                        List.of()),
                ScoreBreakdown.unscored(),
                BlueprintValidation.valid(),
                Blueprint.CURRENT_FORMAT_VERSION);
    }
}
