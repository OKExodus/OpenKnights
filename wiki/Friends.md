# Friends

**Definition.** The friend list, pending requests, recommendations, and praise: a per-pair relationship recorded once in the shared world rather than twice, once for each side.

## How It Works Inside

Friend relations live in the world document `social`, keyed by wire role id. Adding a target puts them on the requester's own list immediately and leaves a pending entry on the target's side; the target only becomes a mutual friend once they reply and accept, which needs a free slot on both lists. Removing a friend clears the relation on both sides at once. A player's maximum friend count is a fixed base plus a VIP-level bonus, read fresh at login and written into the character's own list-size field.

Praise is the pair-cooldown action: praising a friend pays Pal Points to the truthy pair sharing a cooldown keyed by both role ids, feeds a praise achievement counter, and sends the target a small mail carrying the same points again as a note, so a friend who is not looking still receives the points once their mail is claimed or opened. A repeated praise within the cooldown, or one aimed at a non-friend, pays nothing and returns the failure result without touching state. Recommendations and the player card reuse the same participant list that friends and mail both read, so a name looked up for a friend request, a mail recipient, or a chat target all resolve the same way.

## Opcodes

| Opcode | Role |
| --- | --- |
| `C353` / `S384` | Pending requests |
| `C355` / `S386` | Recommendations or campaign helpers |
| `C359`, `C361` / `S388` | Add by id or by name |
| `C363` / `S390` | Reply to a request |
| `C365` / `S396` | Remove |
| `C385` / `S416` | Player card |
| `C387` / `S418` | Praise |

## Persistence

Friend lists, pending requests, the campaign-helper timers, and the pair praise timestamps are shared state in the [[World Directory]] document `social`. Praise also grants Pal Points and an achievement step, which commit a revision to the character's own [[State Store]]. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Friends.kt`, dispatched from `SocialRoutes.friends` in `SocialRoutes.kt`.

## How It Was Deciphered

The add, reply, remove, and praise flows were captured, reproduced, and pinned by the [[Method Differential Harness]].

## See Also

- [[Guild]], sharing the same world participant list.
- [[Chat]], the private-message path that resolves names the same way.
- [[Mail]], the delivery path for a praise note.
