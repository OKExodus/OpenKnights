# Rebirth Shop

**Definition.** The three Event Hall shops (Sprite, Fame, and Jewel) that sell rebirth-currency goods from a daily rolled list of six positions each.

## How It Works Inside

Each shop rolls six positions once per local day, one good per position from that position's eligible slot (the highest VIP-gated slot the character qualifies for, or the lowest slot if none apply), weighted among that slot's goods. Each rolled good also carries a fixed-rate chance of being priced in Diamonds instead of its normal soul currency, decided per goods kind. A character's very first list, rather than being rolled, is taken from the day-zero seed payload when that seed is well formed; every later day rolls fresh. A refresh re-rolls one shop's six positions; a character gets a handful of free refreshes per day from their VIP level, then pays Diamonds for more, up to the VIP maximum. Buying a position spends its price, grants the good by its kind (item, hero, gear, or jewelry), and marks the position sold for the rest of the day.

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/RebirthShop.kt`, routed from `AcquisitionRoutes.kt`.

## Persistence

A refresh or a buy commits one revision to the character's [[State Store]], carrying the day's shop document (today's rolled entries, refreshes used, sold flags) forward. See [[Transactions and Publishing]].

## Determinism

Each day's roll, and each paid refresh's re-roll, is randomness seeded from the character's own key, the local day, and the shop, so the same day always rolls the same list for that character. The Diamond-pricing chance per good is drawn from the same seeded stream. See [[Method Determinism]].

## How It Was Deciphered

The daily roll, the free and paid refresh limits, and the buy-and-grant path were captured across all three shops, reproduced, and pinned by the [[Method Differential Harness]].

## See Also

- [[Rebirth]], the source of the shops' soul currencies.
- [[Shops]].
