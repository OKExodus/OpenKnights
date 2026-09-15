# Hero Power-up

**Definition.** A training-and-save cycle that rolls small, capped increases to a hero's four development values (the same fields Fortify and Evolution read as `dev` in the base-stat model) by spending Hero Stones.

## How It Works Inside

The dialog opens with an acknowledgement-only request, then a train request names the target, a "way" (the material tier: ordinary Hero Stones or one of two fumo.csv-derived tiers), and a try count restricted to the client's x1/x10/x100/x1000 buttons. Each stat has a cap, `Formula::GetHeroDevMax`: the configured cap permille times the stat's undeveloped base, truncated in 32-bit float arithmetic. The system checks at least one stat has room under its cap, then spends the way's stones and rolls: one independent draw per stat per try, summed over the whole count, clamped so the pending development for each stat cannot leave `[0, cap]`. The roll is recorded as pending, not yet applied to the hero.

A save request then applies the most recently recorded pending roll for that hero to its actual development and stat fields, provided the hero's development has not changed since the roll was recorded; if it has, the save is refused rather than reapplied to a hero that has moved on. A train without a following save simply leaves its roll unclaimed.

## Opcodes

C3693 open, C3713 train, and C3721 save all route through `Session.heroCardRoute`. See the [[Opcode Index]].

## Data Files

Power Up reads the same `hero.csv` and `heroexp.csv` base-stat inputs used to resolve a hero (see [[Heroes]]), plus fumo.csv for the higher material tiers' per-stat roll ranges.

## Persistence

A train commits its pending roll and consumed stones as one revision; a save commits the applied development and stats as a second revision, both to the character's [[State Store]]. See [[Transactions and Publishing]].

## Determinism

Each try draws from a seeded RNG (a 64-bit seed recorded with the roll, from the service's own entropy source) so a commit replays exactly; ordering is one draw per stat per try, in a fixed stat order. See [[Method Determinism]].

## Code

Reproduced in `server-core/.../game/HeroPowerUp.kt`.

## How It Was Deciphered

The per-try roll is server-side RNG and unrecoverable from client-visible behavior alone, so the reconstruction runs it only under an explicitly labeled local RNG policy; the request/result wire shapes and the cap formula were captured directly and are pinned by the [[Method Differential Harness]].

## See Also

- [[Heroes]], [[Hero Fortify]].
