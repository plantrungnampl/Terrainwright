# S2 Builder Navigation Spike Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` and `superpowers:test-driven-development` task-by-task.

**Goal:** Prove on a real Fabric GameTest server that a disposable Builder can move chest-to-site, extract one exact test item, resolve a legal interaction position, place through a server-only executor, recover within fixed bounds, and stop safely on impossible or unloaded targets.

**Architecture:** All S2 code is explicitly disposable and remains under `dev.ssa.fabric.spike.navigation`; it must not leak into the pure cores or become the V1 Builder implementation. A `PathfinderMob` owns a small measured state machine. Minecraft GameTests create deterministic obstruction fixtures and assert observable world/entity outcomes. The spike records path-attempt timings and never calls teleport APIs after spawn.

**Tech Stack:** Java 25, Fabric Loom 1.17.19, Fabric API GameTest 4.0.21 (from API 0.154.2+26.2), Minecraft 26.2 official mappings, JUnit Jupiter 5.14.3.

## Constraints

- Use Fabric's official `configureTests` source set and server GameTest runner.
- The workspace owner confirmed the Minecraft EULA; set the GameTest option only for this local/automated test runner.
- Spawn setup may position the entity once. After `beginScenario`, movement must use `PathNavigation`; production spike code must not call `setPos`, `teleportTo`, or equivalent.
- Maximum normal path attempts: `3`; stuck window: `40` ticks; no-progress threshold: `0.05` blocks; scaffold ramp: at most `3` blocks for the spike fixture.
- A placement requires loaded chunks, a walkable feet/head position, distance at most `4.5` blocks, line of sight to the chest/target, one carried cobblestone, and a replaceable target.
- An impossible target must enter a stable `BLOCKED` state and perform no mutation after the terminal transition.
- S2 is evidence, not final Builder architecture. Do not add UI, persistence, WAL, final inventory logistics, models, textures, or player commands.

---

### Task 1: Enable isolated Fabric GameTests

**Files:**
- Modify: `platform-fabric/build.gradle.kts`
- Create: `platform-fabric/src/gametest/resources/fabric.mod.json`
- Create: `platform-fabric/src/gametest/java/dev/ssa/fabric/spike/navigation/BuilderNavigationGameTests.java`

- [ ] Add `fabricApi.configureTests` with `createSourceSet = true`, mod ID `smart_survival_architect_gametest`, server GameTests enabled, client GameTests disabled, and EULA enabled.
- [ ] Add GameTest metadata with a `fabric-gametest` entrypoint to `BuilderNavigationGameTests`.
- [ ] Point the first smoke test at a missing `SpikeEntityTypes.BUILDER` and run the server GameTest task; verify red because the entity registration does not exist.
- [ ] Record the exact generated GameTest task name from `:platform-fabric:tasks` and use it consistently.

### Task 2: Register the disposable Builder entity

