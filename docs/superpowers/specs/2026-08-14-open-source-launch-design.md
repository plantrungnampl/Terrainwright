# Terrainwright Open-Source Launch Design

**Date:** 2026-08-14  
**Status:** Approved approach, pending written-spec review

## Goal

Publish the verified Terrainwright V1 checkout as a credible public GitHub repository under `plantrungnampl/Terrainwright`, with a downloadable `v1.0.0` JAR and the minimum community infrastructure needed to convert interested Minecraft players and developers into users, contributors, and stars.

Success means the public repository has `main` at the verified local HEAD, MIT licensing, a passing CI workflow, clear installation and contribution paths, useful GitHub topics, and a GitHub Release whose JAR matches the locally verified build artifact.

## Scope

The launch adds only repository and distribution infrastructure:

- MIT license with copyright attributed to Terrainwright contributors;
- a concise README with CI/release/license badges, value proposition, V1 feature boundaries, installation, usage, verification, contribution links, and a restrained star call-to-action;
- `CONTRIBUTING.md`, `SECURITY.md`, and structured bug/feature issue forms;
- a GitHub Actions workflow that builds and tests with Java 25 and uploads the Fabric JAR as a workflow artifact;
- a public GitHub repository named `Terrainwright`, with description, topics, issues, and Discussions enabled;
- an annotated `v1.0.0` GitHub Release containing `terrainwright-1.0.0.jar` and its SHA-256 digest in the release notes.

The launch does not add unverified gameplay claims, generated gameplay screenshots, paid promotion, automated star solicitation, or new mod functionality. The existing technical mod ID `smart_survival_architect` remains unchanged to preserve registry and world-data compatibility.

## Repository Surface

The README will lead with what Terrainwright does: terrain-aware house design and survival construction by a server-owned Builder using real materials from an explicitly linked chest. It will show the three shipped styles, safety/recovery behavior, supported Minecraft/Fabric/Java versions, exact install path, and links to the existing player/server/developer guides.

Community files will be deliberately small. `CONTRIBUTING.md` will document prerequisites, the build command, focused-test expectations, style-palette contribution rules, and the requirement that gameplay changes include tests. `SECURITY.md` will direct private vulnerability reports through GitHub's private vulnerability reporting rather than public issues. Issue forms will request reproducible versions, logs, and expected/actual behavior without collecting secrets.

## CI and Release Flow

The CI workflow will run on pushes to `main` and pull requests. It will use `actions/checkout@v7`, `actions/setup-java@v5`, `gradle/actions/setup-gradle@v6`, and `actions/upload-artifact@v7`, install Temurin Java 25, run `./gradlew clean test build --no-daemon`, and upload `platform-fabric/build/libs/terrainwright-*.jar` while excluding source JARs from the player artifact.

Before publishing, the local checkout must be clean, contain no obvious tracked credentials, and pass `git diff --check`. The repository will be created with `gh repo create Terrainwright --public --source=. --remote=origin --push`, then verified for `PUBLIC` visibility, default branch `main`, branch tracking, and exact remote SHA equality.

The release will be created only after the push is verified. Its uploaded JAR will be the artifact produced by the fresh local clean build, and the release notes will disclose the technical mod ID, supported versions, installation requirements, major V1 features, known scope exclusions, and SHA-256 digest.

## First-100-Star Strategy

The repository itself will optimize for honest conversion rather than artificial promotion:

- visitors can understand the mod and install a verified binary without cloning;
- CI, tests, safety documentation, and release checks provide credibility;
- topics make the project discoverable for Minecraft, Fabric, procedural generation, survival building, and Java searches;
- Discussions and issue forms give early users a low-friction feedback path;
- the README asks users who find Terrainwright useful to star it and share real builds.

External promotion is a later manual step: publish authentic gameplay media and posts only after real screenshots or video exist. Stars are never purchased, exchanged, botted, or requested through spam.

## Verification

The launch is complete only when all of the following are observed:

1. the local clean build exits zero and all GameTests pass;
2. the GitHub repository reports `visibility: PUBLIC`, `isPrivate: false`, and default branch `main`;
3. local `main`, `origin/main`, and the GitHub default-branch commit are identical;
4. CI configuration is present on the published branch;
5. Release `v1.0.0` is public and contains the expected JAR;
6. the uploaded JAR's local SHA-256 is recorded in the release notes;
7. the local working tree is clean after publication.
