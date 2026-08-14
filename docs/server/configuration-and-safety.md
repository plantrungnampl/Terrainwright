# Server Configuration and Safety

## Runtime requirements

V1.0 targets Minecraft 26.2, Fabric Loader 0.19.3+, Fabric API 0.154.2+26.2, and Java 25. The mod and its matching Fabric API must be installed on the dedicated server and clients.

V1 deliberately has no general configuration file. Locked safety bounds are code-owned so a server setting cannot silently weaken the release contract:

- site survey range: 64 blocks;
- Builder Chest distance from Hut: squared distance at most 256 (16 blocks);
- preview workers: 2, with a bounded queue of 16 and a 5-tick per-player request cooldown;
- temporary scaffold: at most 24 placements and 12 blocks of height;
- no forced chunk loading and no teleport fallback.

## Permissions

Preview survey/confirmation, chest binding and transfer, permanent placement/removal, and Safe Undo all use the shared `FabricPermissionAdapter`. The shipped adapter requires the owner to be online in the target level, the chunk to be loaded, and Minecraft's `mayInteract` check to allow the position. It does not claim compatibility with every third-party land-claim mod; a modpack integration must preserve this single permission boundary and fail closed when authority is unknown.

Only the durable Hut owner can view or control its job. Commands include the expected job revision; stale and unauthorized commands are rejected by the server.

## Persistence and failure behavior

Durable job state is stored in Minecraft world data. Exact material transfers and world mutations use operation-intent WAL files under:

```text
<world>/data/smart_survival_architect/operations/
```

On restart, active evidence is reconciled before scheduling. Exact all-before evidence aborts, exact all-after evidence finalizes, supported partial evidence is resolved, and unknown/external evidence quarantines the job. Do not delete WAL or world-data files to force a resume.

Builder unload is suspension, not death. Relevant chunk unload pauses work and no chunk ticket is created. Confirmed death records a tombstone; replacement is never automatic. Breaking a Hut with an active job retains its last durable association and moves the job to recovery/orphan handling. Breaking an unused Hut removes the empty durable ownership record.

## Stop and Safe Undo

Stop prevents new permanent scheduling and preserves already committed construction. Safe Undo reads the reverse journal and mutates only cells whose current state still equals the job-written state. Conflicts and protected positions remain untouched. Site preparation and Undo suppress normal block drops and XP; Safe Undo is not a material-refund system.

## Debug metrics

Optional metrics are disabled by default and record no per-tick INFO logs. Enable them at JVM startup with:

```text
-Dsmart_survival_architect.debugMetrics=true
```

The in-memory snapshot contains generation request/success/failure counts and timing, path attempts/failures and timing, scaffold blocks, material trips, conflicts, candidate rejection reasons, and reconciliation outcomes. V1 exposes this as the internal `DebugMetrics.snapshot()` API only; it does not ship a command, file exporter, or network endpoint.

## Release checks

Run from the repository root with Java 25 available:

```powershell
.\gradlew.bat clean test build --no-daemon
.\gradlew.bat :platform-fabric:runGameTest --no-daemon
powershell -ExecutionPolicy Bypass -File tools/Invoke-S4PersistenceCheck.ps1
powershell -ExecutionPolicy Bypass -File tools/Invoke-S5RestartCheck.ps1
git diff --check
```
