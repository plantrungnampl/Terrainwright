# Changelog

## 1.0.2 - 2026-08-15

- Hardened preview confirmation so only unchanged, server-authoritative previews can start jobs.
- Added dimension-aware permission checks for previews, chest links, block mutations, material transfers, and Safe Undo.
- Fixed interrupted Builder spawn and replacement recovery, including cleanup of stranded entities.
- Fixed Architect Table workflow state across dimensions and site or Hut reselection.
- Added confirmation feedback and focused regression coverage for the hardened workflows.

## 1.0.1 - 2026-08-14

- Fixed Creative tab/search behavior.
- Redesigned the Architect screen.
- Redesigned the Builder Hut screen.
- Validated responsive layouts and accessibility behavior.

## 1.0.0 - 2026-08-14

- Added deterministic Medieval, Japanese, and Modern house generation for the Small, Medium, and Large UI footprints.
- Added server-authoritative site survey, ghost preview, rotation, movement, Hut selection, and confirmation.
- Added craftable/placeable Architect Table and Builder Hut blocks with Survival recipes, drops, models, and placement ownership.
- Added explicit vanilla single/double-chest linking and relinking with ownership, range, topology, and permission validation.
- Added one real Builder NPC per Hut with physical material transfer, bounded navigation recovery, temporary scaffolding, and conflict handling.
- Added durable job, Builder lifecycle, operation-intent, restart reconciliation, chunk suspension, Stop, and Safe Undo behavior.
- Added revisioned Builder Hut progress/control replication, optional in-memory debug metrics, guarded style-palette overrides, property tests, and release GameTests.
