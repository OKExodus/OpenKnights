# Compose

**Definition.** Refining owned gear, jewelry, and items into materials, combining materials into a target gear or jewel piece to raise it to its super variant, and fusing two heroes into the next super class.

## How It Works Inside

Compose covers three related conversions, each read from the same star-and-super-flag catalog rows. Refine breaks down an owned gear, jewelry, or item stack into a configured product and amount. Combine raises a target gear or jewel's super flag from 0 to 1: it computes a merge rate from a base row plus each material's contribution, doubling a super material's contribution at or above the target's flag, refuses a rate below the minimum, then consumes a pair of stones and deletes the materials. Fuse advances a hero to its next super class, the packed hundreds digit rising: it computes a rate from the target and material heroes' catalog fields, rolls a seeded chance against that rate plus any banked luck, and on success recomputes the hero's stats for the new template while keeping its accumulated level, EXP, and development; on failure it consumes up to three sampled materials and raises the banked luck instead.

## Opcodes

| Opcode | Role |
| --- | --- |
| C1249 | Hero Fuse |
| C2051 / C2633 | Gear / jewelry Refine |
| C3137 | Item Refine |
| C2055 / C2631 | Gear / jewelry Combine |

Replies include S1568 (Fuse result) and S1572 (the fuse luck list), S1574 / S3088 / S3340 (Refine rewards), and S1576 / S3086 (Combine result). See [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `herorh.csv` | Hero Fuse rate and reward rows |
| `equiprh.csv` | Gear Refine and Combine rate rows |
| `jewelry_ronghe.csv` | Jewelry Refine and Combine rate rows |

See [[Data File Index]].

## Persistence

Each Refine, Combine, or Fuse commits one revision to the character's [[State Store]]. See [[Transactions and Publishing]].

## Determinism

Fuse's success roll is a seeded draw against the computed rate plus banked luck; the seed is recorded with the transaction so the outcome replays. See [[Method Determinism]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Compose.kt`.

## How It Was Deciphered

Refine and Fuse were captured and reproduced so the consumed materials, products, and the fuse roll and luck accounting match the reference. Combine's frames are a structural candidate built from the client's own rate and stone checks, not a direct capture. Pinned where captured by the [[Method Differential Harness]].

## See Also

- [[Gear and Equipment]], the items Compose consumes and produces.
- [[Acquisition and Items]].
