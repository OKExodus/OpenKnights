# Hero Ascension

**Definition.** Raising a hero's awaken level by one step beyond ordinary [[Hero Evolution]], consuming a configured resource and, for non-leader heroes, duplicate hero cards of the same base.

## How It Works Inside

Ascension resolves its target through the shared bounded stat model in [[Heroes]] and finds the next awaken level from the hero's current awaken field. The requirement row it needs comes from a formula-derived key: for the captured leader path the key is the next awaken level alone, while an ordinary hero's key also folds in the hero's resource category, and both must resolve against the herojuexing requirement and level tables. A leader hero ascends on its own; an ordinary hero must additionally supply the exact number of distinct duplicate hero cards the row calls for, each sharing the target's resource base, each sitting on the bench and outside deployment or assignment. The requirement row's role-level and hero-level gates are checked against the character's current levels before anything is spent, and the row's Gold and material costs are drawn down the same way Evolution's are. On success, the hero's stats are recomputed at the new awaken level through the shared model, and its awaken field is raised (or added, for a hero that had never carried one).

Ordinary, card-consuming Ascension only proceeds under an explicitly labeled local material policy; without it, only the captured leader branch is accepted, so the boundary between evidenced and reconstructed behavior stays visible in the code itself.

## Opcodes

The request is C3907, routed from `Session.heroCardRoute` alongside [[Hero Power-up]] and [[God Skills]]. See the [[Opcode Index]].

## Data Files

Ascension reads the same herojuexing awaken tables as [[Hero Evolution]] (requirement, level, and skill rows) to find the next slot and its gates and costs. See the [[Data File Index]].

## Persistence

Ascension commits one revision to the character's [[State Store]]: the target's stats and awaken field, any consumed hero cards, and Gold. See [[Transactions and Publishing]].

## Code

Reproduced in `server-core/.../game/HeroAscension.kt`.

## How It Was Deciphered

The leader ascension path was captured directly; the ordinary, card-consuming path is reconstructed under a labeled local policy and pinned by the [[Method Differential Harness]] alongside the captured shape.

## See Also

- [[Heroes]], [[Hero Evolution]], [[Acquisition and Items]].