**Files:**
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/spike/navigation/SpikeEntityTypes.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/spike/navigation/SpikeBuilderEntity.java`
- Modify: `platform-fabric/src/main/java/dev/ssa/fabric/SmartSurvivalArchitectMod.java`

- [ ] Register a `PathfinderMob` under `smart_survival_architect:spike_builder` with dimensions `0.6 x 1.8`, max health `20`, movement speed `0.30`, follow range `32`, and step height `1`.
- [ ] Register default attributes through `FabricDefaultAttributeRegistry` before GameTests spawn the entity.
- [ ] Keep random goals empty. Expose only spike state, bounded counters, max observed per-tick displacement, carried item count, scaffold count, and immutable path-duration samples.
- [ ] Run the smoke GameTest; verify it can spawn the custom entity and the server remains authoritative.

### Task 3: Implement chest-to-site interaction through TDD GameTests

**Files:**
- Modify: `platform-fabric/src/gametest/java/dev/ssa/fabric/spike/navigation/BuilderNavigationGameTests.java`
- Modify: `platform-fabric/src/main/java/dev/ssa/fabric/spike/navigation/SpikeBuilderEntity.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/spike/navigation/SpikePlacementExecutor.java`

- [ ] First add `flatChestToSitePlacesOneBlock`: flat floor, linked vanilla chest containing exactly one cobblestone, empty target, and Builder spawn. Verify red because no scenario API exists.
- [ ] Implement `beginScenario(chestPos, targetPos, allowScaffold)` and states `IDLE`, `NAVIGATE_CHEST`, `NAVIGATE_SITE`, `SUSPENDED_CHUNK_UNLOADED`, `BLOCKED`, `SUCCESS`.
- [ ] Resolve up to four cardinal standing positions with feet/head clearance, solid support, reach and line-of-sight checks. Use `PathNavigation.createPath`/`moveTo`; record attempt duration and retry only after the bounded stuck window.
- [ ] At the chest, remove exactly one cobblestone into a one-slot `SimpleContainer`. At the site, call `SpikePlacementExecutor`, which revalidates reach, line of sight, inventory and replaceability on the server thread before debit + placement.
- [ ] Verify the chest is empty, target is cobblestone, Builder inventory is empty, final state is `SUCCESS`, attempts are bounded and max per-tick displacement is at most `1.5` blocks.

### Task 4: Add the obstruction matrix one fixture at a time

**Files:**
- Modify: `platform-fabric/src/gametest/java/dev/ssa/fabric/spike/navigation/BuilderNavigationGameTests.java`
- Modify: `platform-fabric/src/main/java/dev/ssa/fabric/spike/navigation/SpikeBuilderEntity.java`

For each fixture, add the GameTest, run red for the missing behavior, implement the minimum recovery, then rerun green:

- [ ] `oneBlockStepSucceeds`: a one-block elevation change remains reachable.
- [ ] `doorwaySucceeds`: a two-high one-wide doorway is traversed.
- [ ] `fencedObstructionBlocksWithoutMutation`: enclosure prevents every legal path and reaches `BLOCKED` in at most three attempts.
- [ ] `upperFloorSucceeds`: vanilla stairs lead to a second-floor interaction position.
- [ ] `shortScaffoldRampSucceeds`: normal candidates fail; a three-block temporary ramp is created, used and counted without teleport.
- [ ] `impossibleTargetStaysBlocked`: bedrock enclosure reaches terminal `BLOCKED`; state, attempt count and target remain unchanged for another 40 ticks.
- [ ] `unloadedDestinationSuspendsWithoutPathSpam`: a destination in an unloaded chunk reaches `SUSPENDED_CHUNK_UNLOADED` with zero path attempts and zero mutations.

Every test uses `@GameTest(maxTicks = 400, padding = 20)` unless the unloaded-chunk fixture needs smaller padding to keep the far chunk unloaded.

### Task 5: Profile route attempts and publish repeatable evidence

**Files:**
- Create: `tools/Invoke-S2NavigationCheck.ps1`
- Create: `docs/spikes/S2/gametest.log`
- Create: `docs/spikes/S2/result.md`

- [ ] Add a GameTest that performs 100 short-route `createPath` calls after warmup and emits a stable `SSA_S2_PROFILE` line with count, p50, p95 and max microseconds.
- [ ] The harness runs the exact GameTest Gradle task, captures output, requires every named fixture and the profile marker, rejects GameTest failures/teleport markers, and writes a normalized log.
- [ ] Treat local S2 navigation cost as acceptable only when p95 is below `10,000 µs` and max is below `50,000 µs` on the declared Windows/JDK 25 host; otherwise the spike fails and the design is revised.
- [ ] `result.md` records fixture outcomes, path/retry/stuck/scaffold bounds, timing percentiles, command/exit code, hardware/JVM, and any design changes.
- [ ] Run `clean test build`, the S2 harness, pure-module import checks and `git diff --check`; commit evidence only after all checks exit `0`.
