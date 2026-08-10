# S4 OperationIntent Persistence and Recovery Spike Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans`, `superpowers:test-driven-development`, and `superpowers:systematic-debugging` task-by-task.

**Goal:** Prove that exact component-aware inventory changes and exact world-state changes can be prepared durably, applied, classified, completed or quarantined after every required crash window without duplication/loss and without executing fsync on the server tick thread.

**Architecture:** S4 separates the platform-neutral recovery truth table from the disposable Fabric persistence adapter. `construction-core` owns immutable operation snapshots, ordered deltas and a pure exact-evidence classifier. `platform-fabric` owns a versioned checksum-framed append-only WAL, Minecraft `ItemStack`/`BlockState` snapshot adapters, a single-thread persistence executor and a server-continuation coordinator. The test fixture permits at most one active intent, applies deltas in recorded order and recovers only all-before, all-after or a strict known prefix; every other observation quarantines without mutation.

**Tech Stack:** Java 25, JUnit 5.14.3, Java NIO `FileChannel.force(true)`, Minecraft 26.2 codecs/registries, Fabric API 0.154.2+26.2 GameTests.

## Fixed recovery policy

- No durable intent and all-before evidence: no operation exists; leave evidence unchanged.
- Durable `PREPARED` plus all-before evidence: append durable `ABORTED`, then clear/checkpoint.
- A strict prefix of ordered after-states followed only by exact before-states: complete the remaining suffix, verify all-after, append durable `COMMITTED`, then clear/checkpoint.
- All-after evidence: finalize missing journal/task commit without replaying any delta, append durable `COMMITTED`, then clear/checkpoint.
- Durable `COMMITTED`: never replay deltas; verify all-after and clear/checkpoint. Any mismatch quarantines.
- Any foreign stack component, count, inventory identity/binding, block ID/property or non-prefix mixture: append durable `QUARANTINED`; do not automatically mutate evidence.

## Constraints

- `PREPARED` must be acknowledged only after the WAL frame and metadata are forced to stable storage.
- No inventory or world side effect may occur before the durable acknowledgement completes.
- WAL I/O runs only on the named persistence executor. Mutation/recovery continuations run only on the supplied server executor.
- Stack snapshots preserve item ID, count and the complete encoded data-component payload; block snapshots preserve block ID and every state property.
- Recovery compares every referenced slot/cell individually. Aggregate material totals are never recovery evidence.
- S4 is an in-process crash-window persistence prototype. Actual OS process termination/restart belongs to S5.
- Use one active intent and bounded record/frame sizes. Reject malformed, oversized or checksum-invalid frames.

---

### Task 1: Define the recovery truth table with red pure tests

**Files:**
- Create: `construction-core/src/test/java/dev/ssa/construction/spike/persistence/OperationRecoveryClassifierTest.java`
- Create: `construction-core/src/test/java/dev/ssa/construction/spike/persistence/OperationIntentValidationTest.java`

- [ ] Cover `MATERIAL_TRANSFER`, placement-with-consumption and atomic multi-block mutation fixtures.
- [ ] Assert all-before, all-after and every strict known prefix classification.
- [ ] Assert foreign components, slot counts, binding revisions, block properties and non-prefix mixtures quarantine.
- [ ] Assert intent kind/delta-shape invariants, a maximum of 256 inventory and 64 world deltas, and immutable defensive copies.
- [ ] Run `:construction-core:test`; record the expected compilation failure before implementation.

### Task 2: Implement immutable intents and exact-evidence classification

**Files:**
- Create: `construction-core/src/main/java/dev/ssa/construction/spike/persistence/OperationKind.java`
- Create: `construction-core/src/main/java/dev/ssa/construction/spike/persistence/OperationStatus.java`
- Create: `construction-core/src/main/java/dev/ssa/construction/spike/persistence/StackSnapshot.java`
- Create: `construction-core/src/main/java/dev/ssa/construction/spike/persistence/BlockStateSnapshot.java`
- Create: `construction-core/src/main/java/dev/ssa/construction/spike/persistence/InventoryDelta.java`
- Create: `construction-core/src/main/java/dev/ssa/construction/spike/persistence/WorldDelta.java`
- Create: `construction-core/src/main/java/dev/ssa/construction/spike/persistence/OperationIntent.java`
- Create: `construction-core/src/main/java/dev/ssa/construction/spike/persistence/ObservedEvidence.java`
- Create: `construction-core/src/main/java/dev/ssa/construction/spike/persistence/RecoveryDecision.java`
- Create: `construction-core/src/main/java/dev/ssa/construction/spike/persistence/OperationRecoveryClassifier.java`

