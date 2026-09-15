# Battle Engine

**Definition.** The deterministic simulation that resolves a fight between two teams. Given the two lineups and a seed, it plays the battle out and produces a result that is identical every time, down to the last unit of damage.

The battle engine is the most exactness-critical system in the project. Everything else can be checked against a state change; a battle has to be reproduced move for move, because a single divergence early cascades into a completely different fight. It is documented in more detail than any other page for that reason.

## How It Works Inside

A battle is a turn-based contest between an attacking team and a defending team, each a set of units placed by [[Formation]]. Units act in an order the engine determines, each action drawing on the shared random source to decide chance-based outcomes, computing damage from the attacker's and defender's stats, and applying the result. The battle ends when one side is defeated or a limit is reached, and the engine emits a [[Battle Report]] describing what happened.

The engine owns no persistent state. It is a pure function of its inputs: the two teams, their stats, and the seed. The systems that call it, chiefly [[Campaign]], are responsible for assembling the inputs and for settling the outcome.

## Opcodes

The engine is internal. It is invoked by the systems that run battles, and its outcome reaches the client as a report.

| Opcode | Role |
| --- | --- |
| `S4` | The battle report the engine produces, encoded for the client. See [[Battle Report]]. |
| `S12` | Per-unit position and state updates within the report. |

The triggers that start a battle belong to the calling system, for example `C129` in [[Campaign]].

## Data Files

The engine reads the tables that define units and their combat behavior:

| Table | Role |
| --- | --- |
| `monster.csv`, `monsterability.csv`, `monsterskill.csv` | Enemy units and what they can do |
| `skill.csv`, `buff.csv`, `effect.csv` | The skills units cast, the buffs they apply, and how those resolve |
| `property.csv` | The attribute model the stats are expressed in |
| `battlesettlement.csv` | How a finished battle is settled into rewards |

See the [[Data File Index]] for the relations among these.

## Persistence

None. The engine does not touch the data root. Its caller persists the settlement.

## Determinism

This is where the engine lives or dies. Every one of the following is reproduced exactly. See [[Method Determinism]].

- **Randomness.** Draws come from SplitMix64 with unsigned 64-bit arithmetic, seeded from the battle's recorded seed. The order of draws is fixed by the engine's rules, and there are rules for when a draw is not consumed at all: a chance that is at or below zero, or at or above its maximum, consumes no draw, and a coefficient whose low and high bounds are equal consumes no draw. Getting these skip rules wrong shifts every later draw and changes the fight. An integer draw is unbiased by rejection sampling, from `exact/.../SplitMix64.kt`:

```kotlin
/** Unbiased integer in [0, n): reject the biased tail so every result is equally likely. */
fun below(un: ULong): ULong {
    val remainder = ((ULong.MAX_VALUE % un) + 1uL) % un   // 2^64 mod n
    while (true) {
        val value = nextU64()
        if (remainder == 0uL || value < 0uL - remainder) return value % un
    }
}
```
- **Floating point.** Some intermediate values are computed at 32-bit float precision and then widened, while the damage total is computed in 64-bit and floored to an integer. The reconstruction narrows to 32-bit exactly where the game does, so the rounding matches.
- **Seed handling.** The seed is carried as an unsigned 64-bit value where it is stored and compared, and used as the same 64 bits when it drives the generator. A signed reading of the same bits would seed the generator with the wrong number.

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/BattleEngine.kt`, with the unit and stat model in `BattleStats.kt` and `HeroStats.kt`, and the report codec in [[Battle Report]].

## How It Was Deciphered

Battles were captured and the engine's rules were recovered move by move: the action order, the draw order, the skip rules, and the damage formula, each confirmed by reproducing a captured fight exactly. The result is pinned two ways:

- By the [[Method Differential Harness]], which replays recorded battles and compares the reconstruction's every draw and every damage number against the reference, with hundreds of battle simulations matching byte for byte.
- By native proof, in which the patched game plays a real campaign battle against the reconstruction and reaches the same outcome, including a full three-star clear.

## See Also

- [[Campaign]], which assembles battles and settles their results.
- [[Battle Report]], the encoded outcome.
- [[Formation]], which produces the teams the engine fights with.
- [[Method Determinism]], the discipline this system depends on.
