# Activity Progress

**Definition.** The progress ladders inside a served event activity's rows, such as Diamond spent, Diamond recharged, a per-transaction pack counter, or a VIP-level threshold, kept current as other systems act and re-sent with one server message per change.

## How It Works Inside

An active event activity carries a list of rows, each a formatted title string, a reward, and a claim flag. Activity Progress recognizes the wording of known ladders (a "used X of T" or "refilled X of T" count, a VIP-level threshold, a per-transaction counter) by pattern, and when another system reports an amount, it rewrites the matching row's text and flag in place and re-sends the whole activity. It does not own any action: a Diamond spend advances the spend ladder, a real-money recharge advances the recharge ladder, its per-transaction counter, and the VIP-level ladder, and the VIP daily claim's own level check reuses that same VIP-level ladder. Claiming a completed row rewrites it to its claimed wording and, where the wording lines up, seeds the next tier's starting progress from what the claimed row already showed.

## Opcodes

| Opcode | Role |
| --- | --- |
| [[Opcode Index\|S1188]] | One activity's full row list, pushed after a change |

Claiming a ready row is the event-row claim opcode documented under [[Claims]].

## Data Files

None of its own; the activities it edits are the ones the schedule in `huodongbiao.csv` currently serves. See [[Events and Event Hall]] and the [[Data File Index]].

## Persistence

Row edits are written as part of whichever action's own transaction (a spend, a recharge, or a claim); Activity Progress commits no revision of its own. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/ActivityProgress.kt`, called from `Shops.kt`, `Recharge.kt`, and `Claims.kt`.

## How It Was Deciphered

The row wording and flag transitions were captured across spends, recharges, and claims and reproduced so the pushed rows match the reference byte for byte, pinned by the [[Method Differential Harness]].

## See Also

- [[Daily Missions]], [[Events and Event Hall]], [[Claims]].
