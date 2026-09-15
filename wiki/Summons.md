# Summons

**Definition.** Drawing heroes from a summon lot with Pal Points, Summon Vouchers, or Special Vouchers, and reading back the summon report.

## How It Works Inside

Three lots are offered with single and 11-draw rows. The Normal lot also offers 120 draws. A request picks a row by lot and mode: the free single row when its cooldown has elapsed, else the character's unused first-draw row, else the normal row. Each draw first resolves a weighted group (a row's slots, one of which may rotate by a schedule keyed to the served time), then a weighted hero within that group. A row may also roll a bonus per drawn hero from a second weighted table, paying out gold or an item. The 120-draw mode additionally auto-refines any drawn hero at 3 stars or below, or any draw beyond the eleven the report can list, into refine materials instead of granting the hero. Drawn heroes are granted in order, each opening its own hero-add, collection-book, astral-skill, and activity frames; the free lot's cooldown timers are stored and reported back. The summon document also seeds fresh characters' free-timer countdowns from the character's creation time.

## Opcodes

C321 requests a summon; C1251 refines bench heroes for materials directly. Both route through `Session.acquisitionRoute`. See the [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `xinniudan.csv` | Summon lot rows: cost, currency, slots, bonus table |
| `niudanhero.csv` | Weighted hero groups a row's slots draw from |
| `xinniudan_xunhuan.csv` | The rotating group schedule for a row's fourth slot |

See the [[Data File Index]].

## Persistence

A summon commits one revision to the character's [[State Store]], including the updated summon document (free timers, first-draw flags). See [[Transactions and Publishing]].

## Determinism

Every group and hero draw is randomness, seeded per request from the request bytes, the character's revision, and the current served time, under the labeled local summon policy. See [[Method Determinism]].

## Optional Supreme Summon Pool

The release package may include `release-data/supreme-summon-pool.json`. When present, the service applies it to dedicated Supreme summon groups. Other banners keep their original groups, and tests without the policy retain baseline behavior. Four-, five-, and six-star groups have probabilities of 30%, 60%, and 10%. Within each group, a standard hero has weight 100 and a limited hero has weight 25. The separate bonus roll grants one seven-star piece (19.8%), one eight-star piece (7.98%), one exchange voucher (0.5%), or Gold (71.72%). Every drawn hero also grants five Lucky Diamonds. First-ever hero guarantees, draw costs, and free cooldowns remain unchanged.

The on-device patcher rewrites the two summon tables for the client preview and preserves their originals under `assets/openknights/original-tables/`. The server verifies the originals against the supported APK hashes and applies the same policy. The policy file contains the selected template IDs and weights and remains private release data; the public repository contains the loader and synthetic tests.

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Summon.kt`, routed from `AcquisitionRoutes.kt`.

## How It Was Deciphered

Summon rows, their weighted draws, and the bonus and refine paths were captured across all three lots and modes, reproduced, and pinned by the [[Method Differential Harness]].

## See Also

- [[Acquisition and Items]], which grants and stores the drawn heroes and materials.
- [[Heroes]].
