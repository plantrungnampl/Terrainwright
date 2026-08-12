# V1 Task 10 BuildJob, Reconciliation, and Safe Undo Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans`, `superpowers:test-driven-development`, `superpowers:systematic-debugging`, and `superpowers:verification-before-completion` task-by-task.

**Goal:** Add the pure `construction-core` state and decisions required to persist construction progress, classify world conflicts, reconcile restart evidence without double consumption, and plan conservative reverse-journal Undo.

**Architecture:** Keep every type platform-neutral. `BuildJob` is an immutable, revisioned progress aggregate over the canonical V1 job states; platform repositories and controllers added by later tasks will persist and drive it. `ConflictClassifier`, `Reconciler`, and `UndoPlanner` accept explicit observations from adapters and return decisions only. `JournalEntry` records exact before/after block states for committed permanent mutations. The existing S4/S5 `OperationIntent` spike remains the WAL authority and is not duplicated in this slice.

**Assumptions and boundaries:** Task 10 does not perform I/O, mutate a world or inventory, serialize Minecraft block entities, or implement the final Builder controller. A safe-terrain-equivalence flag is supplied only after the platform applies its bounded natural-block allowlist. Until a safe block-entity restoration contract exists, block-entity cells are conflicts rather than arbitrary payload restoration targets. Undo never refunds consumed placement materials.

**Tech Stack:** Java 25 immutable records/classes, existing architect/task primitives and S4 recovery contracts, JUnit 5.14.3.

---

### Task 1: Lock canonical job states and revisioned transitions

**Files:**
- Create: `construction-core/src/main/java/dev/ssa/construction/job/BuildJob.java`
- Create: `construction-core/src/main/java/dev/ssa/construction/job/BuildJobState.java`
- Test: `construction-core/src/test/java/dev/ssa/construction/reconcile/ReconcilerTest.java`

- [ ] Mirror the canonical persisted V1 job-state names.
- [ ] Reject illegal state transitions and invalid identity/hash/version/revision data.
- [ ] Keep completed task IDs, diagnostics, and journal entries immutable and reject partial/duplicate progress evidence.
- [ ] Make already-completed task recording idempotent so normal execution cannot consume it twice.

### Task 2: Lock conflict and journal contracts

**Files:**
- Create: `construction-core/src/main/java/dev/ssa/construction/conflict/ConflictClassifier.java`
- Create: `construction-core/src/main/java/dev/ssa/construction/journal/JournalEntry.java`
- Test: `construction-core/src/test/java/dev/ssa/construction/undo/UndoPlannerTest.java`

- [ ] Classify exact expected state as `UNCHANGED`.
- [ ] Allow `SAFE_CHANGED` only for explicitly approved equivalent natural terrain with permission and no block entity.
- [ ] Classify protection rejection, block entities, foreign/valuable blocks, and other unexpected states as exact conflicts.
- [ ] Validate unique monotonic journal identity/sequence, task/operation identity, position, exact before/after state, and committed revision.

### Task 3: Implement safe Undo and restart reconciliation decisions

**Files:**
- Create: `construction-core/src/main/java/dev/ssa/construction/undo/UndoPlanner.java`
- Create: `construction-core/src/main/java/dev/ssa/construction/reconcile/Reconciler.java`
- Test: `construction-core/src/test/java/dev/ssa/construction/undo/UndoPlannerTest.java`
- Test: `construction-core/src/test/java/dev/ssa/construction/reconcile/ReconcilerTest.java`

- [ ] Order Undo strictly by descending journal sequence.
- [ ] Restore the prior state only when current state still equals the journaled written state and permission remains granted; otherwise preserve current state with a conflict decision.
- [ ] Mark an incomplete task complete without consuming again only when a matching journal entry and exact after-state both exist.
- [ ] Keep clean incomplete tasks pending when no committed journal exists, preserve durably completed tasks after external edits, and quarantine mismatched/corrupt evidence.

### Task 4: Verify, review, and commit

- [ ] Capture red failures before implementation, then run the focused tests and full `:construction-core:test`.
- [ ] Re-index the code graph and request independent P1/P2 review.
- [ ] Run `clean test build --no-daemon`, S1 layout verification, and `git diff --check`.
- [ ] Commit only the Task 10 pure-core slice with `feat: persist construction state and safe undo decisions`.
