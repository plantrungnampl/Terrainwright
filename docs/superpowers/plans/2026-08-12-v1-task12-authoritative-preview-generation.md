# V1 Task 12 Authoritative Preview Generation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans`, `superpowers:test-driven-development`, `superpowers:systematic-debugging`, and `superpowers:verification-before-completion` task-by-task.

**Goal:** Add the server-authoritative Survey Mode, bounded terrain capture, preview session protocol, and cancellable generation worker required to turn validated site selections into server-owned Blueprint previews.

**Architecture:** `SurveyModeService` owns short-lived player/table sessions and single-use site tokens. It accepts a site only after same-dimension, table/player/anchor range, top-surface, permission, and independent server ray-trace checks. `FabricTerrainScanner` captures a bounded immutable `TerrainSnapshot` synchronously on the server thread without loading chunks. `ArchitectGenerationService` sends only immutable core inputs to a worker and marshals results back to a supplied server executor. `PreviewSessionService` stores the latest server-created Blueprint per player, bound to owner, token hash, world revision, expiry, and client nonce; confirmation accepts only the stored session/hash and never a client Blueprint.

**Assumptions and boundaries:** Survey range is inclusive at 64 blocks and measured from the active Architect Table to both player and selected anchor. A selected anchor is the upper face of a solid top block with replaceable space above. Sessions expire on disconnect, dimension change, range violation, cancellation, or replacement. Tokens are unpredictable, owner-bound, expiring, and consumed when preview generation is accepted. Snapshot width/depth are derived from validated requirements plus a small fixed margin and capped; unloaded cells reject capture instead of forcing chunks. This task defines payload DTOs but does not register network handlers or implement the Architect screen/renderer from Task 13. BuildJob creation remains a confirmation decision; no client-provided Blueprint/hash can enter the repository.

**Tech Stack:** Java 25 immutable records, Minecraft/Fabric 26.2 server APIs, `CompletableFuture` with injected executors, existing `ArchitectEngine`, JUnit 5.14.3, Fabric GameTest.

---

### Task 1: Lock Survey Mode authority

**Files:**
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/survey/SurveyModeService.java`
- Create: `platform-fabric/src/gametest/java/dev/ssa/fabric/preview/PreviewAuthorityGameTest.java`

- [ ] Start Survey Mode only for an owned/authorized Architect Table and replace any prior session for that player.
- [ ] Reject selection without a session, wrong dimension, table/player/anchor distance over 64, non-top surface, denied permission, unloaded chunks, or a server ray-trace mismatch.
- [ ] Issue one opaque expiring site token bound to player, table, dimension, anchor, and world revision; cancellation/expiry/consumption invalidates it.

### Task 2: Capture a bounded immutable terrain snapshot

**Files:**
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/world/FabricTerrainScanner.java`
- Test: `platform-fabric/src/gametest/java/dev/ssa/fabric/preview/PreviewAuthorityGameTest.java`

- [ ] Capture exact top height/material plus bounded water/lava/tree/obstruction evidence around the approved anchor.
- [ ] Derive slope metrics and a deterministic revision fingerprint from the observed cells.
- [ ] Refuse invalid dimensions or any unloaded cell and prove scanning never forces a chunk.

### Task 3: Define server-owned preview sessions and confirmation

**Files:**
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/network/PreviewPayloads.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/preview/PreviewSessionService.java`
- Test: `platform-fabric/src/test/java/dev/ssa/fabric/preview/PreviewSessionServiceTest.java`

- [ ] Store immutable `PreviewSession(id, blueprintHash, blueprint, owner, expiryRevision, surveyTokenHash, worldRevision, requestNonce)` created only from a generation result.
- [ ] Reject expiry, wrong owner/Hut/token/world revision, unexpected hash, replaced sessions, and random client-supplied Blueprint data without producing confirmation authority.
- [ ] Make successful confirmation single-use and return only the trusted stored Blueprint plus validated identities needed by the later BuildJob command.

### Task 4: Run cancellable generation with latest-request-wins semantics

**Files:**
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/preview/ArchitectGenerationService.java`
- Test: `platform-fabric/src/test/java/dev/ssa/fabric/preview/ArchitectGenerationServiceTest.java`

- [ ] Capture terrain before dispatch; the worker receives only `HouseRequirements`, `TerrainSnapshot`, immutable style data, and registry snapshot.
- [ ] Return completion to an injected server executor before consuming the Survey token or storing a PreviewSession.
- [ ] Cancel/ignore a prior player request when a newer nonce is accepted so stale completion cannot replace the current preview.

### Task 5: Verify, review, and commit

- [ ] Capture focused red tests before implementation, then run platform unit tests and all Fabric GameTests.
- [ ] Re-index the code graph and request independent P1/P2 review.
- [ ] Run `clean test build --no-daemon`, S1 layout verification, client GameTest, and diff checks.
- [ ] Commit only the Task 12 slice with `feat: generate authoritative terrain aware previews`.
