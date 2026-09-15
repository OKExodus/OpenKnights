# Acquisition and Items

**Definition.** The item inventory and the ways an item leaves it: using an item, opening a reward box or a choose-box, and merging item stacks into a hero, a piece of gear, or another item.

## How It Works Inside

Every acquisition request is planned against an owned view of one committed revision: the character's item stacks across all three stores (initial, extra, acquired), its heroes, its equipment, and its role properties. A use request classifies the item's template row into a family (a resource grant, a direct grant of an item, hero, or equipment, a timed buff, or a reward box) and applies that family's effect, then consumes the item. A choose-box lets the player pick one option from a fixed list; a merge consumes a recipe's materials, which may be item stacks, currencies, or the merged stack itself, and grants the recipe's target. Reward boxes resolve per configured slot: a slot with one possible outcome is fixed, and a slot with several draws by weight. New items land in the bag matching their template, and a full bag refuses the grant with the item's own bag-full error. New heroes and new equipment also add their entries to the hero or equipment collection book and raise its achievement counter.

## Opcodes

C73 uses an item, C4099 resolves a choose-box option, and C803 (or C801 for a single-piece stack) merges. All three route through `Session.acquisitionRoute`. See the [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `item.csv` | Item templates: use effect, bag, stacking, and merge recipe pointer |
| `box.csv` | Reward box slots and weighted outcomes |
| `choosebox.csv` | Choose-box options |
| `starbox.csv` | A further box variant read the same way |

See the [[Data File Index]].

## Persistence

Every use, choose, or merge commits one revision to the character's [[State Store]]. See [[Transactions and Publishing]].

## Determinism

A reward box draw is randomness, seeded per request from the request bytes, the character's revision, and a fixed salt, under the labeled local box policy; a box with only fixed-outcome slots draws nothing. See [[Method Determinism]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Acquisition.kt`, routed from `AcquisitionRoutes.kt` and `Session.acquisitionRoute`.

## How It Was Deciphered

Item use, choose-box resolution, and merges were captured across their families, reproduced, and pinned by the [[Method Differential Harness]]. Box outcomes outside the supported column set are refused rather than guessed.

## See Also

- [[Summons]] and [[Shops]], other sources of items.
- [[Warehouse]] and [[Compose]].
