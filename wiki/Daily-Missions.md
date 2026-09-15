# Daily Missions

**Definition.** The day's activity counters, drawn from `dailyactivities.csv`, and the point-threshold gifts a character claims against them from `dailyactivties_gift.csv`.

## How It Works Inside

A per-character document (`daily_mission_state_v1`, keyed to the local day) holds one counter per activity id and a list of already-claimed gift ids. Other actions across the game advance a counter through a shared hook, and each counter is capped at its `dailyactivities.csv` row's maximum so a large action cannot overshoot it. The day's Daily Mission points are the sum of every counter, each capped and multiplied by its row's points value. A gift becomes claimable once that running total reaches its `dailyactivties_gift.csv` row's threshold, and each gift claims once per day. The whole document is replaced at the first touch of a new local day.

## Opcodes

| Opcode | Role |
| --- | --- |
| `C2539` / `S2912` | Query the day's counters and claimed gifts |
| `C2541` / `S2914` | Claim a threshold gift |

## Data Files

| Table | Role |
| --- | --- |
| `dailyactivities.csv` | Activity id, its counter cap, and its points per count |
| `dailyactivties_gift.csv` | Gift id, its points threshold, and its reward |

See the [[Data File Index]].

## Persistence

A gift claim commits one revision to the character's [[State Store]]. The counters themselves are advanced inside the shared `daily_counters` follow-up revision that runs right after whichever action fed them, alongside [[Quests and Bounty Board]] and [[Goals]]. See [[Transactions and Publishing]].

## Determinism

The day boundary is the device's local midnight, never a server clock. See [[Method Determinism]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Daily.kt` (the Daily Mission section: `missionView`, `missionCount`, `missionPoints`, `planMissionGift`), with the counter feed in `DailyHooks.kt`, routed from `Session.dailyRoute`.

## How It Was Deciphered

The query and claim messages were captured, reproduced, and pinned by the [[Method Differential Harness]].

## See Also

- [[Quests and Bounty Board]], [[Claims]], [[Goals]].
