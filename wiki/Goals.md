# Goals

**Definition.** The seven-day target event ("Mubiao"): goals that open in groups on the character's own day count since creation, and that claim one-time rewards, some of which reopen the story quest unlock rule.

## How It Works Inside

A per-character document is seeded once from the character's own seed frame and tracks each goal row as running, ready, or claimed. A goal's kind decides how it advances: a counter kind fed by the same action events as the daily counters (summon, refine, evolve, and a handful of goal-only events such as check-in), an owned-state kind re-read from the save (player level, hero level, hero tier, worn runes, Power), or a "complete all" kind that finishes once every other goal of its day is done. New groups of goals open once the character's local day index, counted from creation, and level both clear the group's gate. Claiming a ready goal grants its item or hero reward and can push updates for any other row the same evaluation pass changed, including a re-run of the story quest unlock rule.

## Opcodes

| Opcode | Role |
| --- | --- |
| [[Opcode Index\|C2657]] | Claim a ready goal |
| [[Opcode Index\|S3108]] | The full goal row list (query, and after a claim) |
| [[Opcode Index\|S3106]] | One updated goal row |
| [[Opcode Index\|S3104]] | The claim's Reward |

## Data Files

| Table | Role |
| --- | --- |
| `mubiao.csv` | The day groups: which day and level open which goal type |
| `mubiaoquest.csv` | Individual goal rows: kind, target, and reward |

See the [[Data File Index]].

## Persistence

A claim commits one revision to the character's [[State Store]]. Login and hook-driven advances ride the shared `daily_counters` follow-up revision alongside [[Achievements]]. See [[Transactions and Publishing]].

## Determinism

A goal's day index counts local midnights since the character's creation, not a server clock. See [[Method Determinism]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Goals.kt`, routed from `Session.goalsRoute`.

## How It Was Deciphered

The seed, the claim, and the day-opening rule were captured and reproduced so the saved rows match the reference across days, pinned by the [[Method Differential Harness]].

## See Also

- [[Achievements]], [[Daily Missions]].
