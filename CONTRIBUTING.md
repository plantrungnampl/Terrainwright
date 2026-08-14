# Contributing to Terrainwright

Thanks for helping Terrainwright become a safer and more capable survival building mod.

## Before you start

- Use Minecraft 26.2, Fabric Loader 0.19.3+, Fabric API 0.154.2+26.2, and Java 25.
- Search existing issues before opening a new one.
- Keep changes inside the V1 scope described in the README unless an issue explicitly approves a broader design.
- Report security vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## Development workflow

1. Fork the repository and create a focused branch.
2. Use the checked-in Gradle wrapper; do not replace dependency versions incidentally.
3. Add or update a focused test before changing gameplay behavior.
4. Run the smallest relevant test while developing.
5. Before submitting, run the complete gate:

```bash
./gradlew clean test build --no-daemon
```

On Windows PowerShell, use `./gradlew.bat` instead of `./gradlew`.

The complete build includes the unit suites and Fabric GameTests. Pull requests should explain what changed, why it changed, player/server impact, and the verification performed.

## Code and data expectations

- Keep server authority, operation-intent persistence, permission checks, and material accounting intact.
- Do not add direct production world mutations outside the existing mutation executor.
- Preserve deterministic generation for the same requirements, terrain, style, and seed.
- Gameplay changes require regression coverage; documentation-only changes do not.
- Style palette contributions must follow [the palette format](docs/developer/style-palette-format.md) and use the canonical roles and capabilities.
- Avoid unrelated refactors or formatting churn.

## Good first contributions

Reproducible bug reports, focused tests, documentation corrections, translations, and compatible style-palette improvements are especially useful. For larger gameplay changes, open a feature request before investing in implementation.
