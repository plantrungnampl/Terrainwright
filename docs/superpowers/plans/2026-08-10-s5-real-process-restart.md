# S5 Real-Process Restart Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans`, `superpowers:test-driven-development`, `superpowers:systematic-debugging`, and `superpowers:verification-before-completion` task-by-task.

**Goal:** Prove that a real Fabric dedicated-server JVM can be terminated at every S4 operation boundary, restarted against the same world, reconcile all durable project evidence before scheduling, and quarantine unknown evidence without new work.

**Architecture:** The S5-only Fabric bootstrap is inert unless `SSA_S5_MODE` is present. In crash mode it creates one versioned fixture repository inside the active world, executes the existing S4 coordinator, and calls `Runtime.halt(70)` at the selected boundary. In recovery mode it reopens the same world-level fixture and WAL, runs reconciliation, then opens a tick-level scheduling gate only for non-fatal outcomes. A PowerShell harness controls each JVM pair through environment variables and `runServer --universe`, captures both process exit codes/logs, and copies compact fixture/result manifests into `docs/spikes/S5`.

**Tech Stack:** Java 25, JUnit 5.14.3, Fabric lifecycle/tick events, Minecraft 26.2 dedicated server, Gradle Loom `runServer`, Java NIO forced files, PowerShell.

## Fixed pass contract

- Use one isolated harness-owned universe and the same generated world identity for every crash/restart pair.
- Persist and reopen: BuildJob ID/revision/state, Blueprint hash, ContainerBinding identity/revision, Builder identity/lifecycle/tombstone, active OperationIntent WAL, temporary scaffold provenance, journal count, exact ordered evidence and recovery diagnostics.
- Inject: before PREPARED append, after write-before-fsync ACK, after durable PREPARED, after every individual delta, after all deltas-before-journal, after journal-before-WAL commit, after WAL commit-before-clear, and after clear.
- A successful restart reconciles twice idempotently before exactly one scheduling action is permitted.
- Foreign evidence durably quarantines, preserves evidence bytes, and permits zero scheduling actions.
- The crash JVM must exit through the injected hard halt; the recovery JVM must exit cleanly after assertions.

---

### Task 1: Define the world-level fixture repository with red tests

**Files:**
- Create: `platform-fabric/src/test/java/dev/ssa/fabric/spike/restart/RestartFixtureRepositoryTest.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/spike/restart/RestartFixture.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/spike/restart/RestartFixtureRepository.java`

- [ ] Start with compilation-failing tests for complete metadata persistence and exact evidence reopen.
- [ ] Test apply/reopen, idempotent journal commit, recovery-complete scheduling gate and foreign evidence.
- [ ] Write the smallest versioned properties-backed repository, replacing through a forced temporary file.
- [ ] Keep every update synchronized and reread from disk so tests exercise process-visible persistence rather than retained objects.

### Task 2: Define crash-boundary and recovery-state behavior with red tests

**Files:**
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/spike/restart/S5CrashBoundary.java`
- Create: `platform-fabric/src/test/java/dev/ssa/fabric/spike/restart/S5RestartScenarioTest.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/spike/restart/S5RestartScenario.java`

- [ ] Map every S4 boundary, including each of three ordered deltas, to exactly one coordinator/append callback.
- [ ] Define expected first/second recovery outcomes and final evidence for each boundary.
- [ ] Define foreign evidence as `QUARANTINED`, evidence-byte stable and never schedulable.
- [ ] Keep process termination behind an injected port so unit tests never halt their JVM.

### Task 3: Wire an opt-in dedicated-server restart driver

**Files:**
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/spike/restart/S5RestartServerDriver.java`
- Modify: `platform-fabric/src/main/java/dev/ssa/fabric/SmartSurvivalArchitectMod.java`

- [ ] Register lifecycle/tick callbacks only when `SSA_S5_MODE` is set.
- [ ] In crash mode, create the fixture inside `server.getWorldPath(LevelResource.ROOT)`, execute the operation and hard-halt at the configured callback with an emitted marker.
- [ ] In recovery mode, reopen the fixture and WAL, reconcile twice, and keep the scheduling gate closed until recovery succeeds.
- [ ] Permit exactly one scheduling marker for safe outcomes; quarantine records diagnostics and exits with schedule count zero.
- [ ] Write a forced result manifest containing fixture/world identity, outcomes, commit/apply/schedule counts, evidence hash and thread markers before clean shutdown.

### Task 4: Automate real JVM crash/restart pairs

**Files:**
- Create: `tools/Invoke-S5RestartCheck.ps1`
- Modify: `.gitignore`

- [ ] Build first, then launch `:platform-fabric:runServer` with a fixed harness-owned `--universe` and unique fixture ID per boundary.
- [ ] Require the crash launch to contain the exact boundary marker and the game JVM exit value `70`.
- [ ] Restart the same universe/fixture, require clean exit `0`, validate outcome/evidence/scheduling markers and copy compact saved fixtures.
- [ ] Include a foreign-evidence pair and compare evidence SHA-256 before/after recovery.
- [ ] Bound every process with a timeout and terminate only the exact Gradle process tree on timeout.

### Task 5: Publish and verify S5 evidence

**Files:**
- Create: `docs/spikes/S5/result.md`
- Create: `docs/spikes/S5/process.log`
- Create: `docs/spikes/S5/fixtures/*.properties`
- Create: `docs/spikes/evidence-index.md`

- [ ] Record commands, server/game exit codes, runtime/world identity, every boundary decision, scheduling-before-recovery assertion and unknown quarantine result.
- [ ] Run `clean test build`, the full S5 harness, S1 layout verification and `git diff --check`.
- [ ] Re-run the harness from a clean S5 universe to prove repeatability.
- [ ] Promote S1-S5 only after all evidence exists and the final review finds no blocker.
