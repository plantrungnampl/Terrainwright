# V1 Task 5 Room Graph and Floor Layout Solver Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans`, `superpowers:test-driven-development`, and `superpowers:verification-before-completion` task-by-task.

**Goal:** Generate the semantic room graph required by `HouseRequirements` and deterministically map it into a bounded V1 footprint without overlaps or broken room connectivity.

**Architecture:** `RoomGraphGenerator` creates built-in namespaced semantic room nodes and required transitions. `Footprint` owns the V1 rectangle/L/T cell mask. `FloorLayoutSolver` performs seed-stable bounded backtracking over compact rectangular room candidates. Same-floor required transitions share a cell edge; consecutive-floor stair transitions overlap horizontally to reserve a vertical landing. Failure is explicit as `Optional.empty()`.

**Scope boundary:** This slice places rooms and door/stair transitions only. It does not emit walls, roofs, openings, terrain tasks, material palettes, or Blueprint blocks.

**Tech Stack:** Java 25 immutable values, JUnit 5.14.3.

---

### Task 1: Lock semantic graph generation with red tests

**Files:**
- Create: `architect-core/src/test/java/dev/ssa/architect/room/RoomGraphGeneratorTest.java`

- [ ] Require entrance -> living and conditionally include kitchen, storage, balcony, upper halls, stairs and the requested bedroom count.
- [ ] Assign every node a stable ID, open namespaced type, exact floor, minimum area and exterior preference.
- [ ] Reject duplicate/missing node references, self edges, and disconnected graphs.

### Task 2: Implement immutable room graph and generator

**Files:**
- Create: `architect-core/src/main/java/dev/ssa/architect/room/RoomGraph.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/room/RoomGraphGenerator.java`

- [ ] Keep the graph immutable with canonical undirected edges.
- [ ] Generate a connected tree so every requested room has one required access path.
- [ ] Model each upper floor with a stair landing and upper hall; distribute bedrooms deterministically across available floors.

### Task 3: Lock deterministic bounded layout behavior with red tests

**Files:**
- Create: `architect-core/src/main/java/dev/ssa/architect/layout/Footprint.java`
- Create: `architect-core/src/test/java/dev/ssa/architect/layout/FootprintTest.java`
- Create: `architect-core/src/test/java/dev/ssa/architect/layout/FloorLayoutSolverTest.java`

- [ ] Prove rectangle/L/T masks are immutable, connected and bounded.
- [ ] Prove equal graph + footprint + seed yields equal layouts.
- [ ] Prove no same-floor overlap, all graph transitions are physically realizable, and all rooms are reachable from entrance.
- [ ] Run at least 500 seeds on a moderate two-floor fixture; every returned layout must satisfy the invariants and the fixture must remain feasible.
- [ ] Prove an undersized footprint returns `Optional.empty()` within the search budget.

### Task 4: Implement floor layout and bounded backtracking solver

**Files:**
- Create: `architect-core/src/main/java/dev/ssa/architect/layout/FloorLayout.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/layout/FloorLayoutSolver.java`

- [ ] Enumerate only compact rectangular candidates contained by the footprint and meeting node minimum area.
- [ ] Require exterior rooms to touch the footprint boundary.
- [ ] Reject same-floor overlap; require shared edges for same-floor graph edges and horizontal overlap for consecutive-floor stair edges.
- [ ] Use a stable seed-derived candidate ordering, maximum candidate count per decision and maximum total search attempts.
- [ ] Return immutable placed-room cells and realized connections.

### Task 5: Verify and commit

- [ ] Run focused red/green tests, then `:architect-core:test`.
- [ ] Run `clean test build --no-daemon`, S1 layout verification, and `git diff --check`.
- [ ] Request independent code review and commit only the Task 5 graph/layout slice.
