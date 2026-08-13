# V1.0 Completion Implementation Plan

**Date:** 2026-08-13  
**Spec:** `docs/superpowers/specs/2026-08-10-v1-gate-first-delivery-design.md`  
**Canonical scope:** `Smart-Survival-Architect-Master-Architecture-R2/docs/roadmap/12-milestones-and-release.md` M0-M10

## Goal

Complete the R2 V1.0 release target in the existing four-module repository without changing the V1 trust boundary. The current checkout already contains the architect/preview/OperationIntent/Builder material-loop slices and their evidence. This plan adds the remaining release behavior, then runs the complete release gate matrix.

## Global constraints

- V1 builds houses only and supports Medieval, Japanese, and Modern styles.
- `platform-fabric` is the only module that imports Minecraft/Fabric runtime classes.
- Every inventory or world mutation goes through `OperationIntent`; no direct mutation path is added.
- Restart reconciliation runs before scheduling; unknown evidence is quarantined and cannot resume automatically.
- Builder unload/chunk suspension is not Builder death; no automatic replacement is created.
- Site-preparation removal and restoration use no normal item drops or XP.
- No teleport fallback, forced chunk loading, arbitrary chest scanning, multi-Builder scheduling, LLM, basement, infrastructure, settlement, or remote content service is added.
- New behavior follows red-green-refactor. Each task has a focused failing test before production code.

### Task 1: Complete bounded Builder recovery and scaffolding

**Files:**
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/builder/StuckDetector.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/builder/RecoveryController.java`
- Create: `construction-core/src/main/java/dev/ssa/construction/scaffold/ScaffoldPlan.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/builder/FabricScaffoldPlanner.java`
- Modify: `platform-fabric/src/main/java/dev/ssa/fabric/builder/BuilderController.java`
- Test: `construction-core/src/test/java/dev/ssa/construction/scaffold/ScaffoldPlanTest.java`
- Test: `platform-fabric/src/gametest/java/dev/ssa/fabric/builder/ScaffoldingRecoveryGameTest.java`

**Interfaces:**
- `StuckDetector.observe(tick, position, navigationStatus)` returns a bounded observation and never mutates the world.
- `RecoveryController.nextAttempt(observation, target)` returns `RETRY_ROUTE`, `LOCAL_RESET`, `SCAFFOLD`, or `BLOCKED`.
- `ScaffoldPlan` contains at most 24 temporary placements, maximum height 12, and provenance for every temporary block.

- [x] Write a test proving the detector reaches `BLOCKED` after the configured bounded attempts without a teleport call.
- [x] Run `./gradlew :construction-core:test --tests '*ScaffoldPlanTest'`; confirm the new test fails because the recovery API is absent.
- [x] Implement the pure plan and recovery ladder, then wire it into the existing controller.
- [x] Add GameTests for a two-floor target that completes with temporary scaffolding and an impossible target that stops with no further world mutations.
- [x] Run the focused tests, then `./gradlew :construction-core:test :platform-fabric:test`; commit `feat: add bounded builder recovery and scaffolding`.

### Task 2: Add durable lifecycle, chunk suspension, and restart reconciliation

**Files:**
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/job/JobRecoveryService.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/job/ChunkSuspensionService.java`
- Modify: `platform-fabric/src/main/java/dev/ssa/fabric/persistence/ServerBuildJobRepository.java`
- Modify: `platform-fabric/src/main/java/dev/ssa/fabric/entity/BuilderEntity.java`
- Modify: `platform-fabric/src/main/java/dev/ssa/fabric/builder/BuilderRuntimeService.java`
- Test: `construction-core/src/test/java/dev/ssa/construction/job/BuilderLifecycleTest.java`
- Test: `platform-fabric/src/gametest/java/dev/ssa/fabric/job/RestartReconciliationGameTest.java`
- Test: `platform-fabric/src/gametest/java/dev/ssa/fabric/job/BuilderLossRecoveryGameTest.java`

