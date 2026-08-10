# Smart Survival Architect V1 Gate-First Delivery Design

**Date:** 2026-08-10  
**Status:** Approved delivery design  
**Canonical product source:** `../../../../Smart-Survival-Architect-Master-Architecture-R2/`

## Goal

Deliver the complete V1 described by Master Architecture R2 without treating unproven Fabric, navigation, rendering, durability, or restart assumptions as implementation facts.

The work proceeds through evidence gates. The first subproject proves mandatory spikes S1-S5. Gameplay implementation begins only after all five spikes pass or the canonical design is revised to match observed evidence.

## Workspace and repository

- Implementation repository: `D:\Terrainwright\smart-survival-architect`.
- The R2 design package remains unchanged in its sibling directory.
- The implementation repository uses Git with `main` as its initial branch.
- Production code uses the fixed package prefix `dev.ssa` and mod ID `smart_survival_architect`.
- Toolchain coordinates from R2 are provisional until S1 resolves them against current official Fabric sources and proves clean client and dedicated-server launches.

## Delivery approach

Use one gate-first Gradle monorepo. Do not create a disposable monolith and plan to modularize it later.

The full V1 program is divided into six sequential subprojects:

1. **Foundation and mandatory spikes:** repository boundary, Fabric bootstrap, navigation feasibility, ghost-rendering feasibility, durable OperationIntent prototype, and real-process restart harness.
2. **Architect core:** typed requirements, immutable terrain snapshots, procedural candidates, validation, scoring, three geometry-distinct styles, and deterministic Blueprint output.
3. **Authoritative survey and preview:** Architect Table, server Survey sessions, bounded terrain capture, preview sessions, client-only ghost rendering, and confirmation authority.
4. **Transactional construction:** BuildTask DAG, work zones, batching, BuildJob state, exact inventory/world OperationIntent execution, conflict policy, journal, and direct test executor.
5. **Builder runtime:** Hut and chest binding, one Builder, physical material fetching, navigation, bounded stuck recovery, temporary scaffolding, progress replication, and actionable failure states.
6. **Recovery and release:** restart/chunk/death/orphan recovery, Stop, Safe Undo, protection hooks, palette overrides, performance/property suites, three-style release scenarios, and V1 release evidence.

Each subproject receives its own repository-aware spec and TDD implementation plan. Passing one subproject is required before planning APIs that depend on it.

## Production module boundaries

V1 has exactly four production modules:

```text
architect-core
construction-core -> architect-core
minecraft-common -> architect-core + construction-core
platform-fabric -> architect-core + construction-core + minecraft-common
```

- `architect-core` owns platform-neutral IDs, positions, block-state specifications, requirements, terrain, Blueprint, style, validation, and scoring semantics.
- `construction-core` owns planning and pure decisions for task graphs, scheduling, state transitions, operations, reconciliation, journals, conflicts, and undo.
- `minecraft-common` owns narrow world, inventory, navigation, permission, persistence-codec, and network-DTO contracts without importing Minecraft or Fabric runtime classes.
- `platform-fabric` is the only module allowed to import Minecraft or Fabric runtime classes. It owns registrations, entities, adapters, server integration, persistence, packets, screens, and rendering.
- Spike fixtures and harnesses live in test sources, testmod sources, and `tools/`; they do not become production APIs merely because a spike succeeds.

## First subproject: foundation and S1-S5

### Included

- Initialize the Gradle multi-project boundary and wrapper using versions proven by S1.
- Produce a minimal loadable Fabric jar with client and server entrypoints separated correctly.
- Add a disposable Builder/navigation test fixture for chest-to-site movement and bounded failure.
- Add immutable 1,000- and 5,000-block preview fixtures for supported rendering abstractions.
- Define and prove the smallest exact `OperationIntent` model needed for `MATERIAL_TRANSFER` and `WORLD_MUTATION`.
- Add a Windows PowerShell harness that starts, controls, terminates, and restarts a dedicated server against the same fixture world.
- Store commands, logs, measurements, screenshots or structured results, failures, and resulting design changes under `docs/spikes/S1` through `docs/spikes/S5`.

### Excluded

- Architect Table gameplay and production UI.
- Procedural house generation beyond immutable spike fixtures.
- Production Builder Hut, chest-linking UI, task scheduler, or house construction loop.
- Japanese and Modern production style content.
- Stop, Safe Undo, multi-Builder execution, AI, basements, infrastructure, settlements, and remote content.

## Spike flows

### S1: bootstrap and packaging

```text
official version evidence
 -> resolved JDK/Gradle/Loom/Loader/Fabric API coordinates
 -> four-module clean build
 -> client launch
 -> dedicated-server launch
 -> loadable jar and class-boundary evidence
```

