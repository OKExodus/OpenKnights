# Mail

**Definition.** System, personal, attack, praise, and guild mail: a per-character mailbox in the shared world, each message carrying an optional Reward that is claimed into the character's own save.

## How It Works Inside

Mailboxes live in the world document `mail`, one box per wire role id, with mail ids drawn from a single world counter so every message anyone ever receives has a unique id. A mailbox listing shows the 50 oldest unclaimed system-reward messages plus every other message; a box past its limit evicts its oldest rewardless message first, then its oldest paid praise note, and never evicts a message still holding an unclaimed reward. Messages older than 30 days are dropped as the box is read.

Reading marks a message read and returns its body and the reward it would pay; claiming pays that reward into the character's items, heroes, equipment, and role-property scalars and removes the message. A claim is recorded in the character's own mail ledger (`mail_state`), so a claim that lands in the save but whose world removal has to be retried is never paid twice: a repeated claim request against an id already in the ledger just re-sends the removal. A malformed ledger, one that is not an object or whose id lists are not lists of integers, is refused cleanly rather than trusted. Praise mail is a special case: its Pal Points are paid the moment the message is opened rather than through a separate claim, and a ledger entry marks that payment so a later delete or claim never repeats it. Deleting a message that still holds an unclaimed reward is refused; the reward has to be claimed first. Writing a personal message checks the write panel's title and body length limits and the recipient's blacklist before it is delivered; a blocked sender's mail never reaches the box.

## Opcodes

| Opcode | Role |
| --- | --- |
| [[Opcode Index\|C257]] / [[Opcode Index\|S256]] | List the mailbox |
| [[Opcode Index\|C195]] / [[Opcode Index\|S260]], [[Opcode Index\|S264]] | Read a message |
| [[Opcode Index\|C197]] / [[Opcode Index\|S266]] | Claim its reward |
| [[Opcode Index\|C199]] / [[Opcode Index\|S262]] | Delete |
| [[Opcode Index\|C201]] / [[Opcode Index\|S268]] | Write a personal message |
| [[Opcode Index\|C203]] to [[Opcode Index\|C207]] / [[Opcode Index\|S270]] | Blacklist, block, unblock |
| [[Opcode Index\|S258]] | A new message pushed to an online recipient |

## Persistence

Mailboxes, their messages, and the mail blacklist are shared state in the [[World Directory]] document `mail`. A claim or an opened praise note also grants items and role properties, which commit a revision to the character's own [[State Store]], guarded by the per-character `mail_state` ledger. See [[Transactions and Publishing]] and [[Acquisition and Items]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Mail.kt`, dispatched from `SocialRoutes.mail` in `SocialRoutes.kt`.

## How It Was Deciphered

The listing limits, the eviction order, the claim ledger, and the praise-on-open payment were captured, reproduced, and pinned by the [[Method Differential Harness]].

## See Also

- [[Friends]], the source of a praise note.
- [[Guild]], the source of guild mail and war results.
- [[Acquisition and Items]], the grant path a claimed reward runs through.
