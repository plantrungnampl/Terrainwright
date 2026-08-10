# S3 Ghost Preview Rendering Result

**Status:** PASS
**Date:** 2026-08-10

## Scope

This spike proves that an immutable preview revision containing 1,000 or 5,000
ghost blocks can be rendered through supported Minecraft/Fabric rendering APIs.
It covers the five required semantic layers, revision replacement, rotation,
regeneration, backend identity, bounded buffer ownership, frame cost, allocation
cost, screenshots, and disposal.

The spike is disposable evidence. It is not the final networked preview feature.

## Reproduction

Run the complete backend check:

```powershell
.\tools\Invoke-S3PreviewCheck.ps1
```

The harness runs these client GameTest commands:

```powershell
.\gradlew.bat :platform-fabric:runClientGameTest --no-daemon
.\gradlew.bat :platform-fabric:runClientGameTest --args="--graphicsBackend vulkan" --no-daemon
```

Runtime used for the recorded evidence:

- Windows 11 Pro
- Intel Core i7-12700F, 12 cores / 20 logical processors
- 32 GiB RAM
- NVIDIA GeForce RTX 4060, driver 591.86
- Eclipse Temurin 25.0.4+7
- Minecraft 26.2 / Fabric Loader 0.19.3 / Fabric API 0.154.2+26.2

## Rendering evidence

Both backends rendered the same deterministic preview geometry. The client
GameTest also performs a pixel-level visibility assertion before accepting each
screenshot.

| Backend | 5k p50 | 5k p95 | 5k max | Allocation p95 | Allocation max |
| --- | ---: | ---: | ---: | ---: | ---: |
| OpenGL 3.3.0 / NVIDIA 591.86 | 32 us | 64 us | 379 us | 1,752 B/frame | 1,752 B/frame |
| Vulkan 1.4.325 / NVIDIA 591.86 | 19 us | 24 us | 62 us | 1,256 B/frame | 1,256 B/frame |
| Gate | - | < 8,000 us | < 16,667 us | < 524,288 B/frame | - |

Each profile contains exactly 120 measured steady-state frames after 30 warm-up
frames. The 5,000-block revision has exactly 1,000 blocks in each layer:
`REQUIRED`, `OPTIONAL`, `TERRAIN_FILL`, `TERRAIN_REMOVAL`, and `CONFLICT`.

| Screenshot | Bytes | SHA-256 |
| --- | ---: | --- |
| `default-preview-1000.png` | 34,659 | `E76174B329786105A5A394627F6BF8980BBE575B3915D155ADCBBC65D98AE048` |
| `default-preview-5000.png` | 46,068 | `4153386514CB4B11947D67A6AFE2BE5A8E22D3A00F085A67AC4EF4B753A6AC15` |
| `vulkan-preview-1000.png` | 34,659 | `E76174B329786105A5A394627F6BF8980BBE575B3915D155ADCBBC65D98AE048` |
| `vulkan-preview-5000.png` | 46,068 | `4153386514CB4B11947D67A6AFE2BE5A8E22D3A00F085A67AC4EF4B753A6AC15` |

The matching hashes show that the same camera and preview revision produce the
same pixels on the two available backends.

## Lifecycle evidence

The test creates four immutable revisions:

1. revision 1: 1,000-block preview;
2. revision 2: 5,000-block replacement;
3. revision 3: rotated replacement;
4. revision 4: regenerated replacement.

Every revision owns at most one staged/GPU buffer. Replacing a revision closes
the previous buffer, and client shutdown closes the final buffer. The recorded
terminal state is `created=4`, `closed=4`, `live=0`.

The original revision remains unchanged after rotate and regenerate operations,
which proves the source preview is immutable.

## API boundary

The renderer uses Fabric level extraction/render events, vanilla
`RenderPipelines.DEBUG_FILLED_BOX`, `StagedVertexBuffer`, and the Blaze3D GPU
device APIs. The harness scans client sources and fails if raw LWJGL OpenGL APIs
or `RenderSystem.gl*` calls are present.

## Design findings

- Rebuilding 5,000 boxes every frame allocated about 2.88 MiB/frame, so geometry
  is now built and uploaded once per immutable revision.
- Profiling immediately after a revision replacement measured upload warm-up,
  so the steady-state profile begins only after 30 rendered warm-up frames.
- Minecraft 26.2's position-color shader does not consume `ModelOffset` for this
  pipeline. The working transform is a composite `ModelViewMat` built from the
  current render pose and camera-relative preview origin.
- Revision-owned buffers make replacement and disposal explicit and bounded;
  the renderer has no unbounded cache keyed by revision.

## Evidence files

- `default-client.log`
- `vulkan-client.log`
- `screenshots/default-preview-1000.png`
- `screenshots/default-preview-5000.png`
- `screenshots/vulkan-preview-1000.png`
- `screenshots/vulkan-preview-5000.png`
