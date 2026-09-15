# Rename

**Definition.** Changing a character's name by spending a Rename Card, with the game's own naming rules enforced.

## How It Works Inside

Using the Rename Card item opens the client's rename panel directly, without going through the general item-use opcode; the panel then sends the new name on its own opcode, and the server is what consumes the card. A request is refused outright, without spending the card, if the name fails the same normalization and validation rules a freshly created character's name must pass. Otherwise the server spends the card, records the old name for an undo, and applies the new one across the world directory, the account registry, and the character's saved state together: the world's name and name-key change first, and if the save transaction that follows then fails, the world change is rolled back so the two never drift apart. Success writes the new name into the character's own name role property.

## Opcodes

C1569 sends the new name. Routed through `Session.renameRoute`. See the [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `rolename.csv` | Naming rules (the same table character creation validates against) |

See the [[Data File Index]].

## Persistence

A successful rename commits one revision to the character's [[State Store]] (the name role property) alongside the world directory and account registry updates; a save failure undoes the world-side rename rather than leaving the two out of step. See [[Transactions and Publishing]] and [[State Store]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Rename.kt`, routed from `Session.renameRoute`.

## How It Was Deciphered

The card-consumption path, the shared naming rules with character creation, and the world/save undo-on-failure ordering were captured and reproduced, and are pinned by the [[Method Differential Harness]].

## See Also

- [[Character Creation]], which the naming rules are shared with.
- [[Acquisition and Items]], the source of the Rename Card.
