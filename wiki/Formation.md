# Formation

**Definition.** How the player arranges heroes for battle: which heroes are on the field, where they stand, who leads, and who waits on the bench.

## How It Works Inside

A character has a roster of heroes and a battle formation drawn from it. The formation system moves heroes between the bench and the field, sets their positions, and names the captain. The arrangement it produces is exactly what the [[Battle Engine]] fights with, so the placement is not cosmetic; position and captaincy feed into combat.

Changes are individual and incremental. Adding a hero to the field, removing one, moving one, and setting the captain are each their own message, and each commits a small change to the saved lineup.

## Opcodes

| Opcode | Role |
| --- | --- |
| [[Opcode Index\|S38]] | Hero added to the bench |
| [[Opcode Index\|S40]] | Hero removed from the bench |
| [[Opcode Index\|S42]] | Lineup set |
| [[Opcode Index\|S44]] | Captain set |

The client requests that drive these, along with the alternate and secondary teams, are documented on their opcode pages and in [[Alternate Team]] and [[Secondary Team]].

## Data Files

Formation reads hero definitions from the hero tables (see [[Heroes]]) to know each hero's combat profile. It does not have a table of its own; the arrangement is player state.

## Persistence

The formation is part of the character's [[State Store]]. Each change commits one revision. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/EquipFormation.kt` and the formation transactions in `server/store/FormationTransactions.kt`, routed from `Session.formationRoute` and `Session.lineupView`.

## How It Was Deciphered

The lineup messages were captured across every placement operation and reproduced so the saved arrangement matches the reference. They are pinned by the [[Method Differential Harness]].

## See Also

- [[Battle Engine]], which fights with the formation.
- [[Heroes]], [[Alternate Team]], [[Secondary Team]].
