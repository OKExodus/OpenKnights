# Quests and Bounty Board

**Definition.** The story quest chain that unlocks and claims one-time rewards as the character progresses, and the Bounty Board, a fixed roster of repeatable tasks redrawn every local day.

## How It Works Inside

A character's story quest document holds running, ready, and claimed rows, seeded once and then unlocked by a rule that checks each row's prerequisite, minimum level, and story flag. A row completes from a counter fed by another action's `daily_counters` follow-up, from owned-state facts re-read from the save (hero or gear level and star, worn gear, cleared stages, held items), or from a level or building/technology threshold. Claiming a ready quest grants EXP, Gold, and Honor by the quest's reward mode, plus any item or gear reward, opens its successors, and advances the quest achievement kind.

The Bounty Board holds the same fixed set of bounty quest ids every day, redrawn at the first local-day touch with a star rating drawn by a seeded random choice. A bounty is accepted against a daily limit, can auto-complete over a timer or be expedited with Diamonds, has its stars rerolled for Diamonds or a free daily reroll, and pays out scaled by its star tier on claim. Both ladders add to one shared quest-points total other systems read.

## Opcodes

| Opcode | Role |
| --- | --- |
| [[Opcode Index\|C261]] | Claim a ready story quest |
| [[Opcode Index\|C259]] | Accept a bounty |
| [[Opcode Index\|C263]] | Quit a bounty |
| [[Opcode Index\|C265]] | Refresh the board |
| [[Opcode Index\|C269]] | Expedite an auto-completing bounty |
| [[Opcode Index\|C271]] | Reroll a bounty's stars |
| [[Opcode Index\|C273]] | Start auto-completion |
| [[Opcode Index\|C275]] | Report an auto-completion's timer end |

## Data Files

| Table | Role |
| --- | --- |
| `quest.csv` | Story quest rows: prerequisites, unlock rule, reward |
| `quest_bounty.csv` | Bounty star weights and their reward bonuses |

See the [[Data File Index]].

## Persistence

A claim or board action commits one revision to the character's [[State Store]]. Counter-driven progress rides the shared `daily_counters` revision instead. See [[Transactions and Publishing]].

## Determinism

The board's daily redraw and star rerolls use a seeded random generator keyed to the character and the local day; the redraw itself turns at local midnight. See [[Method Determinism]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Quests.kt`, routed from `Session.dailyRoute`.

## How It Was Deciphered

The claim and board messages were captured across their actions and reproduced so the saved rows and the grants match the reference, pinned by the [[Method Differential Harness]].

## See Also

- [[Daily Missions]], [[Goals]].
