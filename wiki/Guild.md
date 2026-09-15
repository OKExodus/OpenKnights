# Guild

**Definition.** A guild, the game's own word is "juntuan" (legion), is a shared-world organization of characters: membership and rank, donations, guild technology, a guild boss, and a personal task board, held once for the whole guild rather than duplicated in each member's own save.

## How It Works Inside

A guild is a record in the world document `guilds`, keyed by a numeric guild id; members are keyed by the participant's wire role id (role property 0). Six positions, Leader down to Member, each carry permission flags (approve, kick, notice, tech, mail, rename, transfer) and seat counts that scale with guild level. Level itself is read off accumulated popularity, and maximum members come from level plus the guild's emblem badge.

Joining goes through an application queued on the guild record and an officer's approve or reject. Donating Gold or Diamonds raises the donor's own accumulated contribution and the guild's shared resources and popularity together; only whole 10,000-Gold units are taken from a donation request, the remainder stays with the player, and a daily cap scales with player level. Guild technology is raised from the shared resource pool by an officer, capped by guild level; a character's own Guild Tech document mirrors those levels only while it belongs to a guild. The Guild Boss level tracks one specific technology (the Mascot); offline nobody can fight it, so it is always shown at full health with no cooldown. Each character keeps its own four-task board, refreshed at local midnight, with a star rating that changes donation and completion rewards; tasks are accepted, donated into, and claimed independently of the guild record. Renaming, the emblem, transferring leadership, the notice, kicking, and quitting each carry their own labeled offline policy, such as no rejoin cooldown and a leader who cannot quit while others remain.

## Opcodes

| Opcode | Role |
| --- | --- |
| `C2145` / `S2306` | My guild |
| `C2153` / `S2310` | Create |
| `C2155` / `S2312` | Apply |
| `C2159` / `S2322` | Approve or reject an applicant |
| `C2157` / `S2314` | Donate |
| `C2177` / `S2330` | Guild technology upgrade |
| `C2193` / `S2338` | Guild boss |
| `C2435` to `C2443` / `S2344` | Task board donate, refresh, accept, claim |
| `C2201` / `S2340` | Position wage |

Additional queries and world actions (members, guild list, positions, kick, transfer, notice, rename, emblem, war sign, guild mail) are listed in `Guild.kt`.

## Data Files

`juntuan_dengji.csv` (level, seats, shop unlock), `juntuan_quanxian.csv` (position permissions), `juntuan_junhui.csv` (emblem badges), `juntuan_technolegy.csv` (guild tech), `juntuan_boss.csv` (boss health by Mascot level), `juntuan_activities.csv` and `juntuan_liansheng.csv` (guild event and alliance tables).

## Persistence

Membership, positions, applications, donation totals, technology levels, the boss record, and the guild's own war and notice fields are shared state in the [[World Directory]] document `guilds`, changed through optimistic-retry updates. A member's accumulated contribution (role 30, "Donation"), its own Guild Tech mirror, and its task board (`guild_task_state`) are personal and commit a revision to the character's [[State Store]]. See [[Transactions and Publishing]].

## Determinism

The task board's daily task selection is seeded from the guild-task key and the local day; the star re-roll is seeded from the guild-task key and the request time. Guild War's registration window, the boss's open hours, and daily donation and wage resets all key off the device's local clock rather than a server clock, consistent with the project's offline-first policy.

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Guild.kt`, routed through `SocialRoutes.kt` (`guildWorld`, `guildCharacter`, `query`, `guildTask`) and `Session.socialRoute`.

## How It Was Deciphered

Every guild action was captured, reproduced, and pinned by the [[Method Differential Harness]].

## See Also

- [[Friends]] and [[Mail]], the other social systems reached through the same route.
- [[World Participants]], the roster a guild's members are drawn from.
- [[World Directory]], [[State Store]], [[Transactions and Publishing]].
