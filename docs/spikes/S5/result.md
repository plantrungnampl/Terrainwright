# S5 Real-Process Restart Harness Result

**Status:** PASS
**Date:** 2026-08-11

## Scope

S5 starts a real Minecraft 26.2 Fabric dedicated-server JVM, creates a durable
fixture inside its active world, terminates that JVM with `Runtime.halt(70)` at a
selected S4 recovery boundary, and starts a new dedicated-server JVM against the
same `--universe` and world. The restarted process opens the persisted fixture
and OperationIntent WAL, reconciles twice, then either opens a one-shot
scheduling gate or leaves foreign evidence quarantined.

Two complete runs from clean universes used one stable world identity within
each run and different identities between runs:

```text
run 1: fcd156d8-5cf8-4acf-a9b9-b0e9d1c1a231
run 2: 3b1c3664-f0ac-4bc1-9bd4-03f1a6aafbb1
```

## Reproduction

Run the complete gate:

```powershell
.\tools\Invoke-S5RestartCheck.ps1
```

The harness:

1. safely recreates only `D:\Terrainwright\run\s5-restart`;
2. runs `clean test build --no-daemon`;
3. runs 11 crash JVMs and 11 recovery JVMs through Loom `runServer` with the
   same `--universe` and `--world world`;
4. requires each crash game JVM to report exit `70` and each recovery Gradle
   process to exit `0`;
5. validates and copies the forced world-level fixture/result properties;
6. verifies the module layout and `git diff --check`.

Recorded runtime:

- Windows 11 Pro 10.0.26200
- Intel Core i7-12700F, 12 cores / 20 logical processors
- 32 GiB RAM
- Eclipse Temurin 25.0.4+7
- Gradle 9.5.1 / Loom 1.17.19
- Minecraft 26.2 / Fabric Loader 0.19.3 / Fabric API 0.154.2+26.2
- run 1 clean build: 39.0 seconds, exit `0`
- run 1 process matrix: 22 JVM launches from 19:48:25 to 19:54:17 +07:00
- run 1 full harness: 392.1 seconds, exit `0`
- run 2 clean build: 49.2 seconds, exit `0`
- run 2 process matrix: 22 JVM launches from 20:02:01 to 20:08:29 +07:00
- run 2 full harness: 437.5 seconds, exit `0`

## Real-process crash matrix

Every row was executed in both clean-universe runs. Each execution records one
hard-stop server JVM followed by one clean recovery server JVM. `Blocked ticks`
shows the observed range of server scheduling ticks while recovery was closed.

| Crash boundary | First recovery | Second recovery | Apply | Journal commit | Schedule | Blocked ticks |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| before PREPARED append | `NO_ACTIVE_INTENT` | `NO_ACTIVE_INTENT` | 0 | 0 | 1 | 1 |
| after append, before fsync ACK | `ABORTED` | `NO_ACTIVE_INTENT` | 0 | 0 | 1 | 2-4 |
| after durable PREPARED | `ABORTED` | `NO_ACTIVE_INTENT` | 0 | 0 | 1 | 1-2 |
| after delta 1 | `COMMITTED` | `NO_ACTIVE_INTENT` | 3 | 1 | 1 | 3-4 |
| after delta 2 | `COMMITTED` | `NO_ACTIVE_INTENT` | 3 | 1 | 1 | 2-4 |
| after delta 3 | `COMMITTED` | `NO_ACTIVE_INTENT` | 3 | 1 | 1 | 2-3 |
| after all deltas, before journal commit | `COMMITTED` | `NO_ACTIVE_INTENT` | 3 | 1 | 1 | 3-4 |
| after journal commit, before WAL commit | `COMMITTED` | `NO_ACTIVE_INTENT` | 3 | 1 | 1 | 2 |
| after WAL commit, before clear | `COMMITTED` | `NO_ACTIVE_INTENT` | 3 | 1 | 1 | 1 |
| after clear/checkpoint | `NO_ACTIVE_INTENT` | `NO_ACTIVE_INTENT` | 3 | 1 | 1 | 1 |
| foreign exact evidence | `QUARANTINED` | `QUARANTINED` | 0 | 0 | 0 | 1-2 |

