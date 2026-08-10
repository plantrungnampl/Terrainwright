# S3 Ghost Preview Rendering Spike Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` and `superpowers:test-driven-development` task-by-task.

**Goal:** Prove in a real Fabric 26.2 client that a supported Blaze3D/Fabric rendering path can display, replace, rotate and dispose immutable layered previews of 1,000 and 5,000 blocks without raw OpenGL, unbounded revision resources or unacceptable client-thread cost.

**Architecture:** All S3 code is disposable and remains under `dev.ssa.fabric.client.spike.preview`. A client-only manager owns at most one immutable preview revision and one `StagedVertexBuffer`. Fabric level extraction and render events pass only immutable state into the drawing phase. Replacing or disposing a revision closes its buffer immediately. Client GameTests create deterministic fixtures, capture screenshots and assert resource/metrics markers.

**Tech Stack:** Java 25, Fabric Loom 1.17.19, Fabric API 0.154.2+26.2 client GameTests, Minecraft 26.2 Blaze3D `RenderPipeline`/`StagedVertexBuffer` APIs.

## Constraints

- Follow Fabric 26.2's extraction/drawing split through `LevelExtractionEvents` and `LevelRenderEvents`.
- Use only Minecraft/Blaze3D/Fabric abstractions. Imports or calls to LWJGL OpenGL APIs and `RenderSystem.gl*` are forbidden.
- Support these visually distinct layers: required, optional, terrain fill, terrain removal and conflict. Color is not the only evidence; each layer is retained as typed immutable data and reported in test markers.
- Preview capacity is fixed at 5,000 blocks for this spike. Reject larger fixtures before allocating a GPU buffer.
- The active revision owns exactly one staged buffer. Replace/rotate/regenerate closes the previous buffer; dispose/client shutdown leaves zero live revision buffers.
- Measure 120 rendered frames after warmup. For the 5,000-block fixture, require renderer CPU p95 below `8,000 us`, max below `16,667 us`, and measured Java allocation p95 below `524,288 bytes/frame` on the declared host. A failed budget changes the approach rather than weakening the gate.
- S3 is evidence, not the final preview protocol or Architect UI. Do not add networking, server authority, screens, blueprint generation or persistence.

---

### Task 1: Enable isolated client GameTests

**Files:**
- Modify: `platform-fabric/build.gradle.kts`
- Modify: `platform-fabric/src/gametest/resources/fabric.mod.json`
- Create: `platform-fabric/src/gametest/java/dev/ssa/fabric/client/spike/preview/GhostPreviewClientGameTest.java`

- [ ] Enable Loom client GameTests while retaining the existing server GameTests.
- [ ] Add the `fabric-client-gametest` entrypoint.
- [ ] Add a smoke test that references a missing `GhostPreviewRenderer`; run `:platform-fabric:runClientGameTest` and record the expected compile failure.
- [ ] Verify the exact client GameTest task and screenshot directory.

### Task 2: Add immutable bounded revision data through TDD

**Files:**
- Create: `platform-fabric/src/client/java/dev/ssa/fabric/client/spike/preview/PreviewLayer.java`
- Create: `platform-fabric/src/client/java/dev/ssa/fabric/client/spike/preview/PreviewRevision.java`
- Create: `platform-fabric/src/client/java/dev/ssa/fabric/client/spike/preview/PreviewFixtures.java`
- Create: `platform-fabric/src/test/java/dev/ssa/fabric/client/spike/preview/PreviewRevisionTest.java`

- [ ] Test exact 1,000/5,000 counts, deterministic layer distribution, input copying and rejection above 5,000.
- [ ] Test 90-degree rotation creates a new immutable revision and leaves the source unchanged.
- [ ] Test regenerate changes revision/content identity without mutating the active snapshot.

### Task 3: Implement the supported renderer and revision ownership

**Files:**
- Create: `platform-fabric/src/client/java/dev/ssa/fabric/client/spike/preview/GhostPreviewRenderer.java`
- Create: `platform-fabric/src/client/java/dev/ssa/fabric/client/spike/preview/PreviewRenderMetrics.java`
- Create: `platform-fabric/src/client/java/dev/ssa/fabric/client/spike/preview/PreviewRevisionBuffer.java`
- Modify: `platform-fabric/src/client/java/dev/ssa/fabric/client/SmartSurvivalArchitectClient.java`

- [ ] Register a `RenderPipeline` derived from the supported debug-filled snippet with translucent blending and position/color vertices.
- [ ] Extract only the immutable active revision during `END_EXTRACTION`; draw it during `AFTER_TRANSLUCENT_TERRAIN` using a revision-owned `StagedVertexBuffer`.
- [ ] Render unit cubes with typed layer colors, camera-relative transforms and no world mutation.
- [ ] Record render callback duration and thread allocation without allocating per block.
- [ ] Replace closes the prior buffer before publishing the next revision. Dispose and client stopping close the active buffer and expose zero live buffers.

### Task 4: Prove screenshots, lifecycle and budgets in Client GameTests

**Files:**
- Modify: `platform-fabric/src/gametest/java/dev/ssa/fabric/client/spike/preview/GhostPreviewClientGameTest.java`

- [ ] Create a singleplayer world, position the player/camera, wait for chunks/rendering and install the 1,000-block fixture.
- [ ] Capture a screenshot with every typed layer present and emit `SSA_S3_SCREENSHOT` with backend identity and revision.
- [ ] Replace with the 5,000-block fixture; assert active/live/closed buffer counters and capture the second screenshot.
- [ ] Rotate and regenerate into new revisions; assert the prior buffers close exactly once and the source revisions remain unchanged.
- [ ] Warm up, collect 120 frames, emit `SSA_S3_PROFILE`, and enforce the declared CPU/allocation thresholds.
- [ ] Dispose in `finally`; assert zero live buffers and emit `SSA_S3_LIFECYCLE`.

### Task 5: Publish repeatable backend evidence

**Files:**
- Create: `tools/Invoke-S3PreviewCheck.ps1`
- Create: `docs/spikes/S3/default-client.log`
- Create: `docs/spikes/S3/result.md`
- Create: `docs/spikes/S3/screenshots/` evidence copied from the Loom run directory

- [ ] The harness runs the exact default client GameTest task, captures output, requires screenshot/profile/lifecycle/backend markers, enforces thresholds and copies screenshots.
- [ ] Run a second client GameTest with `--graphicsBackend vulkan` when the runtime exposes it. If backend creation fails, preserve the exact log and record Vulkan unavailable rather than claiming it was tested.
- [ ] Scan client sources for raw OpenGL imports/calls and reject any match.
- [ ] Record backend/device identity, screenshot hashes, lifecycle counters, metrics, commands, exit codes, host/JVM and design findings.
- [ ] Run `clean test build`, default S3 harness, Vulkan probe, module-boundary tests and `git diff --check`; commit S3 only after every applicable gate exits `0`.
