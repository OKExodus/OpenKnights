# Equipment Evolution

**Definition.** Advancing gear and jewelry to higher tiers by spending materials, which raises the item's stats and, at thresholds, changes what it is.

## How It Works Inside

Gear and jewelry both progress along an evolution track. The player spends the required materials to advance an item one step; the item's tier and stats change according to the item's evolution table, and at certain steps the item transforms. The system checks that the player holds the materials, applies the step, and updates the item in place. Evolution preserves the item's accumulated progress rather than resetting it, which matters when an item is fused or reworked.

The refusal cases are as important as the successes: an attempt to advance beyond the cap, or without the materials, is refused with the same message the original returns.

## Opcodes

Evolution is driven by the equipment routes. The client requests and their server replies are documented on their opcode pages and reached through `Session.equipEvolveRoute`. See the [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `equip.csv`, `equip_advance.csv`, `equipjinhua.csv` | Gear roster, advancement, and evolution steps |
| `equiprh.csv` | Gear rebirth |
| `jewelry.csv`, `jewelry_advance.csv`, `jewelry_jinhua.csv`, `jewelry_ronghe.csv` | Jewelry roster, advancement, evolution, and fusion |

The `jinhua` tables are the evolution tracks; the reconstruction reads a step's cost and result from them. See the [[Data File Index]].

## Persistence

An evolution commits one revision to the character's [[State Store]]: materials are consumed and the item is updated. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/EquipEvolve.kt`, with the transactions in `server/store/EquipEvolveTransactions.kt`, routed from `Session.equipEvolveRoute`.

## How It Was Deciphered

Evolution was captured across gear and jewelry, at normal steps, at transformation thresholds, and at the cap, and reproduced so the resulting item and consumed materials match the reference exactly. It is pinned by the [[Method Differential Harness]] and by native testing that evolves gear and jewelry against the reconstruction.

## See Also

- [[Gear and Equipment]] and [[Compose]].
- [[Acquisition and Items]], the source of the materials.
