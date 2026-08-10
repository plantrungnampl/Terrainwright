# S2 Builder Navigation Spike Result

**Gate:** PASS

**Date:** 2026-08-10

**Scope:** Disposable Fabric server GameTest spike under `dev.ssa.fabric.spike.navigation`.

## Verification

- Command: `.\gradlew.bat clean test build --no-daemon`
- Result: exit `0`; all 13 required GameTests passed during the clean build.
- Independent harness: `.\tools\Invoke-S2NavigationCheck.ps1`
- Result: exit `0`; evidence is captured in `gametest.log`.
- Runtime: Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.154.2+26.2, Loom 1.17.19.
- JVM: Eclipse Temurin 25.0.4+7 LTS.
- Host: Windows 11 Pro, Intel Core i7-12700F (12 cores/20 logical processors), 32 GiB RAM.

## Fixture outcomes

| Fixture | Outcome | Key evidence |
| --- | --- | --- |
| Flat chest to site | PASS | One cobblestone moved chest -> carried slot -> target; final state `SUCCESS`. |
| One-block step | PASS | Real path traversal; final state `SUCCESS`. |
| Two-high doorway | PASS | Real path traversal; final state `SUCCESS`. |
| Fence obstruction | PASS | Stable `BLOCKED`; no target mutation. |
| Upper floor | PASS | Vanilla stair route; final state `SUCCESS`. |
| Scaffold required | PASS | Three-block ramp; four total path attempts across both legs; no teleport. |
| Impossible target | PASS | Stable `BLOCKED`; attempts and world state unchanged for another 40 ticks. |
| Unloaded destination | PASS | `SUSPENDED_CHUNK_UNLOADED`; zero path attempts and zero mutations. |
| Stuck timeout | PASS | One exact 40-tick timeout, bounded at three attempts, then `BLOCKED`. |
| Executor unloaded-target guard | PASS | Executor rejects the operation without loading the target chunk or debiting inventory. |

## Bounds and authority

- Path attempts are bounded to three per leg.
- Stuck detection uses a 40-tick window and a 0.05-block progress threshold.
- Standing candidates require solid support, clear feet/head space, reach at most 4.5 blocks, and line of sight.
- The placement executor runs on `ServerLevel` and revalidates loaded target, reach, line of sight, inventory, and target replaceability before debit plus placement.
- Production spike code contains no `setPos`, `teleportTo`, or equivalent position-forcing call after scenario start.
- Maximum observed per-tick movement in the fixtures was below the 1.5-block gate.

## Path-attempt profile

The independent harness measured 100 short-route `PathNavigation.createPath` calls after warmup:

| Metric | Result | Gate |
| --- | ---: | ---: |
| p50 | 274 us | informational |
| p95 | 385 us | < 10,000 us |
| max | 414 us | < 50,000 us |

This is a local spike measurement, not a production capacity guarantee.

## Design findings

- A newly spawned mob must be grounded before requesting its first path; the spike defers path creation until `onGround()`.
- A valid candidate can already be inside the arrival radius; this must be treated as arrival instead of a null-path failure.
- Server-side world mutation must reject unloaded targets before any block read/write to avoid implicit chunk loading.
- S2 remains disposable evidence. Its state machine, one-slot inventory, cobblestone-only executor, and fixed scaffold ramp are not the final Builder implementation.
