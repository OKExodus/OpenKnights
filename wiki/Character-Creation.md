# Character Creation

**Definition.** Making a character. The player picks a name, a gender, and a starter hero, and the server builds a fresh save and the world entry that goes with it. Creating the first character is also the moment the world is born.

## How It Works Inside

Creation is a two-step exchange. The player submits a name, then chooses a starter and commits. On commit, the server:

1. Validates the name and the chosen starter against the owner account.
2. Reserves a unique name and the next free wire identity in the world. See [[World and Generations]].
3. Builds the fresh player state from the creation template: the starter hero, the opening inventory, and the initial systems, all at revision one.
4. Writes the new save and its creation checkpoint, registers the character with the account, and activates its world entry.

If anything fails partway, the reservation is abandoned so a half-created character never lingers.

The first successful creation on a fresh data root is what makes the world born. Before that, the world does not exist; nothing is generated until the first character exists. See [[World and Generations]].

## Opcodes

| Opcode | Role |
| --- | --- |
| [[Opcode Index\|S18]] | The character list and the create prompt |
| [[Opcode Index\|C289]] | Submit the character name |
| [[Opcode Index\|C291]] | Choose the starter and commit the creation |

A name that is already taken, or a starter that is invalid, is refused with the same message the original returns, and the flow returns to the name step.

## Data Files

Creation reads the fresh-profile template and the starter definitions rather than a single table. The starter heroes come from the hero tables (see [[Heroes]]), and the opening state comes from the shipped creation template. The template ships as part of the release data and is treated as private until it is reviewed for release. See [[What Is Not In This Repository]].

## Persistence

A new character produces a new save under the active generation, plus a creation checkpoint that captures its exact opening state. The character is registered with the account, and a world entry is activated for it with its reserved name and wire identity. See [[Save and Data Root]] and [[World Directory]].

## Determinism

The opening state is fixed by the template, and any values that would otherwise be random or time-based are pinned so that two creations from the same template are identical. Creating the world derives its seed from a controlled source. See [[Method Determinism]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/CharacterCreate.kt` and `FreshProfile.kt`, with registration in `server/store/AccountRegistry.kt` and world reservation in `server/store/WorldDirectory.kt`. The release-mode factory that ties creation to a born world is in `server/session/ReleaseFactory.kt`.

## How It Was Deciphered

The creation exchange was captured across names, genders, and starters, and reproduced so that the resulting save and world entry match the reference exactly. It is pinned by the [[Method Differential Harness]] and confirmed by native testing, in which a fresh install creates a character, the world is born, and the character persists across a cold login.

## See Also

- [[World and Generations]], which creation brings into being.
- [[Character Selection and Deletion]].
- [[Accounts and Sign-in]], which precedes it.
