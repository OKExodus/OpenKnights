# Transactions and Publishing

**Definition.** The rules that keep every saved change safe: each change is one transaction that moves a save from one revision to the next, and every file is published atomically so a crash never leaves a half-written state.

## How It Works Inside

A game action that changes a character is not written piecemeal. It is a single transaction: the store reads the current state at its revision, applies the change, and commits a new state at the next revision, or it does nothing. The revision is what makes this safe under concurrency. If the state moved since it was read, the commit is refused and the action is retried against the new state, so two changes never silently overwrite each other. See [[State Store]].

Files are written by the same conservative rule everywhere: a complete new file is written under a temporary name in the same directory, flushed to disk, and then renamed into place, so a reader sees either the old file or the whole new one and never a mixture. This is the atomic publish, and it is quoted in full on [[Save and Data Root]].

## The Revision Model

- Every save carries a revision, a counter that increases by one per committed transaction.
- A transaction names the revision it expects. A mismatch means someone else committed first, and the transaction retries.
- The committed transaction records enough to reconstruct and compare the change, including, where relevant, the inputs that seeded any randomness, so the exact result can be regenerated. See [[Method Determinism]].

## Why It Is Built This Way

The revision model and the atomic publish together give two guarantees the reconstruction depends on. Consistency: a save is always at a whole revision, never between two. Crash safety: an interrupted write leaves the previous complete state, recoverable rather than corrupt. These are also what make the [[Method Differential Harness]] able to compare a save at a precise, named point.

## Persistence

The mechanism underlies every database in the data root: the character saves, the world, the registry, and the bot database. See [[Save and Data Root]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/store/Publish.kt` (the atomic publish) and the transaction handling in `StateStore.kt` and `WorldDirectory.kt`.

## How It Was Deciphered

The transaction and publishing rules follow the reference store and are pinned by the harness, which checks the files and backups an action produces and compares database changes by logical content at each revision.

## See Also

- [[State Store]] and [[World Directory]], which commit through this model.
- [[Save and Data Root]], where the atomic publish is quoted.
