# V1 Task 2 Platform-Neutral Primitives Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans`, `superpowers:test-driven-development`, and `superpowers:verification-before-completion` task-by-task.

**Goal:** Establish the immutable position/block-state primitives and the first consumer-backed Minecraft-facing port without allowing platform or adapter dependencies into either pure core.

**Architecture:** `architect-core` owns value semantics only: `NamespacedId`, `GridPos`, and canonical immutable `BlockStateSpec`. `minecraft-common` may depend on those values and initially owns the exact `PermissionPort` contract required by the canonical plan. `WorldPort`, `InventoryPort`, and `NavigationPort` are intentionally deferred to Tasks 12, 14, and 15 because no accepted consumer currently defines truthful signatures; this follows the R2 instruction not to create generic god interfaces.

**Tech Stack:** Java 25 records, JUnit 5.14.3, existing four-module Gradle boundary.

---

### Task 1: Lock primitive value semantics with red tests

**Files:**
- Create: `architect-core/src/test/java/dev/ssa/architect/model/GridPosTest.java`
- Create: `architect-core/src/test/java/dev/ssa/architect/model/BlockStateSpecTest.java`

- [ ] Assert `GridPos` is a coordinate value with record equality.
- [ ] Assert block properties are copied, lexicographically canonical and unmodifiable.
- [ ] Reject null IDs/maps, invalid property names, blank/unsafe values and duplicate canonical keys.
- [ ] Run `:architect-core:test` and record the expected compilation failure.

### Task 2: Implement the minimum immutable primitives

**Files:**
- Create: `architect-core/src/main/java/dev/ssa/architect/model/GridPos.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/model/BlockStateSpec.java`

- [ ] Keep `GridPos` as the exact three-component record specified by R2.
- [ ] Copy properties through a sorted map and expose an unmodifiable view.
- [ ] Validate only canonical Minecraft-style property tokens; do not add rotation, serialization or platform conversion helpers.
- [ ] Run all architect-core tests.

### Task 3: Add the first consumer-backed port and dependency tests

**Files:**
- Create: `minecraft-common/src/main/java/dev/ssa/common/permission/PermissionPort.java`
- Create: `minecraft-common/src/test/java/dev/ssa/common/ArchitectureBoundaryTest.java`

- [ ] Implement only `boolean canModify(UUID owner, GridPos pos)` from the canonical interface.
- [ ] Prove architect/construction sources do not depend on `dev.ssa.common`, Minecraft or Fabric.
- [ ] Prove minecraft-common sources do not import Minecraft or Fabric runtime classes.
- [ ] Run `:architect-core:test :minecraft-common:test`.

### Task 4: Verify and commit the vertical gate

- [ ] Run `clean test build --no-daemon`.
- [ ] Run `tools/verify-s1-layout.ps1` and `git diff --check`.
- [ ] Record the deliberate port deferrals in the commit handoff so later tasks define them from actual consumers.
