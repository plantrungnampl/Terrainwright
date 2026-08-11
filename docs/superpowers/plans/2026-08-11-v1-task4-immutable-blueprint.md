# V1 Task 4 Immutable Blueprint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans`, `superpowers:test-driven-development`, and `superpowers:verification-before-completion` task-by-task.

**Goal:** Define the smallest complete immutable Blueprint contract that construction-core can consume without Minecraft or Fabric types.

**Architecture:** A Blueprint owns generated identity, seed, style, relative local bounds/footprint, rooms, placements, the canonical phase sequence, validation result, and format version. `BlueprintBlock` keeps relative coordinates, a coarse semantic block role, a canonical material role, platform-neutral `BlockStateSpec`, phase, and relative-position dependencies. Later tasks may add terrain, score, and navigation outputs; Task 4 does not invent their contracts early.

**Assumptions locked for this slice:** Generated Blueprint IDs use UUIDs. Room types remain open namespaced IDs. `BlockRole` is the coarse semantic vocabulary `TERRAIN`, `FOUNDATION`, `STRUCTURAL`, `ENVELOPE`, `OPENING`, `INTERIOR`, `DECORATION`; required/optional completion policy belongs on tasks in construction-core. Blueprint format version starts at `1`.

**Tech Stack:** Java 25 records/enums, JUnit 5.14.3.

---

### Task 1: Lock immutable placement and phase contracts with red tests

**Files:**
- Create: `architect-core/src/test/java/dev/ssa/architect/blueprint/BlueprintImmutabilityTest.java`
- Create: `architect-core/src/test/java/dev/ssa/architect/blueprint/BuildPhaseTest.java`

- [ ] Prove source lists/sets/maps and nested room/dependency collections cannot mutate a created Blueprint.
- [ ] Prove block coordinates are relative and bounded by local bounds.
- [ ] Prove the exact canonical phase order from R2.
- [ ] Prove duplicate block positions, malformed bounds, blank room IDs, and invalid format versions are rejected.

### Task 2: Implement the immutable Blueprint model

**Files:**
- Create: `architect-core/src/main/java/dev/ssa/architect/blueprint/Blueprint.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/blueprint/BlueprintBlock.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/blueprint/BuildPhase.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/blueprint/BlockRole.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/blueprint/Room.java`

- [ ] Use only core Java and existing platform-neutral architect types.
- [ ] Keep all collections defensively copied and exposed as immutable values.
- [ ] Keep phase ordering canonical and reject a Blueprint with a reordered/partial phase sequence.
- [ ] Enforce unique in-bounds footprint/block positions without implementing full architectural validation.

### Task 3: Implement validation results

**Files:**
- Create: `architect-core/src/main/java/dev/ssa/architect/validation/BlueprintValidation.java`
- Create: `architect-core/src/test/java/dev/ssa/architect/validation/BlueprintValidationTest.java`

- [ ] Store immutable structured ERROR/WARNING issues.
- [ ] Derive `isValid()` from absence of ERROR issues; do not accept a caller-provided validity flag.
- [ ] Reject blank issue codes/messages.

### Task 4: Verify and commit

- [ ] Run focused red/green tests, then `:architect-core:test`.
- [ ] Run `clean test build --no-daemon`, S1 layout verification, and `git diff --check`.
- [ ] Commit only the Task 4 Blueprint model and tests.
