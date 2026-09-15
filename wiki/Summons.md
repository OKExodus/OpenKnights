# Summons

**Definition.** Drawing heroes from a summon lot with Pal Points, Summon Vouchers, or Special Vouchers, and reading back the summon report.

## How It Works Inside

Three lots are offered, each with single, 11-draw, and 120-draw rows. A request picks a row by lot and mode: the free single row when its cooldown has elapsed, else the character's unused first-draw row, else the normal row. Each draw first resolves a weighted group (a row's slots, one of which may rotate by a schedule keyed to the served time), then a weighted hero within that group. A row may also roll a bonus per drawn hero from a second weighted table, paying out gold or an item. The 120-draw mode additionally auto-refines any drawn hero at 3 stars or below, or any draw beyond the eleven the report can list, into refine materials instead of granting the hero. Drawn heroes are granted in order, each opening its own hero-add, collection-book, astral-skill, and activity frames; the free lot's cooldown timers are stored and reported back. The summon document also seeds fresh characters' free-timer countdowns from the character's creation time.

## Opcodes

C321 requests a summon; C1251 refines bench heroes for materials directly. Both route through `Session.acquisitionRoute`. See the [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `niudanhero.csv` | Summon lot rows: cost, currency, slots, bonus table |
| `xinniudan.csv` | Weighted hero groups a row's slots draw from |
| `xinniudan_xunhuan.csv` | The rotating group schedule for a row's fourth slot |

See the [[Data File Index]].

## Persistence

A summon commits one revision to the character's [[State Store]], including the updated summon document (free timers, first-draw flags). See [[Transactions and Publishing]].

## Determinism

Every group and hero draw is randomness, seeded per request from the request bytes, the character's revision, and the current served time, under the labeled local summon policy. See [[Method Determinism]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Summon.kt`, routed from `AcquisitionRoutes.kt`.

## How It Was Deciphered

Summon rows, their weighted draws, and the bonus and refine paths were captured across all three lots and modes, reproduced, and pinned by the [[Method Differential Harness]].

## See Also

- [[Acquisition and Items]], which grants and stores the drawn heroes and materials.
- [[Heroes]].
