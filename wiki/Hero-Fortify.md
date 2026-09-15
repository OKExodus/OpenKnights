# Hero Fortify

**Definition.** Raising a hero's level within its current tier by consuming other owned hero cards, or dedicated EXP items, as material.

## How It Works Inside

A Fortify request names a target hero and one or more materials. In the ordinary, whole-card path, every material must be a distinct level-1 hero card with no residual EXP, sitting on the bench and outside deployment or assignment; the target passes through the shared bounded stat model in [[Heroes]] before anything is planned. Each material's EXP value comes from `hero.csv`'s per-template base EXP, and the total awarded EXP is settled against the target one level at a time using `heroexp.csv`'s level curve, stopping short of the hero's configured level cap; a settlement that would reach the cap is refused rather than guessed at. Stats and growth are recomputed at the new level through the same bounded model, and the Gold cost is derived from the summed material EXP and the target's current level.

A second path spends dedicated EXP items instead of whole cards: the request names item templates resolved through `qianghua_itemexp.csv`, and the awarded EXP settles against the same `heroexp.csv` curve and hero cap. Gear Fortify (C81) is the equipment counterpart and is documented on [[Equipment Evolution]] and [[Gear and Equipment]]; it shares the wire layout and the level-settlement shape but reads gear's own catalog rows.

## Opcodes

The ordinary hero-card request and result are routed from `Session.heroFortifyRoute` (C69 / S48). See the [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `hero.csv` | Material and target base EXP, grow, and cap values |
| `heroexp.csv` | The level-indexed EXP curve the settlement consumes |
| `qianghua_itemexp.csv` | EXP-item Fortify's item-to-value resolution |

See the [[Data File Index]].

## Persistence

A Fortify commits one revision to the character's [[State Store]]: the target's fields, the consumed materials, and Gold. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/.../game/HeroFortify.kt`, with the transactions in `server/store/HeroFortifyTransactions.kt`.

## How It Was Deciphered

Hero Fortify was captured across single and multi-material requests, at ordinary levels and near the level cap, and reproduced so the settled level, stats, and Gold cost match the reference. It is pinned by the [[Method Differential Harness]].

## See Also

- [[Heroes]], [[Hero Evolution]], [[Acquisition and Items]].
