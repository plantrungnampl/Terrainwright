# V1 Task 14 Durable OperationIntent Executor Implementation Plan

## Goal

Promote the S4/S5 exact-evidence protocol into the production construction path so no inventory transfer or permanent world mutation can happen before a forced `PREPARED` acknowledgement. Preserve the proven WAL framing and recovery truth table; do not create a second persistence protocol.

## Assumptions and boundaries

- The existing S4 framed WAL, exact slot/block snapshots, ordered-prefix classifier, and S5 restart harness are the implementation baseline because they already passed the full crash matrix.
- Production operation types move out of `spike` namespaces. Spike and restart fixtures are updated to exercise the promoted types rather than retaining parallel copies.
- Task 14 exposes direct execution services but does not add a Builder entity or scheduling loop; those belong to Task 15.
- Permission is checked before `PREPARED`. Unknown, reordered, component-mismatched, or externally changed evidence remains fail-closed and quarantined.

## Implementation plan

1. **Promote the pure operation contract.** Move exact snapshots, deltas, status, intent, recovery decision, and classifier into `dev.ssa.construction.operation`; move their tests with them and keep all validation bounds unchanged.
2. **Promote the durable runtime.** Move the S4 codec, framed forced WAL, persistence executor, mutation coordinator, evidence port, and boundary hooks into production Fabric persistence/construction packages. Rename the public entry points to `OperationIntentStore` and `FabricMutationExecutor` while preserving the WAL format.
3. **Add real Fabric adapters.** Implement `MaterialTransferService`, Minecraft evidence observation/application, and `FabricPermissionAdapter`. Transfer planning snapshots every changed source/destination slot; world mutation validates loaded chunks, exact before states, and permission before preparing.
4. **Exercise production paths.** Update the S4 crash matrix and S5 restart harness to import the promoted code. Add focused production tests for exact transfer, permission rejection before prepare, exact placement/removal, and no active intent after commit.
5. **Verify the full gate.** Run focused construction/Fabric tests, `Invoke-S4PersistenceCheck.ps1`, `Invoke-S5RestartCheck.ps1`, clean unit/build/server GameTests, layout verification, and `git diff --check`. Treat S4/S5 as complete only if their full matrices exit successfully.
