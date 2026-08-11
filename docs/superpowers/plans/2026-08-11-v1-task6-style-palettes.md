# V1 Task 6 Style Packs and Palette Resolution Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans`, `superpowers:test-driven-development`, and `superpowers:verification-before-completion` task-by-task.

**Goal:** Define three geometry-distinct built-in styles and resolve their semantic material roles to compatible platform-neutral block states with deterministic vanilla fallback.

**Architecture:** `StylePack` is immutable declarative data for proportion, foundation, roof, opening, and palette rules. Each palette candidate carries a `BlockStateSpec` plus canonical capabilities. `BlockCapabilityRegistry` is a detached registry snapshot port. `PaletteResolver` evaluates explicit overrides in declared order, skips missing/incompatible candidates, then tries the bundled style fallback list; it never scans arbitrary registry blocks.

**Scope boundary:** This slice defines trusted style data and resolution only. It does not load data packs, call Minecraft registries, generate roof blocks, or validate a complete Blueprint.

**Tech Stack:** Java 25 immutable interfaces/records/enums, JUnit 5.14.3.

---

### Task 1: Lock canonical capability vocabulary and fallback behavior with red tests

**Files:**
- Create: `architect-core/src/test/java/dev/ssa/architect/material/PaletteResolverTest.java`

- [ ] Lock the exact 11 R2 capability identifiers and reject aliases such as `FULL_BLOCK`.
- [ ] Prove missing and incompatible overrides are skipped in declared order.
- [ ] Prove the first compatible override wins and exact block-state properties survive resolution.
- [ ] Prove a missing modded roof falls back to the bundled compatible vanilla roof.
- [ ] Return `Optional.empty()` when neither override nor mandatory fallback exists in the registry.

### Task 2: Implement capability snapshot and resolver

**Files:**
- Create: `architect-core/src/main/java/dev/ssa/architect/material/BlockCapability.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/material/BlockCapabilityRegistry.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/material/PaletteResolver.java`

- [ ] Keep registry lookup platform-neutral and capable of distinguishing a missing block from a block with no required capabilities.
- [ ] Deep-copy override maps/lists and preserve declared priority.
- [ ] Check candidate-required capabilities using set containment; never infer from block names.

### Task 3: Lock three geometry identities with red tests

**Files:**
- Create: `architect-core/src/test/java/dev/ssa/architect/style/StyleIdentityTest.java`

- [ ] Require exact built-in namespaced IDs and positive versions.
- [ ] Prove Medieval, Japanese, and Modern differ in roof family/overhang, foundation, glazing/openings, and proportion/open-plan bias.
- [ ] Require complete fallback coverage for all 18 canonical material roles.
- [ ] Prove style and nested palette collections are immutable.

### Task 4: Implement immutable built-in style packs

**Files:**
- Create: `architect-core/src/main/java/dev/ssa/architect/style/StylePack.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/style/MedievalStyle.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/style/JapaneseStyle.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/style/ModernStyle.java`
- Create: `architect-core/src/main/java/dev/ssa/architect/style/BuiltinStylePalettes.java`

- [ ] Validate numeric rule bounds and complete canonical palette coverage in `StylePack` helpers.
- [ ] Keep exact vanilla block choices replaceable data while locking candidate order and capability requirements.
- [ ] Do not share one geometry rule object across the three styles.

### Task 5: Verify and commit

- [ ] Run focused red/green tests, then `:architect-core:test`.
- [ ] Run `clean test build --no-daemon`, S1 layout verification, and `git diff --check`.
- [ ] Request independent review and commit only the Task 6 style/palette slice.
