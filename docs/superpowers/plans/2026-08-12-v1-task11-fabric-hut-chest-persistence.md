# V1 Task 11 Fabric Hut, Chest Binding, and Persistence Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans`, `superpowers:test-driven-development`, `superpowers:systematic-debugging`, and `superpowers:verification-before-completion` task-by-task.

**Goal:** Bootstrap the production Architect Table and Builder Hut blocks, resolve server-authoritative vanilla chest bindings, and persist authoritative BuildJob, Hut, chest, and Builder-lifecycle state outside the Hut block entity.

**Architecture:** `BuilderHutBlockEntity` stores only stable identifiers and display-local references. Each `ServerLevel` owns one versioned `SavedData` repository, so dimension plus job UUID is the durable key and a missing Hut cannot erase the job. `BuilderChestLinkService` resolves a client-proposed position from live server block state, canonicalizes single/double vanilla chests, applies owner permission and squared-distance checks, and commits a new binding only after all validation succeeds. A topology comparison gates future material transfer without performing transfer in this task.

**Assumptions and boundaries:** The canonical distance is measured from the Hut block position to the canonical primary chest block and is accepted when `distance² <= 256`. Only `minecraft:chest` is accepted; barrels, trapped chests, and modded containers remain outside V1. Double-chest primary ordering is deterministic by `(x,y,z)`. An identical relink is idempotent; a valid topology change increments the prior binding revision and changes its inventory identity. Entity/chunk unload never writes a tombstone. This slice does not add UI, networking packets, Builder AI, material movement, operation WAL duplication, forced chunk loading, or nearby-container discovery.

**Tech Stack:** Java 25, Fabric Loader/API for Minecraft 26.2, `SavedDataType` codecs, `ValueInput`/`ValueOutput`, JUnit 5.14.3, Fabric GameTest.

---

### Task 1: Register production blocks and the Hut block entity

**Files:**
- Modify: `platform-fabric/src/main/java/dev/ssa/fabric/SmartSurvivalArchitectMod.java`
- Modify: `platform-fabric/src/main/java/dev/ssa/fabric/block/ModBlockIds.java`
- Modify: `platform-fabric/src/main/java/dev/ssa/fabric/block/ModBlocks.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/block/ModBlockEntityTypes.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/block/ArchitectTableBlock.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/block/BuilderHutBlock.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/block/BuilderHutBlockEntity.java`
- Test: `platform-fabric/src/test/java/dev/ssa/fabric/block/ModBlockIdsTest.java`
- Test: `platform-fabric/src/test/java/dev/ssa/fabric/block/BuilderHutBlockEntityTest.java`

- [ ] Add stable production registry IDs and register both blocks plus the Hut block entity during common initialization.
- [ ] Persist only Hut ID, owner ID, active job ID, Builder ID, and binding revision in the Hut block entity.
- [ ] Prove reference data survives a ValueInput/ValueOutput round trip without embedding a BuildJob.

### Task 2: Lock canonical binding and Builder lifecycle contracts

**Files:**
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/link/ContainerBinding.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/lifecycle/BuilderLifecycleTombstone.java`
- Test: `platform-fabric/src/test/java/dev/ssa/fabric/link/ContainerBindingTest.java`
- Test: `platform-fabric/src/test/java/dev/ssa/fabric/lifecycle/BuilderLifecycleTombstoneTest.java`

- [ ] Validate dimension, canonical primary/optional partner positions, stable inventory identity, monotonic revision, and format version.
- [ ] Treat matching topology as transfer-eligible and any merge/split/partner change as relink-required.
- [ ] Preserve Builder identity across unload observations; permit replacement only after an explicit authoritative death/removal tombstone.

### Task 3: Persist authoritative world-level state

**Files:**
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/persistence/ServerBuildJobRepository.java`
- Test: `platform-fabric/src/test/java/dev/ssa/fabric/persistence/ServerBuildJobRepositoryTest.java`

- [ ] Back the repository with a versioned `SavedDataType` codec obtained through `ServerLevel#getDataStorage()`.
- [ ] Persist exact BuildJob data alongside Hut-to-job references, current ContainerBinding, and Builder lifecycle/tombstone records.
- [ ] Enforce job ID/revision consistency, return immutable snapshots, mark SavedData dirty only after successful mutations, and reject stale writes.
- [ ] Prove codec round trips preserve state and that deleting Hut-local references does not remove the authoritative job.

### Task 4: Resolve and verify live vanilla chest topology

**Files:**
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/link/BuilderChestLinkService.java`
- Create: `platform-fabric/src/gametest/java/dev/ssa/fabric/link/HutChestLinkGameTest.java`

- [ ] Resolve canonical single/double chest topology entirely from the server level; never accept a proposed partner from the caller.
- [ ] Accept canonical-primary distance² 256 and reject 257, barrel, wrong owner, and denied permission without changing the prior binding.
- [ ] Resolve either half of one double chest to the same primary, partner, inventory identity, and revision.
- [ ] Detect split/merge after linking as relink-required, then produce a new revision only after explicit successful relink.

### Task 5: Verify, review, and commit

- [ ] Capture focused red tests before implementation, then run platform unit tests and all Fabric GameTests.
- [ ] Re-index the code graph and request independent P1/P2 review.
- [ ] Run `clean test build --no-daemon`, S1 layout verification, and `git diff --check`.
- [ ] Commit only the Task 11 slice with `feat: link huts to durable construction state`.
