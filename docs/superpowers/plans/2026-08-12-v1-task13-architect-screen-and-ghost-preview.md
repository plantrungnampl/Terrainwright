# V1 Task 13: Architect Screen and Ghost Preview

**Goal:** Turn the server-authoritative `PreviewResult` from Task 12 into a bounded, client-only Architect workflow and world ghost preview without allowing client state to mutate the world or become confirmation authority.

**Assumptions and boundaries:** The server remains authoritative for Survey selection, preview generation, expiry, terrain revalidation, and BuildJob creation. The preview payload must carry its server origin because a Blueprint contains only local coordinates. The screen may open when a Survey token arrives, but Builder Hut selection remains an external integration point; Confirm stays disabled until a Hut ID is supplied. The renderer reuses the supported Fabric/Blaze3D path proven by Spike S3 and never calls raw OpenGL. V1 accepts at most 5,000 rendered cells per revision.

## Implementation plan

1. **Specify transforms and client authority rules with tests.** Add four-quarter-turn, origin translation, stale nonce, rotation, and bounded-geometry tests. Verify the focused test fails before implementation and passes afterward.
2. **Complete the trusted preview DTO.** Add the server origin to `PreviewResult`, encode/decode it exactly, and source it from the server-side `PreviewSession`. Update codec tests so rendering never depends on a client-invented origin.
3. **Build bounded client state.** Add `PreviewTransform` and `PreviewClientState` to retain only the newest matching server result, derive required/optional/terrain/footprint/entrance/conflict layers, rebuild immutable revisions, and invalidate Confirm whenever the preview is moved or regenerated.
4. **Promote the S3 rendering path.** Add the production `GhostPreviewRenderer` facade, extend the proven layer model for footprint and entrance markers, and preserve one-buffer ownership with dispose-on-revision replacement.
5. **Add the Architect screen and client networking.** Register clientbound receivers, render requirement/result summaries, and provide Generate/Regenerate, Rotate, Move, and Confirm controls. Confirm sends only the server session ID/hash plus a selected Hut ID.
6. **Verify production behavior.** Run focused unit tests, the full clean unit/build/server-GameTest gate, the S1 layout check, `git diff --check`, and the client GameTest bootstrap. The client evidence must retain the S3 1,000/5,000-block performance/lifecycle checks; Task 12 GameTests remain the server revalidation and confirmation authority evidence.

