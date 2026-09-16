# VIP Quest

**Definition.** The "Refill Reward" ladder of paired missions (a recharge or VIP-level mission, and a second collection mission) each unlocking the next row's reward.

## How It Works Inside

The character sits on one quest row at a time, tracked in a small document alongside the current row's own progress counters (items gathered toward its second mission, or a count of 10-summon draws for a mission that asks for those). A row becomes claimable once both its missions are satisfied: the first mission checks either that the character has ever topped up (the recharge ledger's transaction count) or that VIP level meets a threshold; the second checks a target item count, a hero of a given star and class held, or the tracked summon-draw count. The row's claimable state is mirrored into the character's activity tail, so the client's quest icon reflects it without a query. Claiming grants the row's rewards, advances to the next defined row, and resets its progress counters; every other acquisition transaction re-evaluates the current row afterward and pushes an update only when its claimable state changed.

The offline `/setvip` command refreshes the current row's eligibility while preserving the row, collection counters, and prior claims. A positive command grant satisfies the refill prerequisite without recording a purchase; all other mission requirements still apply. This is explicit offline policy, covered by the command integration tests. See [[Admin Commands]].

## Opcodes

C1125 claims the current row. Routed through `Session.acquisitionRoute`. See the [[Opcode Index]].

## Persistence

A claim commits one revision to the character's [[State Store]]: the quest document's row and progress counters, and the activity tail's row id and claimable flag. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/VipQuest.kt`, routed from `AcquisitionRoutes.kt`.

## How It Was Deciphered

The mission evaluation rules, the row-to-row progression, and the activity-tail mirroring were captured across mission kinds, reproduced, and pinned by the [[Method Differential Harness]].

## See Also

- [[VIP and Monthly Cards]], the source of the VIP-level mission.
- [[Quests and Bounty Board]].
