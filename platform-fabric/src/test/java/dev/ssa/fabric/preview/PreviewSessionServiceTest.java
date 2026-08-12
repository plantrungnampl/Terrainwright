package dev.ssa.fabric.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ssa.architect.blueprint.Blueprint;
import dev.ssa.fabric.survey.SurveyModeService.SiteToken;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class PreviewSessionServiceTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID HUT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TOKEN_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void confirmationUsesOnlyTrustedStoredBlueprintAndIsSingleUse() {
        PreviewSessionService service = new PreviewSessionService();
        Blueprint trusted = PreviewTestFixtures.blueprint(7);
        SiteToken token = token(TOKEN_HASH);
        PreviewSessionService.PreviewSession session = service.store(
                OWNER, token, trusted, 90, 500, 12, 9, 9);

        Optional<PreviewSessionService.ConfirmationAuthority> authority = service.confirmObserved(
                OWNER,
                session.id(),
                trusted.hash(),
                HUT,
                "world-revision-1",
                499);

        assertEquals(trusted, authority.orElseThrow().blueprint());
        assertEquals(90, authority.orElseThrow().rotation());
        assertTrue(service.confirmObserved(
                OWNER,
                session.id(),
                trusted.hash(),
                HUT,
                "world-revision-1",
                499).isEmpty());
    }

    @Test
    void wrongAuthorityOrExpiredOrReplacedSessionCannotConfirm() {
        PreviewSessionService service = new PreviewSessionService();
        Blueprint first = PreviewTestFixtures.blueprint(7);
        Blueprint second = PreviewTestFixtures.blueprint(8);
        PreviewSessionService.PreviewSession replaced = service.store(
                OWNER, token(TOKEN_HASH), first, 0, 500, 1, 9, 9);
        PreviewSessionService.PreviewSession active = service.store(
                OWNER, token("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                second, 180, 600, 2, 9, 9);

        assertTrue(service.confirmObserved(
                OWNER, replaced.id(), first.hash(), HUT, "world-revision-1", 100).isEmpty());
        assertTrue(service.confirmObserved(
                UUID.randomUUID(), active.id(), second.hash(), HUT, "world-revision-1", 100).isEmpty());
        assertTrue(service.confirmObserved(
                OWNER, active.id(), first.hash(), HUT, "world-revision-1", 100).isEmpty());
        assertTrue(service.confirmObserved(
                OWNER, active.id(), second.hash(), HUT, "foreign-revision", 100).isEmpty());
        assertTrue(service.confirmObserved(
                OWNER, active.id(), second.hash(), HUT, "world-revision-1", 601).isEmpty());
        assertEquals(Optional.of(active), service.activeSession(OWNER));
    }

    private static SiteToken token(String tokenHash) {
        return new SiteToken(
                OWNER,
                Identifier.parse("minecraft:overworld"),
                new BlockPos(0, 64, 0),
                new BlockPos(4, 64, 4),
                tokenHash,
                "world-revision-1",
                700);
    }
}
