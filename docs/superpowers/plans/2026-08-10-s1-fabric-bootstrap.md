# S1 Fabric Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a four-module Java 25/Fabric 26.2 repository that builds a loadable mod jar and proves clean client and dedicated-server startup without client-class linkage on the server.

**Architecture:** The repository has three pure Java modules and one Fabric adapter module. Only `platform-fabric` applies Loom or imports Minecraft/Fabric classes; a source-boundary test prevents runtime dependencies from leaking inward. S1 registers one inert marker block and records fresh build/client/server evidence without implementing gameplay.

**Tech Stack:** Eclipse Temurin JDK 25.0.4.7, Gradle Wrapper 9.5.1, Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.154.2+26.2, Fabric Loom 1.17.19, Java 25, JUnit Jupiter 5.14.3, PowerShell 5.1.

## Global Constraints

- Work directly in `D:\Terrainwright` on `main`, as explicitly requested by the user.
- Do not modify or stage `Smart-Survival-Architect-Master-Architecture-R2/`.
- Use mod ID `smart_survival_architect` and Java package prefix `dev.ssa`.
- `architect-core`, `construction-core`, and `minecraft-common` must not import `net.minecraft` or `net.fabricmc`.
- Dependency direction is `construction-core -> architect-core`, `minecraft-common -> architect-core + construction-core`, and `platform-fabric -> all three`.
- Pin the R2 baseline exactly for the first build. If an exact coordinate fails, S1 fails and records the incompatibility; do not silently upgrade it.
- No Architect UI, Builder behavior, procedural generation, persistence, networking, or construction gameplay belongs in S1.
- Configuration and upstream Gradle wrapper files are mechanical bootstrap artifacts. New Java behavior follows red-green-refactor.

---

### Task 1: Install Java 25 and establish the Gradle wrapper

**Files:**
- Create: `.gitignore`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `docs/spikes/S1/toolchain.md`

**Interfaces:**
- Consumes: Windows `winget`, official Fabric example-mod `26.2` wrapper files.
- Produces: `java`/`javac` 25 and `./gradlew --version` reporting Gradle 9.5.1.

- [ ] **Step 1: Capture the failing host preflight**

Run:

```powershell
java -version
javac -version
Test-Path .\gradlew.bat
```

Expected: Java commands are not found and the wrapper path is `False`.

- [ ] **Step 2: Install the exact JDK package and verify a fresh shell**

Run:

```powershell
winget install --id EclipseAdoptium.Temurin.25.JDK --exact --version 25.0.4.7 --accept-package-agreements --accept-source-agreements
```

Open a fresh PowerShell process and run:

```powershell
java -version
javac -version
```

Expected: both report major version 25.

- [ ] **Step 3: Retrieve the official Gradle wrapper mechanically**

Download these exact paths from the official Fabric example-mod `26.2` branch:

```text
https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/gradlew
https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/gradlew.bat
https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/gradle/wrapper/gradle-wrapper.jar
https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/gradle/wrapper/gradle-wrapper.properties
```

Keep the upstream wrapper scripts and jar byte-for-byte. Set the properties file distribution URL to `https\://services.gradle.org/distributions/gradle-9.5.1-bin.zip` if the official branch changes.

- [ ] **Step 4: Add repository ignores**

Create `.gitignore` with:

```gitignore
.gradle/
.idea/
build/
*/build/
run/
logs/
*.log
Smart-Survival-Architect-Master-Architecture-R2/
smart-survival-architect/
```

- [ ] **Step 5: Verify the wrapper**

Run:

```powershell
.\gradlew.bat --version --no-daemon
```

Expected: Gradle 9.5.1 running on JVM 25.

- [ ] **Step 6: Record and commit toolchain evidence**

`docs/spikes/S1/toolchain.md` records the commands, resolved versions, source URLs, timestamp, and exit codes without claiming client/server launch yet.

```powershell
git add .gitignore gradlew gradlew.bat gradle docs/spikes/S1/toolchain.md
git commit -m "build: establish java 25 gradle wrapper"
```

