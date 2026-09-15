# Leader Repair

**Definition.** A login-time correction that fixes a leader hero whose packed super-class digit fell out of sync with its evolution row, rewriting the digit and its four stats before the login state is sent.

## How It Works Inside

A leader is the one owned hero whose evolution inputs mark it as a leader; a hero whose inputs cannot be resolved is skipped rather than treated as a match, and repair only applies when exactly one such hero is found. A leader's template packs a super-class digit that should match what its evolution row calls for: normally the digit the grade's own row names, or, at a super grade of exactly 1, only when the character's evolution history actually took the super step that produced this template. When the leader's current digit disagrees with that, its template field is rewritten to the correct digit and its four stat fields are recomputed through the shared stat model at the corrected template, keeping the same growth, level, EXP, development, and awaken it already had. The repair commits as one audited revision immediately before the login state and carries no reply frame of its own; the corrected fields simply appear in that state.

## Opcodes

Leader Repair has no opcode of its own. It runs as a silent correction ahead of the login state documented with [[Formation]].

## Data Files

Leader Repair reads the same evolution inputs [[Equipment Evolution]] uses, for a hero's leader flag, star, and packed super-class digit; it has no table of its own.

## Persistence

The repair commits one revision to the character's [[State Store]] before login, with no reply packet. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/LeaderRepair.kt`.

## How It Was Deciphered

The mismatch and its correction are a labeled policy repair rather than a captured client exchange: the evolution history is scanned for a super step that raised the packed digit at an unchanged grade, and a leader whose current digit disagrees with what that history and its row call for is corrected. Reasoned from the same evolution-row and stat-model evidence that backs [[Equipment Evolution]], and pinned by the [[Method Differential Harness]] through the stat outputs it reproduces.

## See Also

- [[Formation]], whose captain and leader concept this repairs.
- [[Equipment Evolution]].