The append-before-fsync case is allowed to recover as either no active intent or
exact all-before `ABORTED`; this filesystem retained the complete written frame,
so the observed result was `ABORTED`. No side effect occurred before the missing
durable acknowledgement.

## Persisted fixture coverage

Each case saves a versioned fixture under the active world containing:

- BuildJob ID, revision and `BUILDING` state;
- canonical Blueprint SHA-256 reference;
- ContainerBinding world/position identity and revision;
- Builder UUID, `ACTIVE` lifecycle and explicit tombstone flag;
- exact active OperationIntent WAL;
- one component-aware carried stack and two exact ordered world deltas;
- temporary scaffold key and written block state;
- permanent journal commit count;
- recovery diagnostic, apply count and scheduling count.

The repository replaces its properties file through a forced temporary file.
OperationIntent continues to use the S4 forced framed WAL. Every new process
rereads both from disk; it does not reconstruct state from retained Java objects.

## Recovery-before-scheduling proof

The Fabric `START_SERVER_TICK` gate remains closed while the coordinator loads
and reconciles the active intent. All 11 recovery launches observed at least one
blocked tick. Safe outcomes mark recovery complete and permit exactly one
scheduled action on a later tick. The second recovery is already idempotent
before that gate opens.

Foreign component evidence is handled differently: recovery appends durable
`QUARANTINED`, the second recovery remains quarantined, the exact evidence
SHA-256 is unchanged before/after recovery, and schedule count remains zero.

## Process and exit evidence

Each of `process-run-1.log` and `process.log` contains exactly:

- 22 `CASE=` process records;
- 11 `SSA_S5_CRASH` markers;
- 11 game-process `finished with non-zero exit value 70` reports;
- 11 `SSA_S5_RECOVERY` markers;
- 11 clean recovery `BUILD SUCCESSFUL` reports.

No run contains `SSA_S5_FAILURE` or `TIMED_OUT=True`. The 22 saved fixture and
result manifests from each run reproduce the same semantic outcomes and counts.

The recovery result includes fixture ID, boundary, stable world UUID, first and
second outcome, active terminal status, apply/commit/schedule counts, blocked
ticks, exact evidence hashes, metadata verification and the normalized world
path. A controller failure hard-stops with exit `72`, which did not occur.

## Design findings

- `--universe` isolates persistent world data even though Loom recreates its
  generated GameTest run directory before each server launch.
- A self-injected `Runtime.halt(70)` makes the crash boundary part of the game
  JVM rather than an in-process exception; Gradle reliably exposes child exit
  `70` while returning a nonzero task exit.
- Scheduling must be a separate server-tick transition after the recovery future
  completes. Completing recovery and scheduling in one callback would not prove
  the ordering gate.
- The S5 fixture proves process/world-level persistence and reconciliation for
  all required durable facts. It remains spike infrastructure; production V1
  repositories and real gameplay blocks/entities are implemented by the later
  vertical slices.

## Evidence files

- `build-run-1.log` and `build.log`: two fresh clean builds with exit `0`;
- `process-run-1.log` and `process.log`: two independent 22-process matrices;
- `fixtures-run-1/*`: 22 fixture/result manifests from clean universe 1;
- `fixtures/*`: 22 fixture/result manifests from clean universe 2;
- `S5RestartServerDriver.java`, SHA-256
  `F36A62041E12C955236997931B52FFD8023FC439551ED8641F582466A3AE6840`;
- `RestartFixtureRepository.java`, SHA-256
  `46FA6AD29D309A4A0F7644D45D069229F894BD6C6F1C2D741F61260DCCDFD9AD`;
- `S5RestartScenario.java`, SHA-256
  `9707DD899D236EA8738E4BCF8A188101A27B0FC91FB750575B9B97D219994589`;
- `Invoke-S5RestartCheck.ps1`, SHA-256
  `E2EEFBE82AD35FA26D61981C1B05E4A328A2F63A9696370E9309B5C143634D59`.
