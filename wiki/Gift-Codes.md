# Gift Codes

**Definition.** Redeeming a text code for a fixed reward, once per character.

## How It Works Inside

A code request is normalized (its surrounding whitespace stripped, its case folded) and looked up against a loaded code table by the digest of that normalized text, never by the text itself. A match's reward may include role currencies (Diamonds, gold, Stamina, Energy), a set of items each granted a randomized amount within a configured range, and whole base heroes granted the same way a mail reward grants a hero. A character's redemption history is keyed by the same digest; redeeming a code already recorded there is refused as already used, and an unrecognized code is refused as unknown. A full item bag or a full hero list refuses the whole code rather than partially granting it.

**No code is ever public or player visible.** A shipped build's code table holds only a salted PBKDF2-SHA256 digest of each normalized code, with its iteration count and salt; the server never stores, logs, or reconstructs the code text itself, and no gift code, past or future, is published in this repository. See [[What Is Not In This Repository]].

## Opcodes

C1537 submits a code. Routed through `Session.giftCodeRoute`. See the [[Opcode Index]].

## Data Files

| Table | Role |
| --- | --- |
| `yaoqingma.csv` | An invitation/referral code table read the same way as a redeem code |
| `gift.csv` | Gift content definitions a code's reward can reference |

See the [[Data File Index]].

## Persistence

A successful redemption commits one revision to the character's [[State Store]], recording the code's digest and grant count in the redemption document. See [[Transactions and Publishing]].

## Determinism

An item reward's randomized quantity is drawn by a seed derived from the request bytes and the character's revision, under the labeled local policy. See [[Method Determinism]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/GiftCodes.kt`, routed from `Session.giftCodeRoute`.

## How It Was Deciphered

The redemption flow, the digest lookup, and the once-per-character refusal were captured and reproduced, and are pinned by the [[Method Differential Harness]]; the hashing scheme itself is a deliberate operator decision to keep codes secret in any shipped build.

## See Also

- [[Acquisition and Items]], the grant path a code's reward uses.
