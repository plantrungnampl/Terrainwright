# Style Palette Format

V1 style resources live under `data/smart_survival_architect/styles/` and use `formatVersion: 1`. The shipped files are `medieval.json`, `japanese.json`, and `modern.json`.

The loader is intentionally a palette override boundary, not an executable style SDK. Only the three trusted V1 IDs are loaded, and each document's `geometryRules.trustedGeneratorProfile` must match its built-in generator (`MEDIEVAL`, `JAPANESE`, or `MODERN`). JSON cannot replace geometry code or add scripts.

## Document contract

Every document must contain exactly these top-level fields, with no aliases or unknown fields:

```text
formatVersion, id, displayName, version,
geometryRules, proportionRules, footprintWeights, foundationRules,
framingRules, roofRules, openingRules, roomBiases, decorationRules,
materialPalette, fallbackPalette
```

`materialPalette` must override at least one canonical material role. A candidate whose optional mod block, state, or capabilities are unavailable is skipped. `fallbackPalette` must contain every canonical role and every fallback candidate must resolve to a compatible installed block; otherwise the entire resource is rejected and the trusted built-in palette remains active.

Each role contains 1-16 candidates. A candidate contains exactly:

```json
{
  "blockId": "minecraft:dark_oak_stairs",
  "requiredCapabilities": ["STAIR", "HORIZONTAL_FACING"],
  "weight": 1.0,
  "stateTemplate": { "facing": "north" }
}
```

- `blockId` is a namespaced installed block ID.
- `requiredCapabilities` contains at most eight unique canonical capabilities.
- `weight` must be finite and between 0 and 1 inclusive.
- `stateTemplate` contains at most 24 properties, and every property/value must exist on the actual block state.

## Canonical vocabulary

Material roles:

```text
FOUNDATION_STONE, FOUNDATION_FILL, STRUCTURAL_WOOD, STRUCTURAL_PRIMARY,
WALL_PRIMARY, WALL_SECONDARY, FLOOR_PRIMARY, FLOOR_SECONDARY,
ROOF_PRIMARY, ROOF_ACCENT, TRIM, WINDOW, DOOR, RAILING, STAIR,
INTERIOR_PRIMARY, LIGHTING, TEMP_SCAFFOLD
```

Capabilities:

```text
FULL_CUBE, STAIR, SLAB, PANE, DOOR, TRAPDOOR, FENCE, FENCE_OR_WALL,
LIGHT_SOURCE, ORIENTABLE_AXIS, HORIZONTAL_FACING
```

Capability names and material roles are exact and case-sensitive. The loader derives actual capabilities from the registered block and validates the proposed default state; declarations alone never make a block compatible.

## Validation

Use the shipped resources as complete examples. Run:

```powershell
.\gradlew.bat :platform-fabric:test --tests '*StyleDataLoaderTest' --no-daemon
.\gradlew.bat :platform-fabric:runGameTest --no-daemon
```

The unit suite covers unknown fields, aliases, incomplete fallback, non-finite weights, absent optional blocks, and invalid states/capabilities. The GameTest suite reloads the server resources and verifies compatible vanilla fallback generation.
