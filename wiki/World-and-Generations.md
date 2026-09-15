# World and Generations

**Definition.** The shared world that characters live in, when it comes into being, and how the data root keeps versions of it so that restores and resets are safe.

## How It Works Inside

A data root does not have a world when it is first created. It has an owner account and nothing else. This is the unborn state. The world is born the moment the first character is created, and from then on it holds the shared state that characters interact with: the roster of world characters, their names and wire identities, guild membership, and the other data that is common rather than personal.

The world is stored as a generation, a single self-contained version of the born world inside the data root. Normal play keeps one active generation. Operations that replace the world, chiefly restore and reset, do not edit the active generation in place. They build a new generation and then swap it in as active in one atomic step, retiring the old one to the trash. This is what makes a restore all-or-nothing: at no point is there a half-restored world.

## Wire Identities

Every character presents a numeric wire identity to the world, separate from its internal character id. Wire identities are allocated from a fixed range that starts well above the reserved bot range, so a player's identity never collides with a bot's and is never reused. The internal character id is a long random identifier; the wire identity is the small number the world and other players see.

## Born and Unborn, in Practice

- **Unborn:** owner account only, no world. The game shows character creation. See [[Character Creation]].
- **Born:** an active generation exists. Characters can be selected and played.
- **Reset** returns the data root toward a fresh state so the next start is a brand-new world.
- **Restore** replaces the active generation with one rebuilt from a backup.

See [[Save and Data Root]] for how these are stored and [[Transactions and Publishing]] for how the swap stays safe.

## Persistence

The world lives in the world database within the active generation, alongside the account registry and the character saves. The data root's manifest names which generation is active. See [[World Directory]] and [[Save and Data Root]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/store/WorldDirectory.kt` and `DataRoot.kt`, with the born-on-first-creation behavior tied together in `server/session/ReleaseFactory.kt` and `Service.kt`.

## How It Was Deciphered

The born and unborn behavior, the generation model, and the wire-identity allocation were established from captured creation and play, and are pinned by the [[Method Differential Harness]] and by native testing that creates a world, plays it, and restores it.

## See Also

- [[Character Creation]], which births the world.
- [[Save and Data Root]].
- [[World Directory]].
