# Sweep

**Definition.** A group of otherwise unrelated actions carried by the sweep route: the album, signature, Warehouse capacity repair, totem lineup, temporary VIP claim, and the Event Hall shop, dispatched before Goals.

## How It Works Inside

The sweep route answers a fixed set of opcodes, each with its own small planner: reading and activating an album family once its member cards are held, setting the account signature text, raising opened bag capacity (and the Warehouse building itself) to the account's already-unlocked limit, choosing which totem leads the lineup, claiming the one-time temporary VIP window, and rolling or buying from the Event Hall shop. A handful of closed-event opcodes are stateless: they read nothing and reply with a fixed closed-event frame rather than reach the character's state at all.

Every stateful planner first computes what the action would produce and compares it against the character's current state. When the request would change nothing, for example the same totem chosen again or a signature resubmitted unchanged, the planner throws `Unchanged` with the reply frames already built; the route returns them without asking the store to commit anything. This mirrors the reference server's own behavior: a sweep action is idempotent, not merely allowed to repeat.

## Opcodes

C77 (Warehouse slot), C513 (album activate), C515 (album info, stateless read), C577 (signature), C1649 (Event Hall great offer spin), C2529 (totem lineup), and C25 (temporary VIP claim) are the state-changing and stateless actions carried by the sweep route; C1633, C1671, and the closed-event opcodes reply with a fixed frame and touch no state. All are dispatched through `Session.sweepRoute`. See the [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `totem.csv` | Totem roster and stats |
| `totem_jinhua.csv` | Totem evolution track |
| `totemexp.csv` | Totem leveling curve |

The album's `tujian.csv` and the Event Hall's own tables are read alongside them; see [[Events and Event Hall]] and the [[Data File Index]].

## Persistence

A sweep action that changes the character's state commits one revision to the character's [[State Store]]; an action the planner finds unchanged, such as reselecting the active totem or resubmitting the same signature, writes no revision and returns its reply frames directly. See [[Transactions and Publishing]].

## Determinism

The Event Hall great offer spin reached through the sweep route draws from a seed built from the request payload, the character's current revision, and the offer window, so a repeated spin under the same state reproduces the same draw. See [[Method Determinism]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/SweepFeatures.kt`, routed from `Session.sweepRoute`.

## How It Was Deciphered

Each sweep action was captured at both its changing and its unchanged edge, including the closed-event stateless replies, and reproduced so the reply frames and the revision behavior (written or skipped) match the reference. It is pinned by the [[Method Differential Harness]].

## See Also

- [[Events and Event Hall]], which shares the route.
- [[Heroes]], the source of totem lineup and Event Hall shop interactions.
- [[Daily Missions]], run after Sweep in the daily sequence.
