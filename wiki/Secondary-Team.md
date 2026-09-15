# Secondary Team

**Definition.** A second hero-slot team, parallel to [[Alternate Team]], meant for characters derived from an imported save rather than created fresh; it shares the unlock and replace opcodes with the alternate team but is dispatched separately for that character kind.

## How It Works Inside

SecondaryTeam.kt supplies the foundations both team kinds are built on: reading a hero's owned uid and template out of its typed field list, collecting a character's owned heroes into a uid-keyed map, and the encode and decode of the S3745 payload (position, full hero fields, open-position count) both teams reply with. It also holds the client's own duplicate-hero rules: a hero base carries a HeroConfig flag that can bypass every duplicate scan, and a table of rebirth triples relates bases so a related hero cannot hold both the main lineup and an alternate or secondary slot at once. These rules load only from the two bundled tables whose bytes match the supported build, so a modified table cannot silently change what counts as a duplicate.

For a fresh, in-game-created character, unlock and placement run through [[Alternate Team]]. For a character derived from an imported save the equivalent unlock and replacement is not yet reproduced: the routes exist but currently answer as not implemented, pending the captured import and replacement policy that derived characters need.

## Opcodes

| Opcode | Role |
| --- | --- |
| C3777 | Unlock (derived character; not yet reproduced) |
| C3779 | Replace (derived character; not yet reproduced) |

Reached through `Session.secondaryUnlockRoute` and `Session.secondaryReplaceRoute`. See [[Opcode Index]].

## Data Files

The native lineup rules are audited against `hero.csv` and `zhuansheng.csv` by their exact bytes. See [[Data File Index]].

## Persistence

The secondary-team routes commit nothing yet; the shared helpers they will use are exercised live through [[Alternate Team]]'s revisions to the character's [[State Store]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/SecondaryTeam.kt`, routed (as stubs, for derived characters) from `Session.secondaryReplaceRoute` and `Session.secondaryUnlockRoute`.

## How It Was Deciphered

The shared hero decoding, payload codec, and native lineup rules were captured and reproduced, and are exercised live through [[Alternate Team]]. The derived-character unlock and replacement policy itself was captured but is not yet reproduced; the routes are labeled accordingly rather than guessed. Pinned by the [[Method Differential Harness]] for the parts that are reproduced.

## See Also

- [[Alternate Team]], which reuses these foundations for fresh characters.
- [[Formation]].
