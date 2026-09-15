# Arena

**Definition.** The Arena ladder outside combat: the panel, the top-list, and the daily rank reward with its once-a-day settlement. Challenging an opponent is fought by the [[Battle Engine]]; this page covers everything around that fight.

## How It Works Inside

The ladder is the shared world's participant list, kept in the world document `arena_ladder` as a stored rank order. Ranks are the stored order first, then any participant not yet ranked is appended at the bottom in world order, so a new character always joins last; among participants never yet ranked, bots sort before characters by an optional seed, then by roster order. The opponent panel shows up to ten rows: the top six ranks, then the four ranks below the requester's own, matching the live client's shape once a player is past rank seven.

Settlement runs at 22:00 on the device's local clock. At the first request at or after that time, a character's reward rank becomes whatever rank it held at settlement and its claim reopens; a character that joined after the most recent settlement carries reward rank zero and cannot claim until the next one passes. The daily reward reads that settled rank against the Arena reward tiers, pays Gold, Reputation, and an optional item once, and raises a reputation achievement step.

## Opcodes

| Opcode | Role |
| --- | --- |
| `C417` / `S448` | Open the panel |
| `C421` / `S450` | Top list |
| `C423` / `S452` | Claim the daily reward |
| `C419` | Challenge, fought by the [[Battle Engine]] |

## Data Files

`arena.csv` (reward tiers by settled rank). `pvp_kuafu.csv` and `kuafuzhanjiangli.csv` describe the game's cross-server arena and its settlement rewards; the offline ladder is local-world only and does not read them yet.

## Persistence

The ladder order is shared state in the [[World Directory]] document `arena_ladder`. A character's settlement history and its claimed flag are personal (`arena_state`) and commit a revision to the [[State Store]] when the daily reward is claimed. See [[Transactions and Publishing]].

## Determinism

Settlement time is computed from the device's local clock, not a server clock, taking the local UTC offset in force at each candidate 22:00 so a clock-offset change around midnight still resolves to the correct day. A newly ranked bot's ladder position is seeded rather than random, keeping ladder order stable across replays.

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Arena.kt`, routed from `Session.rankRoute` for the ladder and through the social and daily routing for the panel and reward.

## How It Was Deciphered

The ladder ordering, the panel row selection, and the 22:00 settlement were captured, reproduced, and pinned by the [[Method Differential Harness]].

## See Also

- [[Formation]], which the Arena roster reads for lineup and captain.
- [[Battle Engine]], which fights an Arena challenge.
- [[World Participants]], the source of every ranked participant.
