# Terrainwright Open-Source Launch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish Terrainwright as a credible MIT-licensed public GitHub repository with CI, community files, discoverability metadata, and a verified `v1.0.0` JAR release.

**Architecture:** Keep gameplay and the compatibility-sensitive `smart_survival_architect` mod ID unchanged. Add repository-only open-source infrastructure, verify it locally, then create and configure `plantrungnampl/Terrainwright` from the clean local `main` branch before publishing the already-tested JAR as release `v1.0.0`.

**Tech Stack:** Git, GitHub CLI, GitHub Actions, Gradle 9.5.1 wrapper, Temurin Java 25, Fabric Loom, Markdown, YAML.

## Global Constraints

- Public product name and repository name are exactly `Terrainwright`; archive name remains `terrainwright`.
- Technical mod ID and data namespace remain `smart_survival_architect` for compatibility.
- License is MIT with copyright attributed to Terrainwright contributors.
- GitHub repository is public, default branch is `main`, and no pull request is created for the initial publication.
- CI runs `./gradlew clean test build --no-daemon` with Temurin Java 25.
- Release tag/title are `v1.0.0` / `Terrainwright v1.0.0`, with `terrainwright-1.0.0.jar` attached.
- No generated gameplay images, fake usage claims, star automation, or spam promotion.

---

### Task 1: Add MIT and community-facing repository files

**Files:**
- Create: `LICENSE`
- Create: `CONTRIBUTING.md`
- Create: `SECURITY.md`
- Create: `.github/ISSUE_TEMPLATE/bug_report.yml`
- Create: `.github/ISSUE_TEMPLATE/feature_request.yml`
- Create: `.github/ISSUE_TEMPLATE/config.yml`
- Modify: `README.md`
- Modify: `platform-fabric/src/main/resources/fabric.mod.json`
- Modify: `platform-fabric/src/test/java/dev/ssa/fabric/ModMetadataIdentityTest.java`

**Interfaces:**
- Consumes: verified Terrainwright V1 commands and documentation links already in `README.md`.
- Produces: truthful public landing page, MIT metadata, contribution/security policy, and structured issue intake.

- [ ] **Step 1: Add a failing metadata-license assertion**

Add this assertion to `exposesTerrainwrightAsTheProductName()`:

```java
assertTrue(metadata.contains("\"license\": \"MIT\""), metadata);
```

- [ ] **Step 2: Run the focused test and observe RED**

Run:

```powershell
.\gradlew.bat :platform-fabric:test --tests 'dev.ssa.fabric.ModMetadataIdentityTest' --no-daemon
```

Expected: `exposesTerrainwrightAsTheProductName()` fails because metadata still contains `All-Rights-Reserved`.

- [ ] **Step 3: Add the MIT and community surface**

Use the canonical MIT license text with `Copyright (c) 2026 Terrainwright contributors`. Change Fabric metadata to `"license": "MIT"`.

README structure must be:

```text
Terrainwright
badges: CI | release | license
one-paragraph value proposition
Why Terrainwright
V1 features and explicit non-goals
Requirements and installation
Play workflow
Build and verification
Contributing, security, and star/share call-to-action
```

`CONTRIBUTING.md` must require Java 25, the Gradle wrapper, focused tests during development, `clean test build` before submission, and tests for gameplay changes. `SECURITY.md` must direct vulnerabilities to GitHub private vulnerability reporting and forbid public exploit details before a fix. Issue forms must request Minecraft/Fabric/Terrainwright versions, reproduction steps, logs, and expected/actual behavior; their config must disable blank issues and link security reports to `/security/advisories/new`.

- [ ] **Step 4: Run the focused test and validate repository text**

Run:

```powershell
.\gradlew.bat :platform-fabric:test --tests 'dev.ssa.fabric.ModMetadataIdentityTest' --no-daemon
rg -n "All-Rights-Reserved|Smart Survival Architect" README.md CONTRIBUTING.md SECURITY.md platform-fabric/src/main/resources/fabric.mod.json .github
git diff --check
```

Expected: test passes; `rg` returns no matches; diff check exits zero.

### Task 2: Add reproducible GitHub CI

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: Gradle wrapper and Java 25 build contract.
- Produces: `build` job and downloadable `terrainwright-fabric-jar` workflow artifact.

- [ ] **Step 1: Create the CI workflow**

Use this job shape:

```yaml
name: CI
on:
  push:
    branches: [main]
  pull_request:
permissions:
  contents: read
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "25"
      - uses: gradle/actions/setup-gradle@v4
      - name: Build and test
        run: ./gradlew clean test build --no-daemon
      - uses: actions/upload-artifact@v4
        with:
          name: terrainwright-fabric-jar
          path: |
            platform-fabric/build/libs/terrainwright-*.jar
            !platform-fabric/build/libs/*-sources.jar
          if-no-files-found: error
```

- [ ] **Step 2: Run the complete local verification gate**

Run:

```powershell
.\gradlew.bat clean test build --no-daemon
git diff --check
```

Expected: build exits zero, all unit tests and 52 GameTests pass, and diff check exits zero.

### Task 3: Audit the public payload and commit it

**Files:**
- Stage only files from Tasks 1 and 2.

**Interfaces:**
- Consumes: locally verified launch files.
- Produces: one clean launch commit suitable for public `main`.

