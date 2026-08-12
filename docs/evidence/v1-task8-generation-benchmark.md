# V1 Task 8 Generation Benchmark

Measured on 2026-08-12 with Eclipse Adoptium JDK 25.0.4 and Gradle 9.5.1 on the local Windows development host.

Command:

```powershell
.\gradlew.bat :architect-core:test --tests '*ArchitectEngineTest' --no-daemon
```

Fixture: one warm-up followed by ten deterministic 13 x 13, two-floor Medieval-style generations. Each generation evaluates exactly eight candidates and must produce a validated success.

Observed result:

```text
SSA_ARCHITECT_BENCHMARK samples=10 median_ms=226 p95_ms=313
```

The benchmark intentionally records evidence without a timing assertion so normal CI is not made flaky by machine load. The measured median is below the R2 target of 250 ms for a common two-floor house on this host.
