# V1 Task 3 Architect Input Domain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans`, `superpowers:test-driven-development`, and `superpowers:verification-before-completion` task-by-task.

**Goal:** Define the complete immutable, platform-neutral request and bounded terrain input consumed by the future Architect generator.

**Architecture:** `HouseRequirements` centralizes the R2 V1 bounds and fixes terrain adaptation to `LIGHT`. `StyleId` remains an open namespaced value. `TerrainSnapshot` stores surface arrays as immutable flattened row-major lists, sparse obstruction positions, immutable column masks, slope metrics, nearby feature vectors, and a required revision fingerprint. Material roles are the exact persisted R2 vocabulary with no aliases.

**Tech Stack:** Java 25 records/enums, JUnit 5.14.3.

---

### Task 1: Lock request bounds and vocabulary with red tests

**Files:**
- Create: `architect-core/src/test/java/dev/ssa/architect/model/HouseRequirementsTest.java`
- Create: `architect-core/src/test/java/dev/ssa/architect/material/MaterialRoleTest.java`

- [ ] Test width/depth `9..31`, floors `1..3`, bedrooms `0..6`, non-null style/entrance, and fixed `LIGHT` adaptation.
- [ ] Test namespaced open `StyleId` and rejection of bare IDs.
- [ ] Test the exact 18 canonical material roles and rejection of `STONE_BASE`.
- [ ] Test `TerrainBudget.light()` equals `(150, 180, 3, 4, false, false)`.

### Task 2: Implement requirements, style, entrance, budget, and material roles

**Files:**
- Create: `architect-core/src/main/java/dev/ssa/architect/model/HouseRequirements.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/model/StyleId.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/model/EntrancePreference.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/model/TerrainAdaptation.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/material/MaterialRole.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/terrain/TerrainBudget.java`

- [ ] Keep all bounds in `HouseRequirements`; do not duplicate UI assumptions.
- [ ] Parse material roles with exact `Enum.valueOf` semantics and a domain error for unknown aliases.
- [ ] Add no style registry or palette behavior yet.

### Task 3: Lock detached terrain immutability with red tests

**Files:**
- Create: `architect-core/src/test/java/dev/ssa/architect/model/TerrainSnapshotTest.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/model/TerrainSnapshot.java`

- [ ] Require positive width/depth, ordered Y bounds, exactly `width * depth` surface heights/materials, in-range heights, valid mask indexes, finite nonnegative slopes and a nonblank revision fingerprint.
- [ ] Defensively copy all lists, sets, nested nearby-feature lists and maps.
- [ ] Expose only bounded `surfaceYAt`, `surfaceMaterialAt`, water/lava/tree queries and immutable sparse metadata.
- [ ] Keep coordinates/platform conversion out of this type.

### Task 4: Verify and commit

- [ ] Run `:architect-core:test`, then `clean test build --no-daemon`.
- [ ] Run layout and `git diff --check`.
- [ ] Commit only the Task 3 domain and tests.
