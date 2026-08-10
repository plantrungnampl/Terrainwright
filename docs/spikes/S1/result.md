# S1 Fabric Bootstrap Result

## Decision

**PASS** as of `2026-08-10T21:38:16.8463417+07:00`.

The Java 25/Fabric 26.2 bootstrap builds, the common entrypoint loads on both environments, the client entrypoint reaches `CLIENT_STARTED`, and the dedicated server reaches `Done` before stopping cleanly. S1 does not authorize gameplay implementation; S2-S5 still require their own plans and gates.

## Pinned toolchain

- Eclipse Temurin JDK `25.0.4+7-LTS`
- Gradle Wrapper `9.5.1`
- Fabric Loom `1.17.19`
- Minecraft `26.2`
- Fabric Loader `0.19.3`
- Fabric API `0.154.2+26.2`

## Build and tests

```powershell
.\gradlew.bat test build --no-daemon
```

Exit code: `0`. The build ran the `NamespacedId`, architecture-boundary, and stable block-ID tests and produced:

```text
platform-fabric/build/libs/smart-survival-architect-0.1.0-SNAPSHOT.jar
```

The inspected jar contains `fabric.mod.json`, the common entrypoint, the client entrypoint, and the registered marker-block classes.

## Client launch

```powershell
.\tools\Invoke-S1LaunchCheck.ps1 -Mode Client -TimeoutSeconds 180
```

Exit code: `0`. Evidence: `docs/spikes/S1/client-launch.log`.

Observed markers:

```text
SSA_S1_COMMON_READY block=smart_survival_architect:spike_marker
SSA_S1_CLIENT_READY
SSA_S1_CLIENT_STARTED
```

The harness terminated only its owned client process tree after `CLIENT_STARTED`. The dev identity `FabricMC` produced a Realms authorization warning after startup; it did not prevent the client-ready event and is unrelated to the mod bootstrap.

## Dedicated-server launch

```powershell
.\tools\Invoke-S1LaunchCheck.ps1 -Mode Server -TimeoutSeconds 180
```

Exit code: `0`. Evidence: `docs/spikes/S1/server-launch.log`.

Observed markers:

```text
SSA_S1_COMMON_READY block=smart_survival_architect:spike_marker
Done (1.951s)! For help, type "help"
Stopping server
Saving worlds
```

The workspace owner explicitly confirmed the Minecraft EULA for this local development launch. The resulting `platform-fabric/run/eula.txt` and all generated world/server files remain under an ignored `run/` directory. No client entrypoint or client-class linkage appeared in the server log.

## Scope retained

S1 registers only `smart_survival_architect:spike_marker`. It adds no block item, assets, UI, generation, persistence, networking, Builder behavior, or construction gameplay.
