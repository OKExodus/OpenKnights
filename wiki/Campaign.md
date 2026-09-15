# Campaign

**Definition.** The stage-by-stage single-player progression. The player fights through stages on a map, earns rewards and stars, and unlocks the next stage.

## How It Works Inside

The campaign is a sequence of stages grouped into maps. A stage names the enemy team to fight and the rewards for clearing it. When the player enters a stage, the server assembles the two teams, runs the fight through the [[Battle Engine]], and settles the outcome: it grants experience and drops, records the result and its star rating, and advances progress. The campaign supports fighting a stage directly, auto-resolving it, claiming a first-clear bonus, and re-entering a cleared stage, each as its own message.

The battle itself is deterministic. The campaign's job is to produce the seed and the teams, hand them to the engine, and turn the report into rewards and saved progress.

## Opcodes

| Opcode | Role |
| --- | --- |
| [[Opcode Index\|C129]] | Enter and fight a stage |
| [[Opcode Index\|C131]] | Auto-resolve a stage |
| [[Opcode Index\|C133]] | Claim the first-clear reward |
| [[Opcode Index\|S4]] | The resulting [[Battle Report]] |

The refusal and re-entry messages, and the star-box claim, are documented alongside these on their opcode pages.

## Data Files

| Table | Role |
| --- | --- |
| `copy_stage.csv` | The stages: enemy team, rewards, and star conditions |
| `copy_map.csv`, `map.csv`, `stage.csv` | The maps and stage layout |
| `box.csv`, `starbox.csv`, `choosebox.csv` | The reward and star-milestone boxes |

## Persistence

A cleared stage commits one revision to the character's [[State Store]]: progress advances, rewards are added to the inventory, and the star rating and battle result are recorded. The committed transaction carries the battle seed so the exact fight can be reconstructed and compared. See [[Transactions and Publishing]].

## Determinism

The campaign derives the battle seed from recorded inputs and stores it as an unsigned 64-bit value, because the transaction detail records it and any comparison of the saved history has to see the same number. The battle it seeds is then fully deterministic in the [[Battle Engine]]. See [[Method Determinism]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Campaign.kt`, routed from `Session.campaignRoute` in `server/session/Session.kt`, with the battle math in [[Battle Engine]].

## How It Was Deciphered

Campaign play was captured across entering, auto-resolving, first-clearing, and re-entering stages, and each path was reproduced and pinned by the [[Method Differential Harness]]. A native proof played a real stage to a three-star clear against the reconstruction and confirmed the settlement, the star rating, and that the progress survived a cold login.

## See Also

- [[Battle Engine]] and [[Battle Report]].
- [[Formation]], which supplies the player's team.
- [[Acquisition and Items]], which receives the drops.