---

### Task 2: Define the four-module Gradle graph

**Files:**
- Create: `tools/verify-s1-layout.ps1`
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `architect-core/build.gradle.kts`
- Create: `construction-core/build.gradle.kts`
- Create: `minecraft-common/build.gradle.kts`
- Create: `platform-fabric/build.gradle.kts`

**Interfaces:**
- Consumes: Gradle wrapper and pinned coordinates from Task 1.
- Produces: four addressable Gradle projects with the approved acyclic dependencies.

- [ ] **Step 1: Write the layout verification script**

Create `tools/verify-s1-layout.ps1`:

```powershell
$ErrorActionPreference = 'Stop'
$ssaRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ssaRequired = @(
    'settings.gradle.kts',
    'build.gradle.kts',
    'gradle.properties',
    'architect-core/build.gradle.kts',
    'construction-core/build.gradle.kts',
    'minecraft-common/build.gradle.kts',
    'platform-fabric/build.gradle.kts'
)
foreach ($ssaPath in $ssaRequired) {
    if (-not (Test-Path -LiteralPath (Join-Path $ssaRoot $ssaPath))) {
        throw "Missing required S1 file: $ssaPath"
    }
}
$ssaSettings = Get-Content -Raw -LiteralPath (Join-Path $ssaRoot 'settings.gradle.kts')
foreach ($ssaModule in @('architect-core','construction-core','minecraft-common','platform-fabric')) {
    if ($ssaSettings -notmatch [regex]::Escape(":$ssaModule")) {
        throw "Module not included: $ssaModule"
    }
}
Write-Output 'PASS S1 module layout'
```

- [ ] **Step 2: Run the script and verify red**

Run:

```powershell
.\tools\verify-s1-layout.ps1
```

Expected: FAIL with `Missing required S1 file: settings.gradle.kts`.

- [ ] **Step 3: Create the root settings and pinned properties**

`settings.gradle.kts` must include exactly the four modules and Fabric's plugin repository. `gradle.properties` must contain:

```properties
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true
org.gradle.configuration-cache=false
minecraft_version=26.2
loader_version=0.19.3
loom_version=1.17.19
fabric_api_version=0.154.2+26.2
mod_version=0.1.0-SNAPSHOT
maven_group=dev.ssa
```

- [ ] **Step 4: Create minimal focused module builds**

The root build config applies Java 25 and JUnit Jupiter 5.14.3 to subprojects. Pure modules apply `java-library`; `construction-core` depends on `project(":architect-core")`; `minecraft-common` depends on both pure cores. `platform-fabric` applies `net.fabricmc.fabric-loom`, declares Minecraft/Loader/Fabric API, and depends on all pure projects.

- [ ] **Step 5: Verify layout and Gradle project discovery**

Run:

```powershell
.\tools\verify-s1-layout.ps1
.\gradlew.bat projects --no-daemon
```

Expected: layout PASS and all four subprojects listed.

- [ ] **Step 6: Commit the module graph**

```powershell
git add settings.gradle.kts build.gradle.kts gradle.properties tools architect-core construction-core minecraft-common platform-fabric
git commit -m "build: define fabric module boundaries"
```

---

### Task 3: Add the first pure domain primitive with boundary enforcement

**Files:**
- Create: `architect-core/src/test/java/dev/ssa/architect/model/NamespacedIdTest.java`
- Create: `architect-core/src/test/java/dev/ssa/architect/ArchitectureBoundaryTest.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/model/NamespacedId.java`

**Interfaces:**
- Consumes: JUnit-enabled `architect-core`.
- Produces: `NamespacedId.parse(String)` and a repeatable source-boundary test.

- [ ] **Step 1: Write the failing value-object test**

