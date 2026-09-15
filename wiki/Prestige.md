# Prestige

**Definition.** Automatically raising the character's title to the highest rank its Reputation and campaign stars qualify for, and keeping the Reputation achievement's displayed value in step with the character's current Reputation.

## How It Works Inside

Prestige owns no request of its own; it rides along inside whichever system just changed Reputation or campaign stars. `titleFor` scans `title.csv` for the highest title whose required Reputation and star thresholds are both met by the character's current values, and `promote` raises the character's title role field to that rank, never lowering it, emitting a role-update frame only when a strictly higher title is reached. It is called after Arena results, after Campaign stage advances, and during the daily rollover, so a title promotion always appears bundled with the frames of whichever of those actions triggered it. Separately, `achievementFrame` keeps the character's Reputation-tracking achievement entry current: whenever that entry is rebuilt, it writes the character's present Reputation, capped to the field's 32-bit wire width, into the entry before it is sent.

## Opcodes

Prestige has no opcode of its own. Its title-promotion and achievement-sync frames are folded into the Arena, Campaign, and daily-rollover replies that triggered them. See the [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `title.csv` | Title ranks and their required Reputation and campaign-star thresholds |

See the [[Data File Index]].

## Persistence

A title promotion or achievement sync folds into whatever transaction invoked it and commits through the character's [[State Store]] alongside that transaction's own changes. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Prestige.kt` (`titleFor`, `promote`, `achievementFrame`).

## How It Was Deciphered

Title thresholds and the never-lowers-the-title promotion rule were captured across Reputation and star combinations, reproduced, and pinned by the [[Method Differential Harness]].

## See Also

- [[Achievements]], [[Heroes]].
