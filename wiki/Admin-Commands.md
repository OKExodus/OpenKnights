# Admin Commands

**Definition.** Admin commands let a player inspect or change the current character through short commands in in-game chat. This is an OpenKnights offline feature, with hidden save-local tracking of command use.

## How It Works Inside

A slash-prefixed chat line is parsed by `AdminCommands` before ordinary chat routing. The line is consumed privately, so it is not written to chat history, delivered to another player, or passed to a future bot player. A successful command returns private system lines to the issuing session. Invalid syntax, unknown identifiers, and rejected game rules make no state change and do not set the marker.

Commands work from level one. The patched client sends chat to the server without its original minimum-level gate; the server applies that same configured gate to ordinary chat after intercepting commands.

The supported forms are:

| Command | Meaning |
| --- | --- |
| `/additem ItemID Quantity` | Add an item stack. |
| `/addhero HeroID` | Add a base-form hero at its normal starting growth. |
| `/addgold Quantity` | Add Gold. |
| `/adddiamonds Quantity` | Add Diamonds. |
| `/setlevel Level` | Set the character level. |
| `/setvip Level` | Set VIP level and the exact cumulative VIP points needed to reach it. |
| `/completequest [QuestID]` | Complete an active quest, or the current quest when omitted. |
| `/setcastle Level` | Set the castle level. |
| `/setwarehouse Level` | Set the warehouse level. |
| `/levelhero Slot Level` | Set the hero level in a formation slot. |
| `/evolvehero Slot` | Evolve the hero in a formation slot. |
| `/createguild "Name"` | Create a guild while bypassing the normal creation cost. |
| `/leaveguild` | Leave when native guild rules allow it. |
| `/disbandguild confirm` | Disband the leader's guild after confirmation. |
| `/newcharacter "Name" male\|female Jansen\|Rhee\|Talia` | Create a character with the selected gender and starter. |
| `/formation` | Privately list the current formation. |
| `/help [Command]` | Show command usage. |

Every successfully executed command, including `/help`, `/formation`, debugging commands, and `/newcharacter`, sets the hidden admin-use marker. Character creation flags both the issuing character and the new character. A backup made before the first successful command remains unflagged when restored. The marker is paired with command history as tamper evidence, but neither is intended to be tamperproof.

Command names, genders, and starter choices ignore capitalization. Numeric arguments need no quotes. Quotes group guild names containing spaces. Character names retain the existing creation rules: letters, digits, dot, underscore, and hyphen, within the normal name length limit. For example, `/newcharacter "NewHero" female Talia` creates a normal starter character. The original character remains selected.

Formation slots are numbered 1 through 6; `/formation` lists their occupants. `/addhero` accepts a base hero ID or a packed hero template and grants grade one with no super-class digit, at level one and the catalog's default growth. Leader-class heroes are excluded because the game requires one leader per character; use `/newcharacter` to choose another starter. `/evolvehero` advances one configured evolution step without charging materials or Gold, including configured star and super transitions. Hero levels and building levels stay within their configured limits. Lowering a warehouse retains existing inventory capacity to protect stored items and purchased space.

`/setvip 0` sets zero accumulated VIP points. Higher levels use the minimum total that normal top-up progression recognizes as that level. The command refreshes the VIP reward ladder and current VIP quest without resetting claimed rewards, the current quest row, or collection requirements. A positive VIP grant qualifies for the refill prerequisite without creating a purchase or granting Diamonds. Daily claims remain claimed, and today's AP and Energy purchases stay counted through VIP changes.

## Opcodes

| Opcode | Role |
| --- | --- |
| `C449` / `S480` | The existing chat request and reply transport. Commands are intercepted before shared delivery. |

Admin commands add no network opcode. Their private replies use the existing chat reply framing.

## Data Files

Commands reuse the tables of [[Acquisition and Items]], [[Heroes]], [[Hero Evolution]], [[Player Level]], [[Castle]], [[Warehouse]], and [[Quests and Bounty Board]]. Character creation uses the same fresh profile as [[Character Creation]]. Table contents come from the player's game files; see [[Data File Index]] and [[What Is Not In This Repository]].

## Persistence

The hidden marker belongs to the character save and is written with the command's successful state change. The save also records a private command history for future detection or policy use. Restoring an earlier backup restores its earlier marker state, including an unflagged state when no command had yet succeeded. No separate live record overrides the restored save.

The guild portions of a command edit the shared `guilds` world document in the same transaction as the issuing character's marker. Commands do not append chat lines, create bot events, or expose admin provenance in player-facing payloads.

Character creation publishes multiple files. Once its arguments, name, and availability pass validation, the issuing character is marked before creation starts. If publication is interrupted, that character conservatively remains flagged. The new character is flagged in its initial save and checkpoint. A disband confirmation prompt also counts as command use; confirmation expires after 60 seconds and is bound to the same guild and session.

## Determinism

The command route uses the service clock for guild operations and confirmation expiry. New characters use the existing character-creation identity and signature generation. Grants and hero changes reuse the existing catalog rules. See [[Method Determinism]].

## Code

Parsing and usage text live in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/AdminCommands.kt`. `AdminGameplay` and `AdminVip` in the same directory plan gameplay changes. Guild mutations are isolated in `AdminGuild.kt`; `Session.adminCommand` owns dispatch and private replies. `server-core/src/main/kotlin/io/github/okexodus/openknights/server/store/AdminProvenance.kt` records save-local evidence, and `AdminWorldTransactions.kt` coordinates guild commits. Ordinary chat delivery remains in `Chat.kt` and `SocialRoutes.kt`.

## How It Was Deciphered

This is a deliberate offline feature designed from the existing chat interception hook and the current character, world, and acquisition transaction boundaries. It is not claimed as captured behavior from Pocket Knights. Existing guild validation and character-creation validation are reused where the command policy calls for valid game rules.

Inspection of `GameStateChat::HandleMenuSend` identified the original minimum-level comparison against property 298. The checked branch replacement is documented in `patches/native/admin-chat.S`. Emulator validation confirmed `/setvip 10` on a low-level character, the visible VIP 10 badge, and normal claiming of the refill reward afterward. The restart backup retained VIP 10, exactly 5,000,000 VIP points, and admin evidence on the subsequent normal quest claim; the other character remained unflagged.

The command parser, guild checks, and save provenance have focused automated tests. `server-core/src/test/kotlin/io/github/okexodus/openknights/server/session/AdminSessionIntegrationTest.kt` exercises authenticated commands using a maintainer's local APK and release data, checking saved results, exact VIP thresholds, and isolation from another session and shared chat. See [[Method Differential Harness]] for the separate proof of the reused game systems and [[What Is Not In This Repository]] for local test inputs.

## See Also

- [[Chat]], the private interception point and transport.
- [[Bot Player System]], the future bot boundary.
- [[Guild]], the native guild document and rules.
- [[Character Creation]], the gender and starter validation.
- [[Save and Data Root]], backup and restore behavior.
- [[Transactions and Publishing]], commit ordering.