```java
package dev.ssa.architect.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NamespacedIdTest {
    @Test
    void parsesCanonicalNamespacedId() {
        assertEquals("smart_survival_architect:spike_marker",
                NamespacedId.parse("smart_survival_architect:spike_marker").toString());
    }

    @Test
    void rejectsUnnamespacedId() {
        assertThrows(IllegalArgumentException.class, () -> NamespacedId.parse("spike_marker"));
    }
}
```

- [ ] **Step 2: Run the test and verify red**

Run:

```powershell
.\gradlew.bat :architect-core:test --tests '*NamespacedIdTest' --no-daemon
```

Expected: FAIL because `NamespacedId` does not exist.

- [ ] **Step 3: Implement the minimal immutable value object**

Implement a Java record that accepts lowercase namespace/path characters matching `^[a-z0-9_.-]+:[a-z0-9_./-]+$`, rejects null/noncanonical values, and renders `namespace:path`.

- [ ] **Step 4: Add the source-boundary test**

`ArchitectureBoundaryTest` walks `architect-core`, `construction-core`, and `minecraft-common` main Java sources from the repository root and fails if a line contains `import net.minecraft` or `import net.fabricmc`.

- [ ] **Step 5: Run all pure tests and commit**

```powershell
.\gradlew.bat :architect-core:test :construction-core:test :minecraft-common:test --no-daemon
git add architect-core construction-core minecraft-common
git commit -m "feat: add pure architect identity primitive"
```

Expected: all pure tests PASS.

---

### Task 4: Bootstrap Fabric entrypoints and one inert block

**Files:**
- Create: `platform-fabric/src/test/java/dev/ssa/fabric/block/ModBlockIdsTest.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/SmartSurvivalArchitectMod.java`
- Create: `platform-fabric/src/client/java/dev/ssa/fabric/client/SmartSurvivalArchitectClient.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/block/ModBlockIds.java`
- Create: `platform-fabric/src/main/java/dev/ssa/fabric/block/ModBlocks.java`
- Create: `platform-fabric/src/main/resources/fabric.mod.json`

**Interfaces:**
- Consumes: Fabric 26.2 official block-registration API and `dev.ssa` module graph.
- Produces: common/client entrypoints and registered block key `smart_survival_architect:spike_marker`.

- [ ] **Step 1: Write the failing block-ID test**

```java
package dev.ssa.fabric.block;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ModBlockIdsTest {
    @Test
    void spikeMarkerUsesStableNamespacedLocation() {
        assertEquals("smart_survival_architect:spike_marker",
                ModBlockIds.SPIKE_MARKER.identifier().toString());
    }
}
```

- [ ] **Step 2: Run the test and verify red**

Run:

```powershell
.\gradlew.bat :platform-fabric:test --tests '*ModBlockIdsTest' --no-daemon
```

Expected: FAIL because `ModBlockIds` does not exist.

- [ ] **Step 3: Implement the ID and inert block registration**

Use the official 26.2 API shape:

```java
public static final ResourceKey<Block> SPIKE_MARKER = ResourceKey.create(
        Registries.BLOCK,
        Identifier.fromNamespaceAndPath(SmartSurvivalArchitectMod.MOD_ID, "spike_marker"));
```

`ModBlocks` creates `new Block(BlockBehaviour.Properties.of().setId(ModBlockIds.SPIKE_MARKER))`, registers it in `BuiltInRegistries.BLOCK`, and exposes an empty `initialize()` method to trigger static initialization. Do not add a block item, assets, loot, or creative-tab integration in S1.

- [ ] **Step 4: Implement thin entrypoints and metadata**

The common initializer calls `ModBlocks.initialize()` and logs one stable marker: `SSA_S1_COMMON_READY`. The client initializer logs `SSA_S1_CLIENT_READY`. `fabric.mod.json` declares both entrypoints, Java `>=25`, Minecraft `~26.2`, Loader `>=0.19.3`, and Fabric API.

- [ ] **Step 5: Run the focused test, boundary tests, and build**

```powershell
.\gradlew.bat :platform-fabric:test --tests '*ModBlockIdsTest' --no-daemon
.\gradlew.bat test build --no-daemon
```

