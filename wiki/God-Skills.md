# God Skills

**Definition.** Astral Power ("god skills"): a per-hero list of skills, each with its own progress, that a character's owned heroes carry independently of the base-hero and evolution systems, stored as one checksum-bound document.

## How It Works Inside

Each owned hero that has god-skill state carries a list of `(skill id, progress)` pairs. Pressing to upgrade a skill names the hero, the skill's current id, and a press count restricted to the client's x1/x10/x100 choices. Each press consumes a configured quantity of a configured Astral Stone item and adds one point of progress; when progress reaches the skill row's requirement, the skill becomes the next configured id at zero progress and a multi-press request stops there rather than rolling over into the new skill's own requirement. A request whose presses would outrun the owned stones stops at the last affordable press instead of partially spending toward one it can't complete. A skill already at its top configured level, or a hero with no god-skill state at all, is refused.

The god-skill document is validated as a whole on every write: hero entries strictly ascending by UID, each hero's skill ids unique and ascending, every id and progress value a valid uint32. The `resetgodskill` table separately backs a per-skill reset path (see [[Character Creation]] for how a fresh character's starting document is seeded) rather than the press itself.

## Opcodes

The startup list is S2848; a press is C2497, routed from `Session.heroCardRoute` alongside [[Hero Power-up]] and [[Hero Ascension]]; a successful press replies with the hero's whole updated skill list on S2850. See the [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `resetgodskill.csv` | Per-skill reset row consulted outside the ordinary press path |

Astral Power's own press-cost rows (item and per-press quantity, and the next-skill row) are read from the astral catalog inputs; the press planner keys them by skill id. See the [[Data File Index]].

## Persistence

The god-skill document lives in its own table as one JSON document with a stored SHA-256 checksum; a press rewrites the target hero's entry and commits both the document and a [[State Store]] revision together. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/.../game/GodSkills.kt`.

## How It Was Deciphered

The startup list, the press request and reply, and the level-up and stones-exhausted stopping rules were captured and reproduced so the resulting skill list and consumed stones match the reference, and are pinned by the [[Method Differential Harness]].

## See Also

- [[Heroes]], [[Hero Evolution]], [[Character Creation]].
