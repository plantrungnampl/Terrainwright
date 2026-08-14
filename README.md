# Terrainwright

Terrainwright is a Minecraft Java 26.2 Fabric mod for designing a terrain-aware house, approving a client ghost preview, and having one server-owned Builder NPC construct it from materials in an explicitly linked vanilla chest.

V1.0 contains the locked house workflow only: Medieval, Japanese, and Modern styles; bounded light-terrain adaptation; one Builder per Hut; real material transfer; restart reconciliation; Stop; and Safe Undo. It does not include LLM generation, basements, arbitrary structure types, self-mining/crafting, forced chunk loading, or multi-Builder scheduling.

## Requirements

- Minecraft Java Edition 26.2
- Fabric Loader 0.19.3 or newer
- Fabric API for Minecraft 26.2
- Java 25

## Build

On Windows PowerShell:

```powershell
.\gradlew.bat clean test build --no-daemon
```

The Fabric mod JAR is produced under `platform-fabric/build/libs/`. Install it with the matching Fabric Loader and Fabric API on both the server and every connecting client.

## Play

Craft and place an Architect Table and a Builder Hut, open the Hut to link a nearby vanilla chest, then use the Table to select a site and generate a server-authoritative preview. Select the owned Hut and confirm the unchanged preview. Stock the linked chest with the missing materials shown by the Hut UI.

See [Getting Started](docs/player/getting-started.md) for the exact recipes and UI sequence.

## Repository layout

- `architect-core`: deterministic terrain analysis, house generation, validation, and styles.
- `construction-core`: job state, task planning, operation intent, scaffolding, and Safe Undo rules.
- `minecraft-common`: narrow runtime-neutral Minecraft adapter contracts.
- `platform-fabric`: Minecraft/Fabric integration, UI, networking, persistence, NPC execution, resources, and GameTests.

## Verification

```powershell
.\gradlew.bat clean test build --no-daemon
.\gradlew.bat :platform-fabric:runGameTest --no-daemon
powershell -ExecutionPolicy Bypass -File tools/Invoke-S4PersistenceCheck.ps1
powershell -ExecutionPolicy Bypass -File tools/Invoke-S5RestartCheck.ps1
git diff --check
```

Server operators should read [Configuration and Safety](docs/server/configuration-and-safety.md). Style-pack authors should read [Style Palette Format](docs/developer/style-palette-format.md).
