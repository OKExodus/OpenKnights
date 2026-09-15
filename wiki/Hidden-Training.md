# Hidden Training

**Definition.** The Hidden Training menu: training rooms that grant EXP and Honor over time, the Blacksmith and Crafting forge slots, and Hero Set Out, which sends a hero on a timed dungeon run.

## How It Works Inside

A training room belongs to one of three types (Goblinia, Dwarfia, Dragania), each with its own level window and a boost rate; a player can join the shared default room or create and own a room of a chosen type for a lifetime paid in Diamonds. Sitting a seat starts training; the reward is EXP and Honor computed from the seconds trained, the player's attack score, the room's boost, and a creator or full-room bonus, and a claim ends the seat and pays out. A room's owner can set a password, add time, or let it lapse.

The Blacksmith and Crafting slots feed forge EXP into one held gear or jewelry item, advancing its level along the item's own EXP curve; each slot cools down afterward, and the cooldown can be cleared early for Diamonds. Hero Set Out holds a small number of slots, each purchasable up to a cap; assigning a hero draws three dungeon choices, sending the hero away starts a timed run consuming a Token, and the hero can be recalled for a preview, refreshed for new choices, or claimed on return for its reward and hero EXP, drawn from three outcomes: robbed, met a traveler, or received a gift.

## Opcodes

Room actions (C1761 list, C1763 create, C1765 enter, C1767 seat/Train Now, C1769 password, C1773 kick, C1775 preview, C1777 claim, C1779 add time), the forge (C1643 Blacksmith, C3747 Crafting, C3731/C3753 cooldown removal), and Hero Set Out (C2113 assign hero, C2115 go, C2117 refresh, C2119 claim, C2121 buy slot, C2123 return, C2125 cancel) all route through `DailyRoutes` and `Session.dailyRoute`. See the [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `train.csv`, `newtrain.csv` | Training room types, level windows, and boost rates |
| `newtrain_hero.csv` | Hero Set Out dungeon choices and their weights |
| `newtrain_monster.csv` | The training room's monster and boss encounter data |
| `newtrain_quest.csv` | Hero Set Out outcomes and their reward boxes |

See the [[Data File Index]].

## Persistence

A room, forge, or Hero Set Out action commits one revision to the character's [[State Store]]. See [[Transactions and Publishing]].

## Determinism

Hero Set Out's dungeon choices and its return outcome draw from a `random.Random` seeded from a SHA-256 digest of the action's identifying parts (the owner key, the slot, and the position), so the same seed reproduces the same choices and outcome. Every room and slot timer is stored as an absolute deadline and reported to the client as remaining seconds. See [[Method Determinism]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/HiddenTraining.kt`, routed through `DailyRoutes` and `Session.dailyRoute`.

## How It Was Deciphered

Room creation, seating, claiming, the forge slots, and every Hero Set Out transition were captured and reproduced so the resulting EXP, Honor, and item rewards match the reference exactly, including the attack-score formula and the forge's level curve. It is pinned by the [[Method Differential Harness]].

## See Also

- [[Castle]], which hosts Card Sacrifice and Reforge from the same menu.
- [[Heroes]] and [[Battle Engine]], the source of the attack score Hidden Training rewards on.
