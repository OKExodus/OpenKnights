# What Is Not In This Repository

**Definition.** The single, authoritative statement of the boundary between what OpenKnights publishes and what it deliberately keeps out. Every page that describes a data file, an asset, or a decryption method links here.

OpenKnights is a preservation and interoperability project. It documents how Pocket Knights works and reproduces the server behavior in original code. It does not redistribute the game. This distinction is deliberate, and it is the line that every contributor holds.

## The Rule in One Sentence

We publish method, structure, and our own implementation. We never publish the game's data, code, or art, and we never publish anything that would let someone play without owning the game.

## Not in This Repository, and Why

| Category | Examples | Why it stays out |
| --- | --- | --- |
| Original program | The publisher's APK, its split APKs, its native libraries, its decompiled code | It is the publisher's copyrighted software. The patcher reads it from the copy you supply and never contains it. |
| Decrypted game data | The decrypted CSV tables, the game's balance and content values | The values are the publisher's content. We document what a table means and how it relates to others, never its rows. |
| Game art and audio | Hero models, icons, tier art, sound | Copyrighted assets. The player site handles imagery under its own separate policy. |
| Release data | The cleaned fresh-character template and day-zero frames the patcher ships | Capture derived. It stays private until the release phase, and is reviewed before it is ever shipped. |
| Redeem and gift codes | Any past, present, or future code, in any form | No code is ever public or player visible. Shipped builds store only salted hashes. |
| Private operations detail | Test-device identifiers, capture-session specifics, internal operator notes | Not useful to the public and not ours to expose. Described only in generic terms. |

## What We Do Publish

- This wiki: how each system behaves, the opcodes, the data-file meanings and relations, and the methods used to learn all of it.
- The reconstruction: the server implementation, the patcher, and the on-device integration, all as original code in this repository.
- The proofs, in the form the harness and native testing produce, so a claim can be checked.

## Showing Code

Wiki pages carry code snippets, and the boundary decides which code:

- **Our code, shown freely.** The reconstruction is ours and is published under the repository's license. System and method pages quote it directly to show how a behavior is reproduced: the deterministic generator, the float model, the atomic-publish routine, a route handler, the patcher's own edits. Every snippet names the file it came from.
- **The game's code, never.** The publisher's original code, decompiled or otherwise, is never quoted. A page explains how a behavior works and reproduces it in our own code; it does not paste theirs.
- **Interoperability references are facts, not code.** The name of a game class or method the patcher redirects, an opcode number, a table name and its columns: these are the interface the reconstruction talks to, and naming them is how interoperability is documented. They are stated as references, without the game's implementation behind them.

## How Pages Respect the Boundary

- A [[data-file|Data File Index]] page lists a table's columns by name and meaning and its relations to other tables. It does not list the rows.
- A [[decryption|Method CSV Decryption]] page documents the file format and how it was deciphered. It does not include a decrypted payload.
- An [[opcode|Opcode Index]] page documents the wire layout of a message. It does not embed captured traffic that carries private values.

If you are writing or reviewing a page and you are unsure whether something crosses the boundary, treat it as private and ask. The cost of leaving a detail out is small. The cost of publishing the wrong thing is not.
