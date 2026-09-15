# Character Selection and Deletion

**Definition.** Choosing which character to play from the account's list, and removing one. Selection loads a character into the game; deletion retires it while keeping a way back until the trash is emptied.

## How It Works Inside

After sign-in, the account presents its character list. The player either selects an existing character, which loads that character's save and enters the world, or creates a new one. See [[Accounts and Sign-in]] and [[Character Creation]].

Deletion does not destroy a save outright. The character's save is moved to the trash and its world entries are released: its name, its wire identity, and its place in shared systems are freed for reuse. Because the save is in the trash rather than gone, the deletion can be undone until the trash is emptied, and emptying the trash asks for confirmation. This mirrors the original's own delete-and-restore behavior.

## Opcodes

| Opcode | Role |
| --- | --- |
| `S18` | The character list and the create prompt |

The selection and deletion requests, and their replies, are documented on their opcode pages.

## Persistence

Selection reads a character's [[State Store]] and marks it active for the session. Deletion moves the save to the data root's trash and releases the character's entries in the [[World Directory]], recording the retirement in the account registry so the name and identity can be reused. See [[Save and Data Root]].

## Determinism

Selection and deletion are exact state operations, pinned like every other. A deleted character's released name and identity become available again by the same rules the world uses to allocate them. See [[World and Generations]].

## Code

Reproduced across `server-core/src/main/kotlin/io/github/okexodus/openknights/server/session/` (the selection routes) and `server/store/SaveManagement.kt` and `AccountRegistry.kt` (deletion, retirement, and the trash).

## How It Was Deciphered

The selection and deletion flows were captured and reproduced so the character list, the released entries, and the trash match the reference exactly, and are pinned by the [[Method Differential Harness]].

## Note on the In-game Trash Icon

Deleting a specific character is the one data-management action that stays inside the game, as an in-list trash icon, while backup, restore, and reset are handled through the player's files. See [[Save and Data Root]].

## See Also

- [[Character Creation]] and [[Accounts and Sign-in]].
- [[World and Generations]], which reclaims a deleted character's name and identity.
