# Method: CSV Decryption

**Definition.** How the game's data tables are recovered from their shipped form and read, and the strict boundary that keeps their contents private.

The game ships its balance and content as a large set of tables. See the [[Data File Index]] for what they are. This page is about how the tables are decoded and parsed. It is not about their contents, which are the publisher's and are never published. See [[What Is Not In This Repository]].

## The Shipped Form

The tables are not plain text on disk. They are packed and obscured inside the game so they are not casually editable. Recovering a table means undoing that packing to reach the row-and-column data the game itself reads at run time.

## How the Format Was Deciphered

The format was learned the same behavior-first way as the protocol:

1. **Locate.** Find where in the game the tables live and how the game addresses one by name.
2. **Observe the transform.** Watch how the game turns the packed bytes into rows it uses, and reduce that transform to its essential steps.
3. **Reproduce and verify.** Reimplement the transform, then confirm that the rows it yields are the rows the game acts on, by tying a table value to an observed in-game outcome.

The result is a reader that, given the player's own game files, produces the same rows the game uses, entirely from the player's own copy.

## Where the Reader Lives

The reconstruction reads the tables through the game-data module. A system page names the specific tables it consumes, and the [[Data File Index]] groups them by system. The reader is generic: it decodes any table by name and hands back rows; each system then interprets the columns it cares about.

## The Boundary, Restated

- The **method** and the **format** are documented. The **decrypted rows are not**.
- A data-file page lists a table's columns by name and meaning and the keys that join it to other tables. It never lists values.
- The tables are read at patch time or run time from the copy of the game the player supplies. Nothing derived from them is shipped in this repository.

This is the same principle the whole project follows: publish the understanding and the original implementation, never the publisher's content.
