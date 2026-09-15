# Battle Report

**Definition.** The encoded record of a fight that the server sends to the client so it can play the battle back. The client does not compute the battle; it renders the report the server produced.

## How It Works Inside

When the [[Battle Engine]] resolves a fight, it produces a structured account of everything that happened: the two teams and their starting state, and the sequence of actions, each with its actor, target, effect, and result. That account is encoded into the battle report message and sent to the client, which animates it. Because the client only renders what the server sends, the server is the sole authority on the outcome, and the report has to be byte-exact for the animation to match the original.

The report also carries the per-unit updates that keep the client's view of the battle in step as it plays back.

## Opcodes

| Opcode | Role |
| --- | --- |
| [[Opcode Index\|S4]] | The battle report itself |
| [[Opcode Index\|S12]] | Per-unit position and state updates within the report |

## Data Files

The report references units and effects defined in the combat tables. See [[Battle Engine]] for `monster.csv`, `skill.csv`, `buff.csv`, and `effect.csv`, and `battlesettlement.csv` for how the finished battle is turned into rewards.

## Persistence

None directly. The report is a message, not stored state. The [[Campaign]] settlement that follows a battle is what persists.

## Determinism

The report is a faithful serialization of a deterministic battle, so it is exact whenever the battle is. The encoding is fixed field for field; the reconstruction produces the same bytes the reference does for the same fight. See [[Method Determinism]].

## Code

The report codec, which both encodes and parses the message, is reproduced alongside the engine in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/`, with the battle-mode model in `BattleStats.kt`.

## How It Was Deciphered

The report layout was recovered from captured battles by tying each field to the action it described, then pinned by the [[Method Differential Harness]], which compares the encoded report byte for byte against the reference for every simulated battle.

## See Also

- [[Battle Engine]], which produces the report.
- [[Campaign]], the most common source of battles.
