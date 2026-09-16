# Chat

**Definition.** In-game chat across the system, world, guild, and private channels, replayed to a viewer's client on login and pushed live to everyone else who should see a line.

## How It Works Inside

A chat line names a channel (system, world, private, or guild), an optional target, and text. World and guild lines are appended to a per-channel history in the world document `chat`, each list kept to its last 20 entries; private lines are appended to a per-recipient history. Sending resolves the delivery list for the request: a world line goes to everyone online, a guild line to the sender's guild members, a private line to the named recipient plus an echo back to the sender showing their own outgoing copy. A recipient who has blocked the sender's name, through the same blacklist [[Mail]] checks, never receives the push. At login, the world history, the character's guild history if any, and the character's own private lines replay in order, again skipping any sender the viewer has blocked; a viewer's own lines always replay regardless.

A line that starts with "/" is never stored and never reaches a channel. It is consumed by the private admin-command route and its reply is returned only to the issuing session. The command line is not replayed on login, delivered to other players, or passed to future bot players. See [[Admin Commands]] for the deliberate offline command set and its hidden save marker.

The authenticated server route checks `property.csv` row 298 for ordinary chat's minimum character level. The client-side gate is bypassed by [[The Patcher]] so commands work from level one. This preserves the native ordinary-chat restriction while allowing the server to distinguish commands.

## Opcodes

| Opcode | Role |
| --- | --- |
| `C449` / `S480` | Send a chat line, replayed to each recipient |

## Persistence

Chat lines are shared state in the [[World Directory]] document `chat`: `world` for the world channel, `guild` keyed by guild id, `private` keyed by recipient. Sending a normal line changes only this shared document. Admin command lines are not stored there; successful commands instead update the character's hidden admin marker and private command history as part of the command transaction. See [[Admin Commands]] and [[Transactions and Publishing]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Chat.kt`, dispatched from `SocialRoutes.chat` in `SocialRoutes.kt` and routed from `Session.socialRoute`.

## How It Was Deciphered

The channel framing, the history limit, and the blocked-sender replay skip were captured, reproduced, and pinned by the [[Method Differential Harness]].

## See Also

- [[Friends]], the name lookup private chat shares.
- [[Guild]], the membership list a guild line delivers to.
- [[Mail]], the blacklist chat delivery checks.