**Interfaces:**
- `BuilderLifecycle` persists `ACTIVE`, `SUSPENDED`, `TOMBSTONED` with stable identity and explicit replacement authorization.
- `JobRecoveryService.reconcile(world)` resolves OperationIntent, binding topology, lifecycle, and task/journal state in that order before reopening scheduling.
- `ChunkSuspensionService` transitions jobs to `SUSPENDED_CHUNK_UNLOADED` and resumes only after required chunks are loaded.

- [ ] Write pure lifecycle transition tests for unload, authoritative death, Hut loss, explicit replacement, and illegal automatic replacement.
- [ ] Run the tests and observe the expected failure before implementation.
- [ ] Persist tombstones and orphaned jobs; wire startup and world-load reconciliation ahead of `BuilderRuntimeService` scheduling.
- [ ] Add real-process restart cases for every OperationIntent boundary and GameTests for death/Hut loss/chunk unload.
- [ ] Run the full S4/S5 harness plus focused tests; commit `feat: reconcile builder lifecycle and jobs safely`.

### Task 3: Implement server Stop and Safe Undo

**Files:**
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/undo/FabricUndoExecutor.java`
- Modify: `platform-fabric/src/main/java/dev/ssa/fabric/job/JobRecoveryService.java`
- Modify: `platform-fabric/src/main/java/dev/ssa/fabric/network/JobReplicationService.java`
- Test: `construction-core/src/test/java/dev/ssa/construction/undo/UndoPlannerTest.java`
- Test: `platform-fabric/src/gametest/java/dev/ssa/fabric/undo/SafeUndoGameTest.java`

**Interfaces:**
- `FabricUndoExecutor.undo(jobId, owner)` consumes reverse journal entries and emits only `OperationIntent(WORLD_MUTATION)` operations whose current state still equals the job-written after-state.
- A changed/protected cell produces `CONFLICT_PRESERVE_CURRENT` and leaves the current block untouched.
- Stop prevents new scheduling, drains the active intent, and leaves a recoverable job record.

- [ ] Add red tests for an external edit that must be preserved and for no material/XP refund on construction or terrain-preparation undo.
- [ ] Implement reverse journal execution through the existing permission and OperationIntent services.
- [ ] Add owner-permission and multiplayer command tests; verify stale client commands are rejected.
- [ ] Run `./gradlew :construction-core:test :platform-fabric:test` and the GameTest suite; commit `feat: add stop and safe undo`.

### Task 4: Finish Builder Hut progress and control replication

**Files:**
- Create or modify: `platform-fabric/src/main/java/dev/ssa/fabric/network/JobPayloads.java`
- Create or modify: `platform-fabric/src/main/java/dev/ssa/fabric/network/JobReplicationService.java`
- Create or modify: `platform-fabric/src/client/java/dev/ssa/fabric/client/screen/BuilderHutScreen.java`
- Test: `platform-fabric/src/test/java/dev/ssa/fabric/network/JobDeltaTest.java`

**Interfaces:**
- `JobSnapshot` and `JobDelta` carry owner, revision, state, progress, missing materials, conflicts, and diagnostics.
- Client state ignores deltas older than its current revision and never performs authoritative mutation.
- Pause, Resume, Stop, and Undo are server-validated commands.

- [ ] Write the stale-delta and unauthorized-command tests first and verify they fail.
- [ ] Implement compact revisioned payloads and server-side command validation.
- [ ] Render actionable missing-material/conflict/recovery status without adding client classes to server initialization.
- [ ] Run unit tests and a two-player GameTest; commit `feat: expose builder progress and controls`.

### Task 5: Close V1 procedural generation and style coverage

**Files:**
- Modify: `architect-core/src/main/java/dev/ssa/architect/ArchitectEngine.java`
- Modify: `architect-core/src/main/java/dev/ssa/architect/style/BuiltinStylePalettes.java`
- Test: `architect-core/src/test/java/dev/ssa/architect/property/HouseGenerationPropertyTest.java`
- Test: `architect-core/src/test/java/dev/ssa/architect/style/StyleGalleryTest.java`

**Interfaces:**
- `ArchitectEngine.generate` remains deterministic for `(seed, requirements, terrain, style)` and returns a validated immutable Blueprint.
- All three built-in styles produce distinct geometry/material identity while sharing the same construction contracts.

- [ ] Add seeded property tests covering site bounds, dependency ordering, no illegal placements, and reproducibility; run them to capture the red failures.
- [ ] Add gallery assertions for Small/Medium Medieval, Japanese, and Modern fixtures, including gentle slopes.
- [ ] Fix only failing generation/style behavior and preserve existing immutable/core boundaries.
- [ ] Run the property and full architect test suites; commit `test: close v1 generation and style coverage`.

### Task 6: Add safe palette overrides and protection hooks

**Files:**
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/style/StyleDataLoader.java`
- Create: `platform-fabric/src/main/resources/data/smart_survival_architect/styles/medieval.json`
- Create: `platform-fabric/src/main/resources/data/smart_survival_architect/styles/japanese.json`
- Create: `platform-fabric/src/main/resources/data/smart_survival_architect/styles/modern.json`
- Modify: `platform-fabric/src/main/java/dev/ssa/fabric/permission/FabricPermissionAdapter.java`
- Test: `platform-fabric/src/gametest/java/dev/ssa/fabric/style/PaletteFallbackGameTest.java`

