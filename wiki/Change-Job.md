# Change Job

**Definition.** Changing the main character's (the leader hero's) class among Warrior, Mage, and Hunter using a Main Character Transfer Card, moving the leader to the same-tier base of the new class.

## How It Works Inside

The request carries one class byte. The server finds the character's single leader-class hero, unpacks its packed template into a base id and grade, and looks up the `zhujuezhuanzhi` row for that (base, grade) pair. That row names up to two target bases, one per possible destination class, each with its own Gold cost; the server picks the entry whose base resolves to the requested class through `hero.csv`'s class column. Requesting the class the leader already has, or a (base, grade) with no configured row, is refused before anything changes.

Once a target base is chosen, the leader's full growth, stat permille, and base stats are recomputed at the same grade and level using the same bounded stat model every hero-card system shares (see [[Heroes]]), and the new field values are checked against their wire widths before being written. The leader's Astral Power (god skill) list moves with the class: skills in the old class's series shift to the new class's series at the same level offset, the Warrior-only series is set aside when leaving Warrior and restored on return. A reborn leader is refused, since its stat model is not yet supported here. The transfer card and the shown Gold are consumed, and the new template is registered in the hero collection book.

## Opcodes

C2561 requests the change; S3040 carries the leader's full updated field map. Routed through `Session.changeJobRoute`. See the [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `zhujuezhuanzhi.csv` | Class-switch rows: source base and grade, target bases per class, and their Gold cost |

`hero.csv`'s class column resolves which target base belongs to which class; see [[Heroes]]. See the [[Data File Index]].

## Persistence

Change Job commits one revision to the character's [[State Store]]: the leader's field list, the Astral Power document, Gold, and the hero collection book entry. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/ChangeJob.kt` (`planChangeJob`, `moveSkills`, `switchRow`), routed from `Session.changeJobRoute`.

## How It Was Deciphered

The card's native use, the class-switch row selection, and the recomputed stat outcome were captured and reproduced so the resulting leader and consumed Gold match the reference; the frame order and the Astral Power series handling are structural policy read from the game's own tables and code rather than captured live. Pinned by the [[Method Differential Harness]].

## See Also

- [[Heroes]], [[Character Creation]].
