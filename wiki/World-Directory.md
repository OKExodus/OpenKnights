# World Directory

**Definition.** The shared world database: the roster of world characters and the shared documents they interact with. Where the [[State Store]] holds what is personal to a character, the world holds what is common to all of them.

## How It Works Inside

The world database records every character's presence in the world: its entry, its unique name, its wire identity, its starter and gender, and its status. Around that roster sit the shared documents that systems contribute to: the data that is common rather than personal, from world-wide progress to the bookkeeping the daily and social systems keep. A character's save is authoritative for personal progress; the world is authoritative for shared data, and the two are kept consistent with each other.

The world is created when the first character is created and the world is born. See [[World and Generations]] and [[Character Creation]].

## Reservations

Creating a character reserves its place in the world atomically, so two creations cannot take the same name or identity:

- **Name.** A unique name is reserved, checked against both existing characters and the reserved bot names.
- **Wire identity.** The next free wire identity is allocated from a fixed range that begins above the reserved bot range, so a player's identity never collides with a bot's. See [[World and Generations]].

If creation fails after a reservation, the reservation is abandoned so a half-created character never holds a name or an identity.

## Shared Documents

Shared state is kept as documents updated with optimistic retries: a reader takes the current document and its revision, edits a copy, and commits it only if the revision has not moved, retrying if it has. This lets independent changes to the shared world proceed without locking, and it is one of the seams the [[Bot Player System]] will use, because a document accepts contributions from any participant, not only the player's characters.

## Persistence

The world is `world.sqlite3` in the active generation, alongside the registry, the bot database, and the character saves. See [[Save and Data Root]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/store/WorldDirectory.kt`, with the born-on-first-creation behavior tied together in `server/session/ReleaseFactory.kt`.

## How It Was Deciphered

The roster, the reservations, and the shared-document model follow the reference store and are pinned by the [[Method Differential Harness]], which compares the world database by logical content.

## See Also

- [[State Store]], the personal counterpart.
- [[World and Generations]], the born world the directory belongs to.
- [[World Participants]] and [[Bot Player System]].
