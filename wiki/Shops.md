# Shops

**Definition.** The catalog shops, their per-day and lifetime purchase counts, the Lucky Shop's cyclical pools, and the Fate Store roulette.

## How It Works Inside

A shop list serves the server-authored catalog's records for the requested shop type, filtered to what is on sale and not withheld, alongside the character's today and total purchase counts. A buy checks VIP level (including a temporary VIP window), a VIP ceiling above which the commodity is no longer offered, and the daily or lifetime purchase limit before charging the catalog price and granting the goods; Diamond purchases also advance the cumulative Diamond-spent achievement and any active Diamond-spending event ladder.

The Lucky Shop serves a cycle of entries drawn from the catalog's observed pools; a character's own paid refresh (one Fate Voucher) replaces the cycle's pools with a fresh draw and clears its bought flags. Buying an entry marks it bought for the rest of the cycle. The Fate Store roulette spends a class-specific coupon for a fixed number of spins across the served wheel's slots, each spin granting the slot's item and adding to a shared event score.

## Opcodes

C1057 lists a shop; C75 buys. C2725 serves or refreshes the Lucky Shop, C2723 buys from it. C641 spins the roulette. All route through `Session.acquisitionRoute`. See the [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `shop_position.csv` | Rebirth Event Hall shop slot positions (shared with [[Rebirth Shop]] roll logic) |
| `shop_refresh.csv` | Refresh cadence and pricing per shop |
| `tongyong_shop.csv` | General shop commodity table |

See the [[Data File Index]].

## Persistence

A buy, a Lucky Shop refresh, or a roulette spin commits one revision to the character's [[State Store]], carrying the shop's today and total counts or the Lucky Shop's cycle document forward. See [[Transactions and Publishing]].

## Determinism

The Lucky Shop's paid refresh draws its cycle's pools, and the roulette draws its slots, both randomness seeded per request from the request bytes, the character's revision, and the current time, under labeled local policies. See [[Method Determinism]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Shops.kt`, routed from `AcquisitionRoutes.kt`.

## How It Was Deciphered

Shop listings, buys, Lucky Shop cycles and refreshes, and roulette spins were captured, reproduced, and pinned by the [[Method Differential Harness]]. The device clock's local midnight governs daily counts; the Lucky Shop's own cycle is a fixed period from a catalog anchor.

## See Also

- [[VIP and Monthly Cards]], whose level gates shop access.
- [[Acquisition and Items]].
