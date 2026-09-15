# Achievements

**Definition.** The medal steps of `achieve.csv`, completed automatically as the save's counters and combat actions reach their targets, and the separate `questmedal.csv` milestones that scale Battle Engine stats by the character's story-quest point total.

## How It Works Inside

Each character's achievement entries, one per kind, carry a step and a progress value in the save's subsystems section. A fixed set of "live" kinds complete offline: two counters this module keeps itself (hero and gear evolve) and progress values other systems already maintain (the quest and bounty claims, the player level, and more). Whenever a committed action's `daily_counters` follow-up runs, every step whose target the progress has reached completes, in order, and a medal-reward mail is queued for the recipient, paying that step's Gold and Diamond columns; any kind that changed reports its new step and progress, and the character's total achievement points are added as one role update.

Separately, `questmedal.csv` reads the story-quest point total (the `quest.csv` and Bounty Board points, banked outside this module) and adds a typed stat bonus for every row whose threshold the total has passed. This is a combat stat feed, not a claim, and carries no mail or medal of its own.

## Opcodes

No opcode of its own. The completion and achievement-row frames ride the reply of whichever action fed the counter, and the medal mail's brief is appended after the character's commit.

## Data Files

| Table | Role |
| --- | --- |
| `achieve.csv` | Medal steps by kind: target, points, and the Gold/Diamond mail reward |
| `questmedal.csv` | Quest-point thresholds and their stat bonuses |

See the [[Data File Index]].

## Persistence

Achievement progress and completions ride the shared `daily_counters` revision of the triggering action; the medal mail is a separate world-level write to the mail store after that commit. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Achievements.kt` (`evaluate`, `deliverMails`), fed by `DailyHooks.kt`; the `questmedal.csv` stat feed is in `BattleStats.kt`.

## How It Was Deciphered

The completion and mail sequence were captured across multiple triggering actions and reproduced so the saved progress and the medal mail match the reference, pinned by the [[Method Differential Harness]].

## See Also

- [[Goals]], [[Prestige]], [[Battle Engine]].
