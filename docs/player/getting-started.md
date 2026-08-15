# Getting Started

## Install

Install the Terrainwright JAR, Fabric Loader, and Fabric API on the server and on every client. V1.0 targets Minecraft Java 26.2 and Java 25.

## Craft the two work blocks

Architect Table:

```text
P B P
C T C
W W W
```

- `P`: paper
- `B`: book
- `C`: copper ingot
- `T`: crafting table
- `W`: any vanilla planks

Builder Hut:

```text
L C L
P T P
P P P
```

- `L`: any vanilla logs
- `C`: vanilla chest
- `T`: crafting table
- `P`: any vanilla planks

## Prepare the Builder Hut

1. Place the Builder Hut. The server records the placing player as its owner.
2. Place a normal vanilla chest within 16 blocks of the Hut. Either half of a double chest may be selected.
3. Right-click the Hut and choose **Link / Relink Builder Chest**.
4. Close the screen, walk to the intended chest, and right-click it. The Hut screen reports `Linked` or an exact rejection reason.

The chest must be loaded, within the Hut's 16-block three-dimensional distance limit, and modifiable by the Hut owner. Barrels and modded containers are not accepted in V1. If a single chest is merged, a double chest is split, or either half changes, transfer pauses until the chest is explicitly relinked. An active healthy job cannot switch chests; relinking is available before confirmation or while the job is `PAUSED_NO_CHEST`.

## Design and confirm a house

1. Place and right-click the Architect Table.
2. Choose Medieval, Japanese, or Modern; a 9x11, 15x19, or 21x25 footprint; 1-3 floors; bedrooms; features; and entrance preference.
3. Choose **Select Site**, close the screen, and right-click the top face of the intended anchor block. The Table, player, and site must remain in the same dimension and within the server's 64-block survey limit.
4. Choose **Generate Preview**. The server scans the terrain, generates candidates, and sends the winning immutable Blueprint to the client ghost renderer.
5. Use **Regenerate**, **Rotate 90 degrees**, or **Reselect Site** if needed. Reselecting returns to the server-authoritative survey flow instead of moving the ghost only on the client.
6. Choose **Select Builder Hut**, close the screen, and right-click the owned Hut.
7. Choose **Confirm**. Confirmation succeeds only while the server preview, survey authority, world revision, permissions, Hut association, and linked Builder Chest are still valid.

## Supply and control the build

Put the missing items listed by the Hut screen into the linked chest. The Builder transfers a real bounded batch into its own inventory before placing blocks. Required missing materials pause the job; optional decoration may be skipped.

The Hut screen exposes Pause, Resume, Stop, and Safe Undo:

- **Pause/Resume** controls future scheduling through the server.
- **Stop** drains current durable work and leaves completed construction in place.
- **Safe Undo** is available for stopped or completed jobs. It reverts only cells still matching the job's own after-state. External edits and protected cells are preserved, and Undo does not refund terrain or construction drops.

If the UI reports a missing chest, obstruction, unloaded chunk, lost Builder, protected area, or conflict, follow the displayed recovery guidance. The mod does not teleport Builders or force-load distant chunks.
