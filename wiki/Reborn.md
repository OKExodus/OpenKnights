# Reborn

**Definition.** A one-time step that swaps an eligible hero for a different base at the same grade, resetting its Rebirth progression to the start while keeping the hero's level, EXP, development, awaken, and Astral Power intact. Distinct from [[Rebirth]], which advances tier and level on a hero that has already changed base.

## How It Works Inside

The request names a hero and a target base. The server finds the `zhuansheng` row for the hero's current base, requiring it to be enabled and to name the requested target base, then checks the hero's grade and level against that row's thresholds; the leader hero, super-class heroes, and heroes currently assigned to exploring or mining are refused. Materials and Gold from the row's cost columns are consumed, the packed template becomes the target base at the hero's current grade, and full growth and base stats are recomputed for the new base through the shared stat model in [[Heroes]]. Rebirth Level, EXP, and Tier are set back to their starting values (level 1, no EXP, tier 1), and the Rebirth bonus attack and defense are recomputed for the new base at that reset state; any of these fields missing from the hero's stored list is added rather than left unset. The reply carries the hero's full updated field map, keeping the same UID so the client can replace the card in place.

## Opcodes

C95 requests the change; S54 carries the hero's full updated field map, with no count byte or reward. Routed through `Session.acquisitionRoute`'s Reborn action, alongside Rebirth Evolve and Fortify. See the [[Opcode Index]].

## Data Files

Reborn reads the same rebirth-eligibility rows as [[Rebirth]]; it does not have a table of its own beyond the `zhuansheng` base-swap rows and `hero.csv`'s class and leader markers. See the [[Data File Index]].

## Persistence

A Reborn commits one revision to the character's [[State Store]]: the hero's template, stats, growth, and reset Rebirth fields, plus the consumed materials and Gold. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Reborn.kt` (`planReborn`, `rebornRow`, `rebornCost`), routed through `Session.acquisitionRoute`.

## How It Was Deciphered

Reborn is reconstructed from the game's own code and tables rather than captured live: the target template formula, which fields carry over, the reset Rebirth values, and the refusal codes are structural policy, not observed traffic. Reproduced and pinned by the [[Method Differential Harness]], with native testing confirming the base swap and reset without spending live rebirth materials.

## See Also

- [[Heroes]], [[Rebirth]].