**Interfaces:**
- `StyleDataLoader` validates required semantic roles, block existence, capability, finite weights, and complete fallback before publishing a palette.
- `FabricPermissionAdapter` is the single protection hook used by preview confirmation, material transfer, world mutation, and undo.

- [ ] Add a red fixture with an absent optional mod block and assert fallback to a compatible vanilla role.
- [ ] Implement loader validation; reject arbitrary aliases and incomplete palettes.
- [ ] Add protection-denied tests for preview confirmation, chest transfer, placement, and undo.
- [ ] Run the GameTest and clean module tests; commit `feat: add safe style overrides and protection adapter`.

### Task 7: Release metrics, regression matrix, and documentation

**Files:**
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/debug/DebugMetrics.java`
- Create: `construction-core/src/test/java/dev/ssa/construction/property/ConstructionInvariantPropertyTest.java`
- Create: `platform-fabric/src/gametest/java/dev/ssa/fabric/release/ReleaseScenarioGameTests.java`
- Create: `CHANGELOG.md`
- Create: `docs/player/getting-started.md`
- Create: `docs/server/configuration-and-safety.md`
- Create: `docs/developer/style-palette-format.md`
- Modify: `README.md`

**Interfaces:**
- `DebugMetrics` records generation, path, scaffold, material-trip, conflict, and reconciliation counters without per-tick INFO logging.
- Release scenarios cover all V1 locked-scope paths and assert the V1 exclusions remain absent.

- [ ] Add deterministic invariant/property tests and run them red before implementation.
- [ ] Add the release GameTest matrix: three styles, missing material resume, chest topology change, chunk unload, tombstone, Hut loss, conflict, Stop, Safe Undo, and restart boundaries.
- [ ] Implement metrics and documentation from actual command names and observed behavior.
- [ ] Run the clean build, full GameTest matrix, S4/S5 harness, and exclusion scan; commit `release: verify smart survival architect v1`.

## Final verification gate

Run, in this order:

```powershell
./gradlew clean test build
./gradlew runServerGameTest
powershell -ExecutionPolicy Bypass -File tools/Invoke-S4PersistenceCheck.ps1
powershell -ExecutionPolicy Bypass -File tools/Invoke-S5RestartCheck.ps1
git diff --check
```

V1.0 is complete only when every command exits 0, the release matrix reports all cases passing, no unknown evidence is resumed, and the final working tree contains the documented release artifacts.
