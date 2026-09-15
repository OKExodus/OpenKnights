# Page Template

**Definition.** The fixed layouts every wiki page follows. Copy the relevant block when adding a page, keep the section order, and remove nothing. Consistency is the point: a reader who has read one system page can read all of them.

House rules for every page:

- No em-dashes and no emoji, anywhere.
- Headings, navigation labels, and table column headers use Title Case. Body prose is sentence case. Proper nouns and acronyms keep their own casing, such as OpenKnights, SQLite, VIP, CSV, and f32.
- Open with a bold **Definition.** line of one or two sentences.
- Cross-link generously with `[[Page Name]]`. A link to a page that does not exist yet is fine and marks work to do.
- Cite code by repository-relative path and the class or function name, not by line number, because names survive edits.
- Every behavioral claim names how it is known: a capture, a native proof, or a differential-harness bundle. See [[Method Differential Harness]].
- Where a snippet of our own code makes a behavior concrete, include it and name the file it came from. Never quote the game's code; reference its class, opcode, or table names as interoperability facts instead. See [[What Is Not In This Repository]].
- Anything touching private data links to [[What Is Not In This Repository]].

## System Page

```
# <System Name>

**Definition.** One or two sentences: what this system is, in the game.

## How It Works Inside
The mechanics and the state the system owns.

## Opcodes
The messages that drive it, each linked to its opcode page.

## Data Files
The tables it reads, each linked to its data-file page.

## Persistence
Which databases and documents it reads and writes, and the revision it produces.

## Determinism
RNG draws, clock inputs, and ordering, if any. Link [[Method Determinism]].

## Code
Reproduced in `server-core/.../game/<File>.kt` (and the store files it uses).

## How It Was Deciphered
The evidence and the method pages that back this entry.

## See Also
Neighboring systems and opcodes.
```

## Opcode Page

```
# <C or S><number> - <Name>

**Definition.** One sentence: what this message does.

- **Direction and number:** client to server (C) or server to client (S), the number.
- **Belongs to:** the system page or pages.

## Request Layout
A table of field, type, and meaning, following the framing.

## Replies
The server messages sent in response, in order.

## Preconditions and Refusals
What causes the server to reject the request.

## Reads and Writes
The data files consulted and the state changed.

## Code
The route handler that reproduces it.

## How It Was Deciphered
Capture diff, decode method, and the bundle that pins it.

## See Also
Related opcodes.
```

## Data-File Page

```
# <name>.csv

**Definition.** What this table holds.

## Format and Parsing
How the file is encoded and how it is read. Link [[Method CSV Decryption]]. Values are never listed here; see [[What Is Not In This Repository]].

## Columns
Name, type, and meaning. Structure only.

## Relations
Which columns key into which other tables.

## Consumed By
The systems that read it.

## Read in Code
Where the loader lives.
```
