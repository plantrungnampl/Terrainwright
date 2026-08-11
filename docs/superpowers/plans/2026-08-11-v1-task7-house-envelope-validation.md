# V1 Task 7 House Envelope Validation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans`, `superpowers:test-driven-development`, and `superpowers:verification-before-completion` task-by-task.

**Goal:** Produce deterministic roof, opening, and light-terrain plans and reject every structurally unsafe or unresolved Blueprint before it can become a preview.

**Architecture:** Keep all generation data platform-neutral in `architect-core`. `TerrainPlan` mirrors the immutable R2 terrain summary and suppresses drops/XP for every edit. Roof and opening planners consume solved layout/style data and emit deterministic semantic plans. `BlueprintValidator` is the single hard-validation boundary; it checks topology, terrain budgets, roof coverage, dependency integrity, block-state support, and style capability requirements using an injected registry snapshot.

**Scope boundary:** This slice does not generate complete house walls, choose the best candidate, read Minecraft registries, mutate terrain, or render a preview. Those integrations begin in Tasks 8, 11, and 12.

**Tech Stack:** Java 25 immutable records/enums, JUnit 5.14.3.

---

### Task 1: Lock the terrain contract with red tests

**Files:**
- Create: `architect-core/src/test/java/dev/ssa/architect/terrain/TerrainAdaptationPlannerTest.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/terrain/TerrainPlan.java`

- [ ] Prove a seeded hillside plan is deterministic and reports exact remove/fill totals and vertical extrema.
- [ ] Require `SUPPRESS` drop/XP policy for every edit and reject unsafe natural-block removals.
- [ ] Prove liquid intersections and over-budget slopes cannot be accepted as valid light terrain.
- [ ] Add `TerrainPlan` to the immutable `Blueprint` contract.

### Task 2: Implement bounded light-terrain planning

**Files:**
- Create: `architect-core/src/main/java/dev/ssa/architect/terrain/TerrainAdaptationPlanner.java`

- [ ] Evaluate only target heights bounded by the sampled footprint extrema.
- [ ] Choose the lowest-cost valid FLAT/CUT/FILL/MIXED plan deterministically.
- [ ] Return no candidate when remove/fill volume or vertical cut/fill exceeds `TerrainBudget.light()`.
- [ ] Preserve water/lava evidence for hard validation instead of silently modifying liquids.

### Task 3: Lock roof and room-aware opening behavior with red tests

**Files:**
- Create: `architect-core/src/test/java/dev/ssa/architect/roof/RoofPlannerTest.java`
- Create: `architect-core/src/test/java/dev/ssa/architect/opening/OpeningPlannerTest.java`

- [ ] Prove every upper-floor footprint cell is covered by a deterministic roof block for each built-in roof family.
- [ ] Prove Japanese wide-overhang geometry extends beyond the footprint while Modern flat/shed and Medieval gable geometry remain distinct.
- [ ] Place one entrance, one door per same-floor room transition, and room-aware windows within the R2 ranges.
- [ ] Preserve exterior orientation and avoid duplicate opening positions.

### Task 4: Implement semantic roof and opening planners

**Files:**
- Create: `architect-core/src/main/java/dev/ssa/architect/roof/RoofPlanner.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/opening/OpeningPlanner.java`

- [ ] Emit platform-neutral `BlueprintBlock` roof placements using resolved placement state.
- [ ] Keep roof decomposition bounded to the footprint plus the style overhang envelope.
- [ ] Derive doors/windows from `RoomGraph + FloorLayout`; never use periodic facade holes.

### Task 5: Lock hard validation failures with red tests

**Files:**
- Create: `architect-core/src/test/java/dev/ssa/architect/validation/BlueprintValidatorPropertyTest.java`

- [ ] Accept seeded valid hillside fixtures without exceeding 150 removals, 180 fills, cut 3, or fill 4.
- [ ] Reject unreachable rooms, invalid cross-floor stairs, missing foundation/roof coverage, unresolved dependencies, and dependency cycles.
- [ ] Reject unsupported placement states, missing style-required material capabilities, liquid modification, unsafe terrain removal, and terrain budget violations.

### Task 6: Implement the hard Blueprint validator

**Files:**
- Create: `architect-core/src/main/java/dev/ssa/architect/validation/BlueprintValidator.java`

- [ ] Return structured stable issue codes without throwing for invalid candidate content.
- [ ] Validate all placement states against the injected immutable registry and selected style requirements.
- [ ] Keep validation deterministic and free of world/runtime access.

### Task 7: Verify, review, and commit

- [ ] Run focused red/green tests, then `:architect-core:test`.
- [ ] Run `clean test build --no-daemon`, S1 layout verification, and `git diff --check`.
- [ ] Request independent review and commit only the Task 7 house-envelope slice.
