# Heroes

**Definition.** The roster of heroes a character owns, and the bounded base-attribute model that every hero-card system (Fortify, Evolution, Ascension, Power Up, Astral Power) validates an owned hero against before it will touch it.

## How It Works Inside

An owned hero is a set of typed dynamic properties keyed by field id: UID, packed template, level, EXP, four base stats and their four growth values, a GrowType marker, a guiding-hero flag, four development values, five reborn fields, and an awaken level. The character's heroes array holds one field list per hero; `SecondaryTeam.ownedHeroes` turns it into the `{uid: fields}` view every hero-card planner reads.

The packed template encodes identity: a base hero id, a hundreds digit for its super-class slot, and a grade for its evolution tier. `HeroStats.resolveProfile` is the shared gate: it recomputes each hero's full growth from `hero.csv`'s raw growth and potential rate at the hero's current grade, builds a per-stat permille (10000 plus a super-class bonus from property 533 plus any active awaken-slot bonus), and requires the wire stats equal `floor(full_grow * (level + grade + 3) * permille / 10000) + dev` exactly. A hero whose stats the model cannot reproduce, or that carries a nonzero GrowType, or that is a guiding hero, is rejected before any system acts on it. This one model is what lets Fortify, Evolution, Ascension, and Power Up recompute a hero's stats after their own change without duplicating the arithmetic.

## Opcodes

The base roster has no opcode of its own; heroes are touched through the hero-card systems, several of which are routed through `Session.heroCardRoute` (Power Up open/train/save, Astral Power, Ascension). See the [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `hero.csv` | Per-base-hero definition: raw growth, potential rate, grade caps, super-class and leader markers |
| `heroexp.csv` | The level-indexed EXP curve consulted when settling awarded EXP |
| `heromodel.csv` | Hero model/visual reference, named here as an interoperability fact |

See the [[Data File Index]].

## Persistence

Every hero-card transaction rewrites the affected hero's field list in place and commits one revision to the character's [[State Store]]. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/.../game/HeroStats.kt`, with the transactions in `server/store/HeroCardTransactions.kt`.

## How It Was Deciphered

The owned-hero field layout and the bounded stat model were captured across many heroes and tiers, reproduced, and pinned by the [[Method Differential Harness]].

## See Also

- [[Formation]], [[Hero Evolution]], [[Hero Fortify]], [[Summons]], [[State Store]].
