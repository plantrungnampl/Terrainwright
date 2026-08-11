# V1 Task 8 Candidate Scoring Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans`, `superpowers:test-driven-development`, and `superpowers:verification-before-completion` task-by-task.

**Goal:** Generate exactly eight deterministic procedural-house candidates, discard every candidate that fails hard validation, and return the highest-scoring valid Blueprint with auditable diagnostics.

**Architecture:** Keep the pipeline platform-neutral in `architect-core`. `ArchitectEngine` composes the existing graph, layout, palette, terrain, roof, opening, and hard-validation modules into bounded candidates derived from the request seed. `BlueprintScorer` normalizes six dimensions and applies the exact R2 weights; `ScoreBreakdown` includes `scenicOrientation` per the repository's canonical alignment decision. `GenerationResult` is a sealed success/failure result so an unsuitable site never produces a null or invalid preview.

**Scope boundary:** This slice generates within the supplied terrain snapshot and does not scan or relocate the site, access Minecraft registries/world state, enqueue construction, or render previews. Site scanning and platform orchestration remain Tasks 11 and 12.

**Tech Stack:** Java 25 immutable records/sealed interfaces, JUnit 5.14.3.

---

### Task 1: Lock exact scoring with red tests

**Files:**
- Create: `architect-core/src/test/java/dev/ssa/architect/scoring/BlueprintScorerTest.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/scoring/ScoreBreakdown.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/scoring/BlueprintScorer.java`

- [ ] Prove the exact weights: layout `.28`, terrain `.24`, style `.20`, accessibility `.12`, material efficiency `.10`, scenic orientation `.06`.
- [ ] Reject non-finite or out-of-range normalized dimensions.
- [ ] Preserve every component, including scenic orientation, in the immutable score breakdown.

### Task 2: Lock best-of-eight behavior with red tests

**Files:**
- Create: `architect-core/src/test/java/dev/ssa/architect/ArchitectEngineTest.java`

- [ ] Prove the same request seed produces the same Blueprint hash, score, and candidate diagnostics.
- [ ] Prove exactly eight candidates are attempted and the selected Blueprint is the highest-scoring valid candidate.
- [ ] Prove invalid candidates are excluded and an all-invalid run returns a typed failure with no preview.
- [ ] Prove multi-floor requests include validator-safe cross-floor stairs.

### Task 3: Implement bounded candidate composition

**Files:**
- Create: `architect-core/src/main/java/dev/ssa/architect/ArchitectEngine.java`
- Modify: `architect-core/src/main/java/dev/ssa/architect/blueprint/Blueprint.java`

- [ ] Derive eight independent deterministic candidate seeds from the request seed.
- [ ] Vary only bounded footprint/layout choices inside the supplied snapshot; do not perform site relocation.
- [ ] Assemble foundation, floors, walls, openings, stairs, roof, terrain plan, and dependency-safe build phases using resolved palette states.
- [ ] Run `BlueprintValidator` before scoring and retain stable rejection diagnostics for every invalid candidate.
- [ ] Select by total score with candidate index as the deterministic tie-breaker.
- [ ] Add a canonical SHA-256 Blueprint hash and persist the complete score breakdown.

### Task 4: Verify, review, and commit

- [ ] Run focused red/green tests, then `:architect-core:test`.
- [ ] Run `clean test build --no-daemon`, S1 layout verification, and `git diff --check`.
- [ ] Request independent review and commit only the Task 8 candidate-generation slice.
