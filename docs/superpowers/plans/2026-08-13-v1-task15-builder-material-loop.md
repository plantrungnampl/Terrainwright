# V1 Task 15 Builder Material Loop Implementation Plan

## Goal

Add one production Builder entity and a thin server controller that walks to the explicitly linked vanilla chest, durably transfers an exact work batch into its carried inventory, walks to legal interaction positions, and commits one-wall placement tasks through the promoted `OperationIntent` executor.

## Assumptions and boundaries

- Task 15 reuses the S2 pathfinding probe and the Task 14 WAL/recovery protocol; it does not add scaffolding or the bounded stuck-recovery ladder, which belong to Task 16.
- The entity owns identity, carried inventory persistence, movement, and lifecycle hooks only. Task selection, material checks, state transitions, and mutations remain in the controller and services.
- The first production slice supports `PLACE` tasks. Unsupported remove/replace work fails closed instead of inferring a missing expected-before state from the world.
- A controller must reconcile any active intent before selecting new work. Permissions are checked before a new `PREPARED` record; a previously authorized durable intent is completed from exact evidence after restart.
- The linked `ContainerBinding` is authoritative. The controller never scans arbitrary nearby storage and never force-loads a chunk.

## Implementation plan

1. **Register and persist the production Builder.** Add `BuilderEntity` and `ModEntityTypes`; persist every carried slot with component-aware `ItemStack` codecs, keep the entity from distance despawn, and drop carried items through normal death handling.
2. **Extract production navigation seams.** Add `InteractionPositionResolver` and `FabricNavigationAdapter` using loaded-chunk checks, walkable feet/headroom, reach, line of sight, and real path navigation. Do not add teleport or scaffolding fallback.
3. **Implement the explicit state machine.** Add `BuilderStateMachine` with validated transitions for recovery, material wait/fetch, chest/site navigation, execution, selection, blocked/suspended, and idle completion.
4. **Implement the one-wall controller.** Consume an active `BuildJob`, `TaskGraph`, and exact `ContainerBinding`; plan one bounded work batch, preflight the complete material bundle, auto-resume from `WAIT_MATERIAL`, transfer chest slots to carried slots through `MaterialTransferService`, and place each task through a combined inventory/world `OperationIntent` no faster than once every eight ticks.
5. **Persist task commits.** Adapt the mutation commit log to append deterministic `JournalEntry` evidence and completed task IDs to `ServerBuildJobRepository` idempotently before the WAL is marked committed.
6. **Exercise the production loop.** Add server GameTests for a three-block wall and missing-material auto-resume. Assert physical movement, durable prepare acknowledgements for transfer and placement, exact chest/carried counts, exact world results, three journal entries, no active WAL, and a completed job.
7. **Verify current gates.** Run focused unit tests, server GameTests, clean build, S1 layout, S4 persistence, S5 real-process restart matrix, and `git diff --check`. Do not call the restart gate complete unless all eleven S5 cases exit successfully.
