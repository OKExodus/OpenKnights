# Warehouse

**Definition.** Selling owned items for Gold and holding the sold stacks in a buy-back list the player can reclaim from, at a Gold cost above the sale price.

## How It Works Inside

Selling an item removes units of an owned stack, pays Gold at the item's catalog sell price times the count, and adds a new entry to the character's buy-back list; a repeat sale of the same item opens another entry rather than merging into an existing one. Buying back spends units of a buy-back entry, at the sale price scaled by a fixed buy-back permille, and grants the item back; a buy-back that empties an entry's count drops the entry, and a partial buy-back keeps it with the remainder. A separate delete operation removes units of an entry without granting or refunding anything, checked against the same preconditions as a buy-back, and a plain list reply reports the whole buy-back list on demand. A character that has never sold locally starts with an empty list.

## Opcodes

| Opcode | Role |
| --- | --- |
| C83 | Sell |
| C85 | Buy back |
| C87 | Delete units of a buy-back entry |
| C89 | List the buy-back entries |

Replies include S74 (the sell reward), S3008 (the whole buy-back list), and the item and Gold updates. See [[Opcode Index]].

## Data Files

Warehouse reads an item's sell price from the item catalog that [[Acquisition and Items]] documents; it has no table of its own.

## Persistence

A sale, buy-back, or delete commits one revision to the character's [[State Store]], including the buy-back list document. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Warehouse.kt`.

## How It Was Deciphered

Sell and buy-back were captured and reproduced so the buy-back list, the consumed and granted items, and the Gold changes match the reference. The delete route was never captured; it is built from the client's own code and reasoned to share the buy-back preconditions, and is labeled as such.

## See Also

- [[Gear and Equipment]], [[Acquisition and Items]].
