# State Store

**Definition.** The per-character save: one database that holds everything personal to a character, versioned by a revision that increases with every change so concurrent edits are caught and any point can be compared.

## How It Works Inside

Each character has its own save database under the active generation. It holds the character's complete personal state: the player payload the game reads, the profile, the inventory, and the progression each system writes. Every committed change increments a revision counter, and the store records a checksum of the state at each revision. A reader can therefore ask for the state at a known revision and get exactly those bytes, which is what lets the harness compare a save at a precise point and what lets the server detect that a save changed underneath an in-flight edit.

The save is authoritative for personal progress. The shared parts of a character, its place in the world, live in the [[World Directory]]; the save and the world reference each other and are kept consistent together.

## Persistence

The save is `characters/<id>/save.sqlite3` within the active generation, with a creation checkpoint beside it. Reads and writes go through a single store interface so the same code runs on a PC and on the device. See [[Save and Data Root]].

## Revisions and Checkpoints

- **Revision.** A monotonic counter. Each committed transaction increments it. It is the key the world, the registry, and the harness all use to name a known state.
- **Checkpoints.** A checkpoint captures the complete state at a revision as a separate file, so a save can be verified against a known-good copy and a restore has something exact to compare to. Creation writes the first checkpoint. See [[Character Creation]].
- **Checksums.** The store records checksums of the state and inventory at each revision, so equality is checked by content, not by raw database bytes, which differ between platforms.

## Determinism

Because every change is a revision with a recorded checksum, two runs of the same actions produce the same sequence of revisions and the same checksums. This is what the [[Method Differential Harness]] compares. See [[Method Determinism]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/store/StateStore.kt` and `SaveWriter.kt`, with the transaction model in [[Transactions and Publishing]].

## How It Was Deciphered

The save's structure and its revision-and-checksum model follow the reference store and are pinned by the harness, which compares database changes by logical content at each revision.

## See Also

- [[World Directory]], the shared counterpart of the save.
- [[Transactions and Publishing]], the revision model.
- [[Save and Data Root]], where the save lives.
