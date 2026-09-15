# Save and Data Root

**Definition.** The single directory that holds everything a player's install persists: the account, the world, and every character save. It is designed so that a crash or a power loss can never leave it half-written.

## How It Works Inside

Everything the server keeps for one install lives under one data root. Inside it, the born world is stored as a generation folder, and within that generation are the account registry, the world database, the bot database, and each character's save and creation checkpoint. Around those sit the working folders the data root uses to stay safe: automatic backups, a trash for retired generations, logs, a clock file, and a manifest that names the active generation.

The store is deliberately conservative. Databases are copied at a single consistent moment rather than while they are changing, files are published atomically so a reader never sees a partial file, and stored paths are relative to the data root so a save can move to another device and still open.

## Layout

```
<data root>/
  worlds/<generation>/
    registry.sqlite3        the account and character ownership
    world.sqlite3           the shared world state
    bots.sqlite3            the bot roster (empty until bots exist)
    characters/<id>/save.sqlite3
    characters/<id>/checkpoint-0001.sqlite3
  auto-backups/             rolling safety copies
  trash/                    retired generations and deleted characters
  logs/
  clock.json                the device clock the world was born under
  manifest.json             names the active generation
  service.lock              one server per data root
```

## Crash Safety

Two rules keep the root consistent:

- **Atomic publish.** A new file is written under a temporary name in the same directory, flushed, then renamed into place. A crash either leaves the old file or the complete new one, never a mixture.
- **One consistent moment.** The databases reference each other, so a backup or a restore copies all of them at a single point rather than at different times.

The atomic publish, condensed from `server-core/.../store/Publish.kt`, is the whole rule in one routine:

```kotlin
fun publishNew(temporary: Path, target: Path): Path {
    require(temporary.parent == target.parent)   // the rename must stay on one volume
    fsyncFile(temporary)                          // the complete file is on disk first
    val claim = target.resolveSibling(target.fileName.toString() + CLAIM_SUFFIX)
    Files.createFile(claim)                       // create-new: exactly one publisher wins
    try {
        if (Files.exists(target)) throw FileAlreadyExistsException(target.toString())  // never overwrite
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        fsyncDirectory(target.parent)
    } finally {
        Files.deleteIfExists(claim)
    }
    return target
}
```

See [[Transactions and Publishing]] for the revision model that sits on top of this.

## Save Management, Offline

On the phone, save management is done through the player's own files rather than an in-game menu:

- **Backup** happens automatically. On each launch the app writes a rolling snapshot of the whole world to a public folder the player can browse and copy, with no permission and no root.
- **Restore** is done by opening a backup with the app. It is staged and applied at the next start, before the server opens the root, so it never races the live databases, and it only swaps a restored world in on success.
- **Reset** is the platform's own clear-data action, which empties the root so the next start is a brand-new unborn world.

The live databases stay in private storage; only inert snapshots are exposed. See [[World and Generations]].

## Determinism and Portability

Stored paths are always relative to the data root, so a backup restores on another phone. The clock the world was born under is recorded so time-based state can be reproduced. See [[Method Determinism]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/store/`, chiefly `DataRoot.kt`, `Publish.kt`, `SaveManagement.kt`, `SaveWriter.kt`, `StateStore.kt`, and `WorldDirectory.kt`. The on-device backup and restore integration lives in `server-android`.

## How It Was Deciphered

The layout and the safety rules follow the reference store, and are pinned by the [[Method Differential Harness]], which compares database changes by logical content and checks the files and backups an action produces. The offline backup and restore paths were confirmed by native testing on a device.

## See Also

- [[World and Generations]].
- [[Transactions and Publishing]].
- [[State Store]] and [[World Directory]].
