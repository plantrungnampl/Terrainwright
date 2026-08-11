# V1 Mandatory Spike Evidence Index

**Promotion status:** S1-S5 PASS
**Updated:** 2026-08-11

All five mandatory implementation spikes in the R2 architecture now have
repeatable runtime evidence. This promotes the repository past the spike gate;
it does not mean the V1 gameplay vertical slices are complete.

| Spike | Result | Repeatable command | Primary evidence |
| --- | --- | --- | --- |
| S1 Fabric bootstrap | PASS | `.\tools\Invoke-S1LaunchCheck.ps1` | `S1/result.md`, client/server launch logs, pinned toolchain |
| S2 Builder navigation | PASS | `.\tools\Invoke-S2NavigationCheck.ps1` | `S2/result.md`, 13 server GameTests and path-attempt metrics |
| S3 ghost preview | PASS | `.\tools\Invoke-S3PreviewCheck.ps1` | `S3/result.md`, OpenGL/Vulkan client logs, screenshots and frame/allocation metrics |
| S4 OperationIntent durability | PASS | `.\tools\Invoke-S4PersistenceCheck.ps1` | `S4/result.md`, clean build, crash matrix, GameTests and ACK latency |
| S5 real-process restart | PASS | `.\tools\Invoke-S5RestartCheck.ps1` | `S5/result.md`, two clean universes, 44 JVM records and 44 saved fixture/result manifests |

## Promotion boundary

The evidence resolves the five implementation-blocking unknowns identified by
the architecture review: current Fabric bootstrap/API shape, disposable Builder
navigation, supported ghost rendering backends, forced OperationIntent
durability/recovery, and actual dedicated-server process restart behavior on the
same world. Subsequent development must still pass the exact child TDD gate for
each V1 vertical slice and may not treat spike adapters as finished gameplay
architecture.