- [ ] **Step 1: Scan tracked content for obvious credentials**

Run:

```powershell
git grep -n -I -E "(gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|password[[:space:]]*[:=][[:space:]]*[^$<{[:space:]])"
```

Expected: no credential match. Test fixtures with non-secret words must be inspected rather than blindly removed.

- [ ] **Step 2: Review and commit**

Run:

```powershell
git status --short
git diff --check
git add LICENSE CONTRIBUTING.md SECURITY.md README.md platform-fabric/src/main/resources/fabric.mod.json platform-fabric/src/test/java/dev/ssa/fabric/ModMetadataIdentityTest.java .github
git diff --cached --check
git commit -m "docs: prepare Terrainwright for open source"
```

Expected: commit succeeds and the working tree is clean.

### Task 4: Create and configure the public GitHub repository

**Files:**
- External mutation: `github.com/plantrungnampl/Terrainwright`
- Local mutation: Git remote `origin` and `main` upstream tracking.

**Interfaces:**
- Consumes: clean committed local `main`.
- Produces: public GitHub repository whose `main` equals local HEAD, with GitHub topics and community/security features enabled.

- [ ] **Step 1: Reconfirm local and GitHub prerequisites**

Run:

```powershell
git status --porcelain
git branch --show-current
git remote -v
gh auth status
gh repo view plantrungnampl/Terrainwright --json nameWithOwner 2>$null
```

Expected: clean tree, branch `main`, no existing remote/repository, authenticated account `plantrungnampl`.

- [ ] **Step 2: Create and push the public repository**

Run:

```powershell
gh repo create Terrainwright --public --source=. --remote=origin --push --description "Terrain-aware survival architecture and autonomous construction for Minecraft Fabric."
```

Expected: repository creation and initial push both succeed.

- [ ] **Step 3: Configure discovery and community features**

Run:

```powershell
gh repo edit plantrungnampl/Terrainwright --enable-issues --enable-discussions --add-topic minecraft --add-topic minecraft-mod --add-topic fabric --add-topic fabricmc --add-topic procedural-generation --add-topic survival --add-topic building --add-topic java
gh api --method PUT repos/plantrungnampl/Terrainwright/private-vulnerability-reporting
```

Expected: repo settings update succeeds and private vulnerability reporting returns success.

- [ ] **Step 4: Prove visibility, tracking, and SHA equality**

Run:

```powershell
gh repo view plantrungnampl/Terrainwright --json nameWithOwner,isPrivate,visibility,url,defaultBranchRef
git rev-parse HEAD
git rev-parse origin/main
git ls-remote --heads origin main
git status -sb
```

Expected: `visibility` is `PUBLIC`, `isPrivate` is `false`, default branch is `main`, and all three SHAs are identical.

### Task 5: Publish and verify GitHub Release v1.0.0

**Files:**
- Use: `platform-fabric/build/libs/terrainwright-1.0.0.jar`
- Create temporarily outside tracked source: release notes file.
- External mutation: Git tag/release `v1.0.0` and one JAR asset.

**Interfaces:**
- Consumes: fresh locally built JAR and public GitHub repository.
- Produces: installable public V1 release with recorded digest.

- [ ] **Step 1: Rebuild and capture the exact digest**

Run:

```powershell
.\gradlew.bat clean test build --no-daemon
Get-FileHash platform-fabric/build/libs/terrainwright-1.0.0.jar -Algorithm SHA256
```

Expected: build exits zero and produces one runnable JAR plus one source JAR.

- [ ] **Step 2: Create release notes and publish**

Release notes must list Minecraft 26.2, Fabric Loader 0.19.3+, Fabric API 0.154.2+26.2, Java 25, installation on server and clients, the V1 workflow/features, explicit non-goals, technical mod ID `smart_survival_architect`, and the exact SHA-256.

Run:

```powershell
$releaseNotes = Join-Path ([System.IO.Path]::GetTempPath()) 'terrainwright-v1.0.0-release-notes.md'
gh release create v1.0.0 platform-fabric/build/libs/terrainwright-1.0.0.jar --repo plantrungnampl/Terrainwright --target main --title "Terrainwright v1.0.0" --notes-file $releaseNotes
```

Expected: public release and asset upload succeed.

- [ ] **Step 3: Verify release and CI**

Run:

```powershell
gh release view v1.0.0 --repo plantrungnampl/Terrainwright --json name,tagName,isDraft,isPrerelease,url,assets
gh run list --repo plantrungnampl/Terrainwright --workflow CI --limit 1 --json databaseId,status,conclusion,headSha,url
```

If CI is still queued or running, watch the returned run ID to completion:

```powershell
$runId = gh run list --repo plantrungnampl/Terrainwright --workflow CI --limit 1 --json databaseId --jq '.[0].databaseId'
gh run watch $runId --repo plantrungnampl/Terrainwright --exit-status
```

Expected: release is neither draft nor prerelease, contains exactly `terrainwright-1.0.0.jar`, and CI concludes `success` for the published HEAD.

- [ ] **Step 4: Final handoff verification**

Run:

```powershell
git status --porcelain
gh repo view plantrungnampl/Terrainwright --json stargazerCount,forkCount,issues,url
```

Expected: clean local tree and reachable public repository. Report the initial star count honestly; do not claim the 100-star target has already been achieved.