The host currently has no Java or Gradle command on `PATH`. S1 installs a supported JDK distribution only after recording the source and exact version. The repository uses the Gradle wrapper rather than requiring a global Gradle installation.

### S2: navigation and interaction

```text
fixture Builder
 -> linked test chest
 -> exact test-item extraction
 -> legal standing-position resolution
 -> Minecraft navigation
 -> one authoritative test mutation
 -> stable success or bounded blocked result
```

Fixtures cover flat ground, a step, doorway, fence obstruction, upper-floor target, scaffold-required target, impossible target, and chunk unload. No code path teleports the Builder.

### S3: ghost preview

```text
immutable preview revision
 -> palette/index buffers
 -> required/optional/terrain/conflict layers
 -> rotate or replace revision
 -> dispose old buffers
```

Evidence names the actual backend, records frame time and allocation behavior, and demonstrates that no raw OpenGL API is used.

### S4: durable operation intent

```text
validate exact evidence
 -> append and fsync PREPARED off the server tick thread
 -> resume on the server thread
 -> apply exact inventory/world deltas
 -> verify exact after-state
 -> persist journal/progress and COMMITTED evidence
 -> checkpoint and clear
```

Recovery classifies observed evidence as all-before, all-after, a specifically supported exact prefix, or unknown. Unknown, foreign, unresolved, or changed-binding evidence quarantines the operation and permits no new mutation.

### S5: real-process restart

The harness launches a dedicated-server process, establishes a fixture identity, injects termination at every S4 boundary, restarts the same world, waits for reconciliation, and asserts that scheduling remains disabled until recovery reaches a terminal classification.

## Canonical contract alignments

The implementation resolves three prose/schema drifts explicitly instead of choosing silently:

- Persisted build phase uses `UPPER_FLOOR`, matching the R2 JSON schemas and examples. Human-facing prose may use “upper floors.”
- `ScoreBreakdown` includes `scenicOrientation` because the canonical scoring formula assigns it weight `0.06`; implementation codecs and generated schemas must include it.
- `OperationIntent` includes optional `taskId` and `atomicGroupId`, and an item snapshot contains a restorable canonical component payload plus an integrity hash. A component hash alone is insufficient to restore an exact pre-operation stack.

These alignments are recorded in the implementation repository. The sibling R2 package is not edited.

## Failure policy

- A missing or incompatible toolchain fails S1; versions are not silently floated.
- A client-only class reachable from server initialization fails S1.
- Unbounded navigation retries, teleport fallback, or mutation after an impossible target fails S2.
- Unsupported renderer APIs, leaked revision buffers, or unacceptable measured behavior fails S3 and changes the renderer design.
- Any crash window that duplicates, loses, guesses, or mutates from unknown evidence fails S4/S5.
- Known failures produce evidence and a design change; they are not converted into waivers.
- No spike pass claim is made from mocks, same-process object reconstruction, or an old command result.

## Testing and evidence

- New behavior follows red-green-refactor. A production method is added only after a focused test fails for the expected missing behavior.
- Pure unit tests cover module boundaries, immutable data, exact evidence classification, and state decisions.
- Fabric GameTests cover world, inventory, entity, navigation, and rendering-adjacent integration where supported.
- Real process tests cover fsync ordering and dedicated-server restart behavior on the same world.
- S1 evidence includes resolved versions, `clean test build`, client launch, server launch, and jar load.
- S2 evidence includes outcome traces, retry bounds, standing/reach checks, and tick/path measurements.
- S3 evidence includes screenshots, backend identity, frame/allocation measurements, and revision disposal.
- S4 evidence includes the full crash matrix, p50/p95 durable acknowledgement latency, and quarantine cases.
- S5 evidence includes one repeatable command, process exit codes, fixture identity, restart assertions, and proof that reconciliation precedes scheduling.

## First-subproject acceptance

The foundation subproject is accepted only when:

1. All four modules compile with the intended acyclic dependency graph.
2. The minimal mod jar loads in both client and dedicated-server environments.
3. S2 navigation fixtures produce documented stable outcomes without teleport or retry loops.
4. S3 preview fixtures render, replace, rotate, and dispose within recorded bounds on the supported backend.
5. Every supported S4 crash point preserves exact material/world accounting, while unknown evidence quarantines.
6. S5 repeats those recovery assertions across actual process termination and restart on the same world.
7. `docs/spikes/evidence-index.md` links the complete fresh evidence only after criteria 1-6 are satisfied.

Passing the first subproject authorizes planning for the Architect-core subproject. It does not by itself claim that V1 gameplay is complete.
