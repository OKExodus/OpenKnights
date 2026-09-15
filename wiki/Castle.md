# Castle

**Definition.** The player's home base: Collect income, buildings and their Magic House tech, the Alchemy Lab's shard Transmute and refresh, recruit slots and their Work/Release/Guard actions, and Card Sacrifice and Reforge (card reset).

## How It Works Inside

The Castle document tracks the day's Collect counts (reset at local midnight), the Transmute timers, and the recruits' countdowns; buildings and their levels live on the character's own state and unlock as the player's level and the Castle's own level allow. Collect pays Diamonds for Gold, Honor, or Runes at an escalating cost and a rolled multiplier; raising a building spends Gold and, for the Castle itself, also raises the Magic House and Alchemy Lab to match and opens newly reachable Magic House techs. The Alchemy Lab holds three shard slots that regenerate an attempt over time; Transmute spends the shown shards for Gold (with a bonus on a triple match) and draws new ones, and a refresh either waits out its cooldown or pays Diamonds to redraw early. Recruits earn Gold on a schedule tied to the player's and the Alchemy Lab's level and can be released or put on guard.

Card Sacrifice and Reforge, reached through the Hidden Training menu, retire a hero, gear, or jewelry card for Diamonds. Sacrifice returns the card at level one; Reforge destroys it and pays soul points instead. Both return the investments the card accumulated (upgrade materials, evolution shards, Power Up, enchant, Astral skill points, fusion, and rebirth) computed component by component from the card's own history, and both refuse a card that is equipped, leading, out on Hero Set Out, or below the level and tier floor.

## Opcodes

Collect (C161), building Evolve (C545), Magic House tech (C97), personal Guild Tech (C2179), Transmute (C739), Alchemy Lab refresh (C737), recruit slot purchase (C769), and recruit Work/Release/Guard (C745/C749/C781) are Castle actions; Sacrifice and Reforge are C3723 (hero), C3725 (gear), and C3727 (jewelry). All route through `Session.dailyRoute`. See the [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `building.csv` | Building unlock levels, upgrade costs, and caps |

Magic House tech, the Alchemy Lab shard weights, and the reset-return tables are read alongside it; see the [[Data File Index]].

## Persistence

A Castle or Card Sacrifice/Reforge action commits one revision to the character's [[State Store]]. See [[Transactions and Publishing]].

## Determinism

Collect's reward multiplier, Transmute's and refresh's new shards, and Card Reforge's outcome all draw from a `random.Random` seeded from a SHA-256 digest of the action's identifying parts, so a repeated capture reproduces the same draw. See [[Method Determinism]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Castle.kt` and `CardReset.kt`, routed through `DailyRoutes` and `Session.dailyRoute`.

## How It Was Deciphered

Collect, building and tech upgrades, Transmute, refresh, and the recruit actions were captured at their cost boundaries and reproduced so the resulting resources and document state match the reference. Card Sacrifice and Reforge were captured across hero, gear, and jewelry cards at ordinary and edge levels, matched against the client's own preview calculations, and pinned by the [[Method Differential Harness]].

## See Also

- [[Hidden Training]], which hosts the Sacrifice and Reforge menu.
- [[Heroes]] and [[Acquisition and Items]], the source of what a card returns.
