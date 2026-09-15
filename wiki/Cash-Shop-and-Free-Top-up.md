# Cash Shop and Free Top-up

**Definition.** The offline edition's cash shop policy: tapping a recharge pack grants its contents without payment, under a labeled preservation policy, rather than performing any real transaction.

## How It Works Inside

The patched client posts a pack's goods and product identifiers, together with the login's session token, to the local sign-in service's recharge route. The service authenticates the token and finds that login's active live game session, then grants the pack on that session exactly as the game would after a real charge: a Diamond pack adds its Diamonds (doubled on the character's very first ever top-up), adds VIP EXP at a fixed rate per Diamond, and raises VIP level when EXP crosses a threshold, in turn raising the VIP AP and Energy buy allowances and ending any temporary VIP window early if the new level already covers it. A monthly-card pack instead activates that card's 30 days of daily claims and grants nothing up front. Every top-up is counted in a recharge ledger (a transaction count and a per-pack count) that the VIP Quest and other systems read to know whether the character has ever topped up. The reply frames are pushed to the live game session exactly as the game session would push them itself.

## Opcodes

There is no game opcode: the request arrives as a small HTTP call on the sign-in service, not a game frame, and its reply is pushed to the game session as ordinary frames (S1188 event ladders, S1088 the Diamond reward, S128 Diamond/VIP level/VIP EXP, the buy-count frames, and S1248 confirming completion).

## Persistence

A top-up commits one revision to the character's [[State Store]]: the recharge ledger, the role properties it changed, and, for a card pack, the monthly card document. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Recharge.kt`, with the sign-in route in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/session/AuthGateway.kt`.

## How It Was Deciphered

The recharge grant's frames, the first-purchase doubling, the VIP EXP and level progression, and the monthly-card activation path were captured, reproduced, and pinned by the [[Method Differential Harness]]. The free-top-up policy itself is an explicit, labeled operator decision for the offline edition, not a captured payment flow.

## See Also

- [[Accounts and Sign-in]], whose session token authorizes the top-up.
- [[Acquisition and Items]].
