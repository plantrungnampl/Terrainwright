# S4 OperationIntent Persistence and Recovery Result

**Status:** PASS
**Date:** 2026-08-10

## Scope

This spike proves a single-active-intent write-ahead protocol for exact
`MATERIAL_TRANSFER` and `WORLD_MUTATION` evidence. It covers a two-slot material
transfer, a placement that consumes one component-bearing stack, and an atomic
three-block mutation.

S4 injects failures in-process while closing and reopening both the WAL and the
fixture evidence files at every boundary. Actual dedicated-server process
termination and restart remains the S5 gate.

## Reproduction

Run the complete gate:

```powershell
.\tools\Invoke-S4PersistenceCheck.ps1
```

The harness runs:

```powershell
.\gradlew.bat clean test build --no-daemon
.\gradlew.bat :construction-core:test :platform-fabric:test --rerun-tasks --info --no-daemon
.\gradlew.bat :platform-fabric:runGameTest --no-daemon
.\tools\verify-s1-layout.ps1
git -c core.safecrlf=false -c core.autocrlf=false diff --check
```

Recorded runtime:

- Windows 11 Pro 10.0.26200
- Intel Core i7-12700F, 12 cores / 20 logical processors
- 32 GiB RAM
- Eclipse Temurin 25.0.4+7
- Minecraft 26.2 / Fabric Loader 0.19.3 / Fabric API 0.154.2+26.2
- Java NIO `FileChannel.force(true)` against the workspace drive

## Crash matrix

Every supported recovery is run twice. The second run must be a no-op, proving
idempotence.

| Crash boundary | Transfer | Placement + consumption | Atomic 3-block |
| --- | --- | --- | --- |
| before PREPARED append | no active intent | no active intent | no active intent |
| after append, before fsync ACK | abort all-before | abort all-before | abort all-before |
| after durable PREPARED | abort all-before | abort all-before | abort all-before |
| after delta 1 | complete suffix | complete suffix | complete suffix |
| after delta 2 | all-after/finalize | all-after/finalize | complete suffix |
| after delta 3 | n/a | n/a | all-after/finalize |
| after all deltas, before commit | finalize without replay | finalize without replay | finalize without replay |
| after journal commit, before WAL commit | verify journal, mark WAL committed, clear | verify journal, mark WAL committed, clear | verify journal, mark WAL committed, clear |
| after WAL commit, before clear | verify and clear | verify and clear | verify and clear |
| after clear/checkpoint | no active intent | no active intent | no active intent |

The transfer preserves the exact total across the two recorded slots. Placement
consumes exactly one recorded item and writes exactly one recorded block state.
Every multi-block fixture reaches the complete intended state or remains wholly
before mutation; no supported case duplicates or loses evidence. The fixture
persists a journal commit-call count and asserts exactly one commit across both
the initial execution and recovery pass at every supported applied boundary.

A foreign component payload with the same item/count is explicitly classified
`QUARANTINED`. Pure classifier tests also cover changed inventory binding
revision, foreign block properties and non-prefix mixtures. Quarantine performs
zero automatic deltas, retains the intent and remains quarantined on a second
recovery pass. Durable `ABORTED` and `QUARANTINED` states are sticky terminal
states across reopen and cannot resume work. `ABORTED`/`COMMITTED` may only
escalate to `QUARANTINED` when later evidence conflicts; `QUARANTINED` cannot
transition again.

## Exact Minecraft evidence

The Minecraft adapter uses registry-aware 26.2 codecs and binary NBT:

- `DataComponentPatch.CODEC` captures the complete item component patch.
- `BlockState.CODEC` captures the exact block and all state properties.
- a diamond pickaxe with custom name and damage round-trips exactly;
- a different custom name is unequal even with identical item ID/count;
- empty stack round-trips exactly;
- east/top oak stairs round-trip exactly, while west/top is unequal.

The server run completed all 15 required GameTests.

## WAL and thread evidence

The append-only WAL uses versioned, length-bounded records with separate
CRC32C checks over header metadata and typed payload, plus a redundant footer
containing the repeated payload length and footer magic.
`PREPARED`, terminal status and clear/checkpoint records are each acknowledged
only after `FileChannel.force(true)` returns. A truncated final frame is ignored
as unacknowledged and the next append truncates back to the last valid byte
before writing. Tests truncate inside a header, payload and footer and then prove
the next append/reopen replaces only the incomplete tail. Corrupt header fields,
payloads and complete footers fail closed in first, middle and final frames.
Encoded-size preflight rejects records above 4 MiB before allocating the output
buffer or creating the WAL. `CLEAR` is accepted and replayed only after a durable
terminal status; a second active operation ID is rejected.

The recorded 200-sample durable PREPARED acknowledgement profile is:

| Samples | p50 | p95 |
| ---: | ---: | ---: |
| 200 | 1,976 us | 3,009 us |

Latency starts before submission to the persistence executor and ends after
`force(true)`, so it includes executor queueing, WAL scan, encoding, write and
durable flush rather than only the filesystem call.

The thread-handoff test deliberately blocks the first fsync. While blocked, a
heartbeat still executes on `ssa-server-test`; no mutation executes and the
operation future remains incomplete. After durable acknowledgement, all fixture
mutation/commit callbacks execute on `ssa-server-test`, while WAL write/force
executes on `ssa-persistence-1`.

## Fixed recovery rule

- all-before durable PREPARED evidence aborts and clears;
- a strict ordered after-prefix followed only by exact before evidence completes
  the remaining suffix;
- all-after evidence finalizes commit without replay;
- durable COMMITTED evidence is verified and cleared without replay;
- every unknown or foreign observation quarantines without mutation.

No decision uses aggregate item totals. Inventory identity, binding revision,
slot, item ID, count, complete components, world identity, position, block ID
and properties are compared at their recorded cells.

## Design findings

- Bytes written before `force(true)` can still be visible after an ordinary
  reopen. This is safe because no side effect is allowed before the durable ACK;
  recovery sees exact all-before evidence and aborts.
- A merely ignored truncated tail would hide later appended frames. Appends now
  scan the valid prefix, truncate the unacknowledged tail and write at that exact
  boundary before forcing the file.
- One ordered delta sequence makes a supported prefix unambiguous across mixed
  inventory and world evidence.
- Exact Minecraft codecs avoid maintaining a fragile hand-written list of item
  components or block properties.
- WAL corruption and foreign game evidence are different failure modes: corrupt
  complete frames fail closed at storage load, while valid intents with foreign
  world/inventory evidence enter recoverable quarantine.
- A zero-count empty stack is canonical only when its component patch is empty;
  both construction and codec tests reject non-empty component bytes to avoid
  lossy decode/re-encode behavior.

## Evidence files

- `build.log` (`clean test build`)
- `test.log` (focused crash, durability and thread evidence)
- `gametest.log` (15 required server GameTests)
- `verification.log` (module layout and `git diff --check`)
- fixture source `OperationCrashMatrixTest.java`, SHA-256
  `43E3708C54C94BD085A0F1108B221BEE1882570DDC3B5804E49994261464B9A2`
- Minecraft codec fixture source `MinecraftSnapshotGameTests.java`, SHA-256
  `FAE62861749482920F7E878B844A94C19C9C221F16AE296117ABF9CD6AB49314`
