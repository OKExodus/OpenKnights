# Player Level

**Definition.** The character's account level and its experience, advanced outside combat by quest, bounty, Daily Mission, Royal Door, and training rewards, using `roleexp.csv` for the experience each level costs to leave.

## How It Works Inside

The character's level and its experience within that level are ordinary role properties. Granting experience adds to the in-level total and then settles as many level-ups as it covers, each consuming the current level's `roleexp.csv` threshold; the top level has no threshold of its own, so experience keeps accumulating past it rather than looping. A single grant reports the resulting level and remaining experience together in one role update. A level gained also advances the player-level achievement kind (see [[Achievements]]) and joins any city buildings the new level newly unlocks.

## Opcodes

No opcode of its own. Every experience-granting action (a story or bounty quest claim, a Daily Mission or Royal Door reward, a training result, and others) calls the shared grant, and its role-update, achievement, and building frames ride that action's own reply.

## Data Files

| Table | Role |
| --- | --- |
| `roleexp.csv` | Experience required to leave each level |

See the [[Data File Index]].

## Persistence

Level and experience are written as part of whichever action's own transaction; the grant commits no revision of its own. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/PlayerLevel.kt` (`grantExp`), called from [[Quests and Bounty Board]], [[Claims]], and the other systems that award experience.

## How It Was Deciphered

The level-up frames and the building unlocks that follow a level-up were captured across multiple experience sources and reproduced so the settled level matches the reference, pinned by the [[Method Differential Harness]].

## See Also

- [[Character Creation]], [[Heroes]].
