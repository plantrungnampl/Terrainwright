# V1 Task 9 Construction Planning Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans`, `superpowers:test-driven-development`, and `superpowers:verification-before-completion` task-by-task.

**Goal:** Convert an immutable validated Blueprint into a deterministic acyclic BuildTask graph with an incrementally maintained eligible frontier, five-by-five horizontal work zones, and exact bounded material batches.

**Architecture:** Keep planning pure in `construction-core`. `ConstructionPlanner` translates terrain changes and Blueprint placements into immutable tasks while preserving explicit position dependencies. `TaskGraph` validates referential integrity and acyclicity once, builds reverse edges once, and exposes a mutable per-job `Frontier` that updates only direct dependents when a task completes. `WorkBatchPlanner` chooses one deterministic zone at a time and counts exact semantic material/state requirements without touching inventories or platform APIs.

**Scope boundary:** This slice does not own BuildJob persistence, phase state transitions, pathfinding, chest capacity/stack rules, world mutation, WAL, retries, or task leases. Those remain Tasks 10, 13, and 14. Material capacity here is an exact item-count bound supplied by the caller.

**Tech Stack:** Java 25 immutable records, existing `architect-core` domain contracts, JUnit 5.14.3.

---

### Task 1: Lock task and DAG contracts with red tests

**Files:**
- Create: `construction-core/src/test/java/dev/ssa/construction/plan/ConstructionPlannerTest.java`
- Create: `construction-core/src/main/java/dev/ssa/construction/task/TaskOperation.java`
- Create: `construction-core/src/main/java/dev/ssa/construction/task/BuildTask.java`
- Create: `construction-core/src/main/java/dev/ssa/construction/plan/TaskGraph.java`

- [ ] Prove Blueprint block dependencies become task-ID dependencies and a roof remains ineligible until its support chain completes.
- [ ] Reject missing dependency IDs, duplicate task IDs, and cycles.
- [ ] Prove `TaskGraph.Frontier.complete` unlocks only direct dependents and never rescans all pending tasks.
- [ ] Preserve the canonical operations `REMOVE`, `PLACE`, `REPLACE`, `TEMP_PLACE`, and `TEMP_REMOVE`.

### Task 2: Lock spatial zones and exact batches with red tests

**Files:**
- Create: `construction-core/src/main/java/dev/ssa/construction/schedule/WorkZone.java`
- Create: `construction-core/src/main/java/dev/ssa/construction/material/WorkBatchPlanner.java`

- [ ] Assign every task to deterministic five-by-five horizontal zones, including negative local coordinates via floor division.
- [ ] Select a single zone deterministically from eligible tasks.
- [ ] Respect task-count and material-item capacity bounds without splitting an atomic group.
- [ ] Return exact counts keyed by semantic material role plus platform-neutral block state; removals consume no material.

### Task 3: Implement deterministic Blueprint translation

**Files:**
- Create: `construction-core/src/main/java/dev/ssa/construction/plan/ConstructionPlanner.java`

- [ ] Translate terrain changes to `REMOVE`, `PLACE`, or `REPLACE` tasks in `SITE_PREPARATION`.
- [ ] Translate Blueprint blocks to `PLACE` tasks with their exact material role/state, phase, position dependencies, and work zone.
- [ ] Make a Blueprint placement depend on any terrain-change task at the same position.
- [ ] Produce stable IDs and ordering independent of source collection order.

### Task 4: Verify, review, and commit

- [ ] Run focused red/green tests, then `:construction-core:test`.
- [ ] Run `clean test build --no-daemon`, S1 layout verification, and `git diff --check`.
- [ ] Request independent review and commit only the Task 9 construction-planning slice.