- [ ] Model a single ordered evidence sequence so a known prefix has one unambiguous meaning across inventory and world deltas.
- [ ] Store component and block-state payloads as immutable canonical bytes; equality is byte-for-byte plus identity/count/position metadata.
- [ ] Implement only four classifier outcomes: `ABORT_PREPARED`, `COMPLETE_SUFFIX`, `FINALIZE_COMMIT`, `QUARANTINE_UNKNOWN_EVIDENCE`.
- [ ] Run all construction-core tests and commit the pure recovery primitive independently.

### Task 3: Build and test the fsync-backed framed WAL

**Files:**
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/spike/persistence/OperationIntentCodec.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/spike/persistence/FileOperationIntentStore.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/spike/persistence/PersistenceExecutor.java`
- Create: `platform-fabric/src/test/java/dev/ssa/fabric/spike/persistence/FileOperationIntentStoreTest.java`

- [ ] Encode version, record kind/status, bounded payload length and checksum in every append-only frame.
- [ ] Test durable `PREPARED`, `COMMITTED`, `ABORTED`, `QUARANTINED` and clear/checkpoint records by closing and reopening the store.
- [ ] Test that a truncated/unforced final frame is ignored while a checksum-invalid durable frame fails closed.
- [ ] Test the one-active-intent invariant and reject oversized/corrupt payloads.
- [ ] Capture thread identity and 200 durable acknowledgement samples; emit p50/p95 latency without setting an unproven hardware threshold.

### Task 4: Prove exact Minecraft stack and block snapshots

**Files:**
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/spike/persistence/MinecraftSnapshotAdapter.java`
- Create: `platform-fabric/src/gametest/java/dev/ssa/fabric/spike/persistence/MinecraftSnapshotGameTests.java`

- [ ] Encode/decode through the selected Minecraft 26.2 registry-aware codecs rather than hand-picking components/properties.
- [ ] Prove same item/count with different custom-name or other data components compares unequal.
- [ ] Prove exact round-trip for a component-bearing stack and empty stack.
- [ ] Prove same block ID with different state properties compares unequal and round-trips exactly.
- [ ] Emit `SSA_S4_CODEC` markers describing the tested component/block fixtures.

### Task 5: Execute every crash window and thread handoff

**Files:**
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/spike/persistence/OperationCoordinator.java`
- Create: `platform-fabric/src/test/java/dev/ssa/fabric/spike/persistence/OperationCrashMatrixTest.java`

- [ ] Inject before append, after append-before-force acknowledgement, after durable PREPARED, after each individual delta, after all deltas-before-commit, after commit-before-clear and after clear/checkpoint.
- [ ] Run the full matrix for material transfer, placement-with-consumption and atomic multi-block mutation.
- [ ] Close/reopen the WAL and fixture evidence at every injected boundary, then reconcile twice to prove idempotence.
- [ ] Assert exact expected slots/cells, item conservation for transfers/placement, no replay after commit, and zero mutation for unknown evidence.
- [ ] Drive preparation from a named server event loop without blocking it; assert `force(true)` occurs on `ssa-persistence-*` and mutation continuation on `ssa-server-*`.

### Task 6: Publish repeatable S4 evidence

**Files:**
- Create: `tools/Invoke-S4PersistenceCheck.ps1`
- Create: `docs/spikes/S4/test.log`
- Create: `docs/spikes/S4/result.md`
- Modify: `.gitignore`

- [ ] Harness runs focused pure tests, WAL/crash tests, Minecraft codec GameTests and the clean build.
- [ ] Require markers for every operation/crash point, deterministic decision, idempotent second recovery, exact codec fixtures, persistence/server thread identities and durable-ack p50/p95.
- [ ] Record commands, runtime/filesystem, sample count, measurements, fixture hashes and design findings.
- [ ] Run `clean test build`, S4 harness, module-boundary/layout checks and `git diff --check`; commit S4 only after every gate exits `0`.
