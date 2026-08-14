# Terrainwright

[![CI](https://github.com/plantrungnampl/Terrainwright/actions/workflows/ci.yml/badge.svg)](https://github.com/plantrungnampl/Terrainwright/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/plantrungnampl/Terrainwright?display_name=tag)](https://github.com/plantrungnampl/Terrainwright/releases/latest)
[![License: MIT](https://img.shields.io/github/license/plantrungnampl/Terrainwright)](LICENSE)
[![Minecraft 26.2](https://img.shields.io/badge/Minecraft-26.2-62b47a)](https://www.minecraft.net/)

## Build smarter. Keep it Survival.

**Terrainwright** is a Minecraft Java Fabric mod for designing terrain-aware houses and having a server-owned Builder construct them **block by block from real survival materials** stored in a linked vanilla chest.

No creative-mode structure paste. No silently spawned materials. You choose the site and design, approve the ghost preview, supply the resources, and the Builder does the construction.

**[Download the latest release](https://github.com/plantrungnampl/Terrainwright/releases/latest)** · **[Getting Started](docs/player/getting-started.md)** · **[Discussions](https://github.com/plantrungnampl/Terrainwright/discussions)** · **[Report a bug](https://github.com/plantrungnampl/Terrainwright/issues)**

> If Terrainwright helps you create a world worth keeping, consider starring the repository and sharing a real build with the community.

## The survival building loop

1. **Design** — choose Medieval, Japanese, or Modern, then configure the footprint, floors, rooms, and entrance preference.
2. **Survey** — select a real site and let Terrainwright evaluate the local terrain.
3. **Preview** — inspect a server-authoritative ghost build before committing any materials.
4. **Supply** — link a vanilla chest and provide the actual blocks required by the plan.
5. **Build** — a server-owned Builder carries bounded batches and constructs the house over time.
6. **Control** — Pause, Resume, Stop, or use Safe Undo when needed.

## Why Terrainwright?

| | Terrainwright |
|---|---|
| **Survival materials** | Uses real blocks from your linked chest instead of silently spawning resources |
| **Terrain awareness** | Adapts bounded house generation to flat ground and gentle slopes |
| **Visible construction** | A Builder performs the build instead of instantly pasting a finished structure |
| **Player approval** | The generated ghost preview must be confirmed before construction begins |
| **Server authority** | Permissions, materials, previews, placement, Stop, and Safe Undo are validated by the server |
| **Recovery safety** | Restart reconciliation, chunk suspension, Builder tombstones, and quarantine avoid unsafe automatic guesses |

## V1.0 highlights

- **Three architectural identities:** Medieval, Japanese, and Modern use different geometry and material palettes.
- **Three bounded footprints:** 9x11, 15x19, and 21x25 with 1-3 floors and configurable house features.
- **Terrain-aware generation:** deterministic layouts support flat ground and gentle slopes without modifying water or lava.
- **Real logistics:** one Builder per Hut, linked vanilla single/double chests, bounded carrying capacity, and exact missing-material guidance.
- **Safe controls:** Pause/Resume, Stop, Safe Undo, restart reconciliation, unloaded-chunk suspension, and lost-Builder recovery.
- **Multiplayer-conscious architecture:** server-authoritative previews, permissions, material accounting, and world placement.

### Deliberate V1 non-goals

V1 does **not** include LLM generation, basements, arbitrary structure types, self-mining/crafting, forced chunk loading, teleport fallback, or multi-Builder scheduling. Terrainwright keeps the first release focused on a deterministic, inspectable survival workflow.

## Requirements

- Minecraft Java Edition 26.2
- Fabric Loader 0.19.3 or newer
- Fabric API 0.154.2+26.2
- Java 25
- Terrainwright installed on the dedicated server and every connecting client

## Install

1. Download `terrainwright-1.0.0.jar` from the [latest release](https://github.com/plantrungnampl/Terrainwright/releases/latest).
2. Install the matching Fabric Loader and Fabric API.
3. Put the Terrainwright and Fabric API JARs in the Minecraft `mods` directory on the server and clients.
4. Start the game with Java 25.

The public product name is **Terrainwright**. The technical Fabric mod ID remains `smart_survival_architect` to preserve registry and world-data compatibility.

## Play

1. Craft and place an Architect Table and a Builder Hut.
2. Place a vanilla chest within 16 blocks, open the Hut, and choose **Link / Relink Builder Chest**.
3. Use the Architect Table to select a site, configure a house, and generate the server-authoritative ghost preview.
4. Select your owned Hut and confirm the unchanged preview.
5. Put the missing materials shown by the Hut UI into the linked chest.
6. Use Pause, Resume, Stop, or Safe Undo from the Hut when needed.

See [Getting Started](docs/player/getting-started.md) for recipes and the complete UI sequence.

## Build and verify

On Linux or macOS:

```bash
./gradlew clean test build --no-daemon
```

On Windows PowerShell:

```powershell
.\gradlew.bat clean test build --no-daemon
```

The runnable mod JAR is produced under `platform-fabric/build/libs/`. The complete build runs all module tests and the Fabric GameTest matrix.

Additional durability gates are available for contributors working on persistence or recovery:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verification/Invoke-S4PersistenceCheck.ps1
powershell -ExecutionPolicy Bypass -File scripts/verification/Invoke-S5RestartCheck.ps1
```

## Documentation

- [Player guide](docs/player/getting-started.md)
- [Server configuration and safety](docs/server/configuration-and-safety.md)
- [Style palette format](docs/developer/style-palette-format.md)
- [Changelog](CHANGELOG.md)

## Contributing and security

Focused bug reports, tests, documentation, translations, and compatible palette improvements are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

Report vulnerabilities privately according to [SECURITY.md](SECURITY.md). Please do not publish exploit details in a public issue.

Terrainwright is available under the [MIT License](LICENSE).
