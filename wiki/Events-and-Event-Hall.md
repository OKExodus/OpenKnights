# Events and Event Hall

**Definition.** The rotating limited-time content: the Event Hall and its activities, such as the Magic Pie, item combination, exchanges, and special offers, along with the schedule that turns events on and off.

## How It Works Inside

Events are time-bounded activities that appear in the Event Hall. A schedule defines which events are active in a given window, and each active event exposes its own actions: visiting and claiming from an event, spinning an offer, combining items, exchanging for rewards, and so on. The server checks that an event is active and that the action is allowed within it, applies the reward or the exchange, and records the result.

Because events are time-bounded, they depend on the clock, and in the offline edition that clock is the device's local clock. An event window and a daily reset are both keyed on local time, not on a server's time. This is deliberate: it is what lets events run fully offline. See [[Method Determinism]].

## Opcodes

The Event Hall actions and the sweep-style event actions are routed through the daily and sweep routes and documented on their opcode pages. See the [[Opcode Index]] and [[Sweep]].

## Data Files

| Table | Role |
| --- | --- |
| `huodongbiao.csv` | The event schedule |
| `huodongta.csv`, `huodongtajifen.csv`, `huodongta_image.csv` | The event tower and its points |
| `huodongyingxiong.csv` | Event heroes |
| `mofadangao.csv` | The Magic Pie |

`huodong` is the game's word for activity or event; these tables define the rotating content. See the [[Data File Index]].

## Persistence

An event action commits one revision to the character's [[State Store]]. An action that changes nothing, such as a closed event queried while inactive, writes no revision. See [[Transactions and Publishing]].

## Determinism and the Clock

Event windows and their resets are computed from the device's local clock. A window keyed by mistake on raw universal time rather than local time is a bug, and the reconstruction keys them on local time so that an event opens and closes at the right local moment. See [[Device clock]] under [[Method Determinism]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/EventHall.kt` and `Events.kt`, routed through `DailyRoutes` and `Session.sweepRoute`.

## How It Was Deciphered

Event actions were captured within their active windows and reproduced so the rewards and the saved state match the reference. The clock-dependent behavior was pinned by recording the clock in force during capture, and the whole is checked by the [[Method Differential Harness]]. The reconstruction was also rebuilt from the game's own event tables where the past events could be reconstructed from the shipped data.

## See Also

- [[Sweep]], which shares the route.
- [[Daily Missions]] and [[Claims]].
