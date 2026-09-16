# Bot Player System

**Definition.** A planned system of autonomous players that populate the world so a fully offline game still feels alive: bots that own real characters, progress, join guilds, fill the arena, and chat. It is being designed and built as its own framework, in a separate repository, before it is integrated here.

> This page is a forward-looking overview. The bot framework is developed independently first, so it can be built and proven on its own, and is folded into OpenKnights once it is ready. The design detail lives in its own repository; this page records the intent and the seams already in place for it.

## Why It Exists

A preserved online game is missing its other players. Without them the world is empty: no one in the guild, no one in the arena, no chatter. The bot system fills that world with autonomous participants that behave like real players rather than static dummies, so the offline game keeps the social texture the original had. It is intended to be a standout feature, not a stopgap.

## What a Bot Is

A bot is a full participant, not a scripted prop. Each bot owns a real character with real state: a roster, equipment, progress, a place in the world. Bots draw from the same content players do, so a bot's team is a team a player could have. They act over time rather than all at once, catching up when the game runs, and they can initiate contact, appearing in the arena, joining a guild, sending a message.

## The Seams Already in Place

OpenKnights was built so the bot system can be added without reworking the core. The groundwork already exists:

- **Storage from day one.** The data root carries a bot database in every generation, backed up, restored, and migrated alongside the world and characters, even while it holds nothing yet. See [[Save and Data Root]] and [[World and Generations]].
- **Identity space reserved.** Wire identities skip a reserved bot range, so a bot's identity never collides with a player's. See [[World and Generations]].
- **Shared world progress.** World documents accept contributions from any participant, not only the player's characters, so bots can move shared progress.
- **Server-initiated delivery.** The session layer supports messages the server pushes to the client, not only replies to requests, which is what lets a bot's action reach the player live.
- **Catch-up on the device clock.** The server runs only while the app runs, so bot activity advances at start through the same settle hook the rest of the server uses, with no background service and no battery drain. See [[Method Determinism]].

## The Separate Repository

The framework is being built on its own, ahead of integration, so its design can be worked out and tested in isolation. That repository holds the bot behavior model, the roster generation, the decision-making, and the knowledge the bots reason over, including data derived from the game's own tables at patch time rather than shipped. When it is ready, it plugs into the seams above.

The link to that repository will be added here once it is public.

## Boundary

The bot system follows the same rules as the rest of the project. Any knowledge derived from the game's tables is generated from the player's own copy, never shipped, and nothing in the bot framework carries the publisher's content. See [[What Is Not In This Repository]].

Admin commands are outside the bot's input and knowledge boundary. Slash command lines are consumed privately before chat delivery, and command replies, command history, and the hidden admin-use marker are not exposed to bots. A bot may observe the ordinary gameplay result of a changed character, but it receives no admin provenance and must not detect or respond to command text in game chat.

## See Also

- [[World and Generations]] and [[Save and Data Root]], the storage the bots live in.
- [[World Participants]], the roster they join.
- [[Reconstructing a Mobile Game Server]], the method the framework is built with.
- [[Admin Commands]], the private offline command route bots do not receive.
