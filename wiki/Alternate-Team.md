# Alternate Team

**Definition.** A second bank of hero slots ("Alt Hero") a player unlocks and fills independently of the main formation, opened one position at a time as the player's level and Gold or Diamonds allow.

## How It Works Inside

The alternate team is a stored document of an open-position count and slot assignments. Its effective open-position count is the larger of what the player has explicitly unlocked and what the leading auto-open rows of the unlock table already grant once the player's level reaches them. Unlocking the next position spends that row's configured Gold or Diamond cost and raises the stored count by one; a position beyond the player's level, or when every position is already open, is refused. Placing a hero into an open position takes it off the bench, replaces whatever hero already held that position, and refuses a hero that already holds another alternate position, sits in the main lineup, or fails the client's native lineup rules: a hero base carries a duplicate-check flag, and a table of rebirth triples relates bases so two related heroes cannot occupy the main lineup and an alternate slot at once. At login the server reports only the slots whose hero is still owned, alongside the open-position count.

## Opcodes

| Opcode | Role |
| --- | --- |
| C3777 | Unlock the next alternate position |
| C3779 | Place a hero into an alternate position |
| S3745 | Alternate team info (login) |
| S3746 | New open-position count |
| S3748 | Position set |

See [[Opcode Index]].

## Data Files

Unlock costs and auto-open thresholds come from `fujiangkaiqi.csv`. See [[Data File Index]].

## Persistence

An unlock or a placement commits one revision to the character's [[State Store]]. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/AltTeam.kt`, routed from `Session.altTeamRoute` for in-game-created characters.

## How It Was Deciphered

Unlock and placement were captured and reproduced so the open-position count, the slot document, and the reply frames match the reference; the Diamond-spending side frames of an unlock were not captured and are excluded. Pinned by the [[Method Differential Harness]].

## See Also

- [[Formation]], the main lineup an alternate hero cannot also join.
- [[Secondary Team]].
