# Hero Evolution

**Definition.** Advancing a hero's packed grade by one tier, which recomputes its growth and stats and, at certain tiers, unlocks its awaken slots and [[God Skills]].

## How It Works Inside

Evolution has two request shapes over one shared planner. The ordinary path names a target hero; the leader path carries no target and resolves the character's single owned leader-class hero itself. Both resolve the target through the shared bounded stat model in [[Heroes]], then look up the current grade's config row: its material item and quantity slots and its Gold cost. The new packed template keeps the hero's base id and super-class digit and increments only the grade; growth is recomputed at the new grade from the hero's raw growth and potential rate, and stats follow from the same permille and development values the current tier already carries. A grade with no successor, or materials the character does not hold as single, sufficient stacks, refuses the request before any change.

Evolution tiers are also where a hero's awaken (herojuexing) slots open up: `herojuexing.csv` names which base has a slot at a given tier, `herojuexinglv.csv` and `herojuexingneedres.csv` gate it by hero and role level and the required resource, and `herojuexingskill.csv` supplies the skill a slot grants. A raised awaken level feeds back into the stat model's permille as a bonus, which is why evolution and awaken slots share one arithmetic path with [[Hero Ascension]].

## Opcodes

The ordinary and leader requests are routed from `Session.evolutionRoute` (C71) and `Session.leaderEvolutionRoute` (C2083). See the [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `herojuexing.csv` | Which base hero has an awaken slot at a tier |
| `herojuexinglv.csv` | Level gates on an awaken slot |
| `herojuexingneedres.csv` | The resource required to open an awaken slot |
| `herojuexingskill.csv` | The skill an awaken slot grants |

See the [[Data File Index]].

## Persistence

Evolution commits one revision to the character's [[State Store]]: the hero's template, growth, and stats, the consumed materials, and Gold. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/.../game/HeroEvolution.kt`.

## How It Was Deciphered

Evolution was captured for both the ordinary and leader paths across several tiers, reproduced so the resulting template, stats, and consumed materials match the reference, and pinned by the [[Method Differential Harness]].

## See Also

- [[Heroes]], [[God Skills]], [[Hero Ascension]].