Expected: focused test PASS, boundary tests PASS, and remapped jar produced under `platform-fabric/build/libs/`.

- [ ] **Step 6: Commit the Fabric bootstrap**

```powershell
git add platform-fabric
git commit -m "feat: bootstrap fabric entrypoints and marker block"
```

---

### Task 5: Prove client and dedicated-server startup

**Files:**
- Create: `tools/Invoke-S1LaunchCheck.ps1`
- Create: `docs/spikes/S1/client-launch.log`
- Create: `docs/spikes/S1/server-launch.log`
- Create: `docs/spikes/S1/result.md`

**Interfaces:**
- Consumes: `runClient`, `runServer`, stable S1 log markers, and the built jar.
- Produces: bounded launch checks with captured logs and explicit pass/fail evidence.

- [ ] **Step 1: Write the launch-check harness before running either environment**

The PowerShell harness accepts `-Mode Client|Server` and `-TimeoutSeconds`. It starts `gradlew.bat --no-daemon runClient` or `runServer` with redirected output, waits for the expected stable marker plus an environment-specific ready signal, writes output to the matching evidence log, and terminates only the process tree it started after success/timeout. Server mode sends `stop` over redirected standard input when the server accepts it. Client mode records the ready marker before stopping its owned process tree.

- [ ] **Step 2: Run the harness with an impossible timeout and verify red**

Run:

```powershell
.\tools\Invoke-S1LaunchCheck.ps1 -Mode Server -TimeoutSeconds 1
```

Expected: nonzero exit with a clear timeout and a captured log; no PASS result file.

- [ ] **Step 3: Run the dedicated-server check**

Create the dev-run EULA acceptance file only in ignored `run/`, then run:

```powershell
.\tools\Invoke-S1LaunchCheck.ps1 -Mode Server -TimeoutSeconds 180
```

Expected: `SSA_S1_COMMON_READY`, Minecraft server ready signal, clean `stop`, and no client classloading error.

- [ ] **Step 4: Run the client check**

Run:

```powershell
.\tools\Invoke-S1LaunchCheck.ps1 -Mode Client -TimeoutSeconds 180
```

Expected: both `SSA_S1_COMMON_READY` and `SSA_S1_CLIENT_READY`, a client-ready signal, and no startup exception.

- [ ] **Step 5: Write the S1 result and commit evidence**

`result.md` lists exact commands, exit codes, resolved artifacts, marker matches, any warnings, and a PASS only if both launch checks succeeded in this run.

```powershell
git add tools/Invoke-S1LaunchCheck.ps1 docs/spikes/S1
git commit -m "test: prove fabric client and server bootstrap"
```

---

### Task 6: Fresh S1 gate verification

**Files:**
- Modify: `docs/spikes/S1/result.md`

**Interfaces:**
- Consumes: all Task 1-5 outputs.
- Produces: one fresh S1 gate decision authorizing or blocking S2-S5 planning.

- [ ] **Step 1: Run clean verification**

```powershell
.\gradlew.bat clean test build --no-daemon
.\tools\verify-s1-layout.ps1
.\tools\Invoke-S1LaunchCheck.ps1 -Mode Server -TimeoutSeconds 180
.\tools\Invoke-S1LaunchCheck.ps1 -Mode Client -TimeoutSeconds 180
git diff --check
```

Expected: every command exits 0.

- [ ] **Step 2: Inspect jar and module boundaries**

Confirm the remapped jar contains the common/client entrypoint classes and `fabric.mod.json`; confirm pure sources contain no Minecraft/Fabric imports.

- [ ] **Step 3: Record the final evidence timestamp and commit only if changed**

If verification refreshed `result.md` or logs:

```powershell
git add docs/spikes/S1
git commit -m "docs: refresh s1 bootstrap evidence"
```

- [ ] **Step 4: Stop at the gate**

If any command is nonzero, keep S1 open and report the exact failing command. If all checks pass, mark only S1 passed and write the separate S2 navigation plan next.
