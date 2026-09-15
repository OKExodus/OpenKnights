# World Participants

**Definition.** The single list every social and ranking system reads to see other players: every local character, read from its own save, joined into one participant view the world binds.

## How It Works Inside

A participant is a read-only snapshot built fresh from a character's current save: its wire role id, name, level, VIP tier, reputation, gender, its lineup and captain hero, and its Power. Power is the release's universal figure, supplied by whichever list is asking rather than recomputed per system, so a guild roster, a friend list, and the Arena ladder all show the same number for the same character. Systems that need to see other players, such as [[Guild]] membership rows, [[Friends]] recommendations, mail and chat name lookups, and the [[Arena]] ladder, all read through this one list rather than each keeping its own copy.

The list is built by reading every character the [[World Directory]] knows about and re-reading its current save through the account registry; no bot participant exists yet, so the list today is characters only. The interface already accepts a bot list alongside the character list, which is the seam the [[Bot Player System]] will fill: a bot enters the same participant shape, with the same fields, and joins the same roster a character does.

## Data Files

`robot.csv` describes bot definitions the game ships; it is not read yet, since no bot exists in this release, and stands as the reference the [[Bot Player System]] will draw its roster from.

## Persistence

World Participants reads rather than writes: characters come from the [[World Directory]]'s roster, each one's current fields from its own [[State Store]] save. It holds no document of its own.

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/WorldParticipants.kt`, read by `Session.rankRoute` for the Arena ladder and by `SocialRoutes.SocialContext.people` for guild, friends, mail, and chat.

## How It Was Deciphered

The participant fields and the universal Power figure were captured, reproduced, and pinned by the [[Method Differential Harness]].

## See Also

- [[World Directory]], the roster this list is built from.
- [[Battle Engine]], the consumer of a participant's lineup and Power.
- [[Bot Player System]], the planned second source of participants.
