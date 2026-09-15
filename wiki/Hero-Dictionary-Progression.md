# Hero Dictionary Progression

**Definition.** The shared arithmetic and row-selection helpers behind the Hero Dictionary screen: bounded integer parsing of table cells, binary32 rounding matched to the client's float model, packed hero id decoding, and the evolve-row selectors that pick a hero's evolution-cap and potential-rate rows. Every hero-card and hero-identity system in the reconstruction is built on these primitives.

## How It Works Inside

`nativeInt` reproduces the client's bounded `atoi` over a table cell: it reads an optional sign and a run of decimal digits and refuses a result outside the signed 32-bit range, exactly as the client's own cell parsing does. `f32` and `truncU32` round and truncate through binary32 the way the native code does, so multi-step formulas (EXP awards, upgrade costs, stat permilles) land on the same value the client would compute. `unpackHeroId` splits a packed hero template into its base id, grade, hundreds digit, and super class; it backs identity decisions everywhere a template is read, including [[Change Job]]'s target-base resolution. `configuredStates` walks the `jinhua` or `jinhuazhujue` table for a hero's category (and, for an ordinary hero, its group) to list every evolution row the client's own cap selector accepts, sorted by grade; `potentialRows` gathers the earlier-grade rows a hero's potential rate sums over. The remaining functions settle bounded hero and equipment EXP and upgrade costs against these same rows. The client's own preview-only functions, the ones that render basic-attribute, EXP, and development previews for display, are not ported, since nothing on a server path calls them.

## Opcodes

This module answers no request directly; its functions are called from inside [[Hero Fortify]], [[Hero Evolution]], [[Change Job]], and the other hero-card systems whenever they need a hero's identity decoded or its evolution-row set resolved. See the [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `jinhua`, `jinhuazhujue` | Per-hero evolution rows: grade, cap, and (for ordinary heroes) group, read by the evolve-row selectors |

The Hero Dictionary's own listing table, `tujian.csv`, the collection book, is read by the neighboring collection and album-reward systems that track which heroes a character owns and award milestones as the collection grows; those systems build on the same packed hero identity this module decodes. See the [[Data File Index]].

## Persistence

This module holds no state of its own. Every field it helps compute is written and committed by the calling system: see [[Heroes]] for the shared stat model and [[Transactions and Publishing]] for how a change reaches the character's [[State Store]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/HeroDictionaryProgression.kt` (`nativeInt`, `f32`, `truncU32`, `unpackHeroId`, `configuredStates`, `potentialRows`, `expForLevel`, `consumedHeroExp`, `consumedEquipmentExp`, `upgradeInstanceCost`).

## How It Was Deciphered

The bounded parsing, float rounding, and evolve-row selection were captured across many heroes, grades, and tiers, reproduced, and pinned by the [[Method Differential Harness]]; the float-rounding functions are also checked by native testing against the client's own binary32 arithmetic.

## See Also

- [[Heroes]], [[Achievements]], [[Hero Fortify]], [[Hero Evolution]], [[Change Job]].
