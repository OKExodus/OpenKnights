# VIP and Monthly Cards

**Definition.** The VIP level's daily gift pack and its AP and Energy buy allowances, and the two recharge monthly cards' 30 days of daily claims.

## How It Works Inside

The character's VIP level gates a daily gift pack: one claim per local day, its contents (an item plus gold and Diamonds) read from the level's row. VIP level also carries a fixed AP-buy and Energy-buy allowance per day, shown as a left-of-maximum count with the next purchase's Diamond cost; a purchase spends Diamonds (advancing the Diamond-spent achievement and ladders), grants the resource, and counts against the day's allowance. Both the claim flag and the buy counts reset at local midnight, and a VIP level-up raises the day's remaining buy allowance to the new level's maximum while keeping what was already bought.

The two recharge monthly cards (activated by the free top-up, never bought outright) each track 30 days of ownership; each owned day the character can claim that day's reward row once, advancing the card's day counter, until the 30th day retires the card.

## Data Files

| Table | Role |
| --- | --- |
| `viplv.csv` | VIP level rows: daily gift pack contents, AP/Energy buy maxima, EXP thresholds |
| `yueka.csv` | Monthly card daily reward rows |
| `shop_vip.csv` | VIP-gated shop pricing and visibility |
| `vipcomeback.csv` | A VIP-tied returning-player table |

See the [[Data File Index]].

## Opcodes

C1025 claims the VIP daily gift; C1027 and C1029 buy AP and Energy; C1669 claims a monthly card's day. All route through `Session.acquisitionRoute`. See the [[Opcode Index]].

## Persistence

Each claim or buy commits one revision to the character's [[State Store]]: the VIP document's claim day, the S18 VIP block's counts, or the monthly card document's day and claim day. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Claims.kt`, with the level-up and reward paths in `Acquisition.kt`, routed from `AcquisitionRoutes.kt`.

## How It Was Deciphered

The daily gift claim, the AP and Energy buy costs and limits, and the monthly card claim path were captured, reproduced, and pinned by the [[Method Differential Harness]].

## See Also

- [[VIP Quest]], a separate VIP-tied achievement ladder.
- [[Cash Shop and Free Top-up]], which activates a monthly card.
- [[Claims]].
