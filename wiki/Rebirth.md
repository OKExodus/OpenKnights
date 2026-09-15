# Rebirth

**Definition.** A per-hero progression layered on top of level and evolution tier: Rebirth Tier advances by spending materials and Gold (Rebirth Evolve), and Rebirth Level advances within a tier by consuming dedicated EXP items (Rebirth Fortify), each tier raising the hero's bonus attack and defense.

## How It Works Inside

A hero qualifies for Rebirth only when its template's `hero.csv` row marks it as a Rebirth hero. Rebirth Evolve (C101) reads the `zhuansheng_jinhua` row for the hero's current tier, checks the hero's Rebirth Level and the character's level against that row's requirements, then consumes its listed materials, a class-specific stone, and Gold to raise Rebirth Tier by one; the following tier's row supplies the new level cap, and a hero already at its maximum tier is refused. Rebirth Fortify (C99) spends a list of class-4 EXP items in request order against `zhuansheng_exp`'s level curve, scaled by the hero's own EXP multiplier, up to the current tier's level cap; the leader hero cannot be Rebirth-fortified. Both paths recompute the hero's bonus attack and defense from its base attack/defense multiplier times Level + Tier + 3, and both pay Gold only after materials are confirmed available.

## Opcodes

C101 to S58 is Rebirth Evolve; C99 to S56 is Rebirth Fortify, sharing the Item Fortify result layout. See the [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `renascence.csv` (`zhuansheng_jinhua`, `zhuansheng_exp`) | Tier requirements, materials, Gold cost, level caps, and the EXP-to-next-level curve |
| `herorh.csv` | Hero rebirth eligibility and related base values |

See the [[Data File Index]].

## Persistence

Each Rebirth Evolve or Fortify commits one revision to the character's [[State Store]]: the hero's tier, level, EXP, bonus stats, consumed materials, and Gold. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Rebirth.kt` (`planEvolve`, `planFortify`), routed through `Session.acquisitionRoute`'s Rebirth Evolve and Rebirth Fortify actions.

## How It Was Deciphered

Rebirth Evolve and Fortify are reconstructed from the game's own tables and code rather than captured live; the reward and refusal shapes are drawn from the game's own conventions and labeled as structural policy where no capture exists. Native testing exercises both paths without ever spending live rebirth materials, per project policy. Reproduced and pinned by the [[Method Differential Harness]].

## See Also

- [[Heroes]], [[Rebirth Shop]], [[Reborn]].
