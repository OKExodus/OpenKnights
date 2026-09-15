# Glossary

**Definition.** Short definitions of the terms used across this wiki. Terms are cross-linked from the pages that use them.

### Bundle
A recorded scenario used to prove exactness. A bundle captures the reference server's behavior step by step so the reconstruction can be replayed against it and compared. See [[Method Differential Harness]].

### Born and Unborn
A data root is unborn until the first character is created, at which point the world is born and its shared state begins. An unborn root has an owner account but no world. See [[World and Generations]].

### Data Root
The single directory that holds everything a player's install persists: the account registry, the world, and every character save. See [[Save and Data Root]].

### Determinism
The property that the same inputs always produce the same bytes. The game's randomness, timing, and floating-point math are all reproduced exactly so that a battle or a draw comes out identical. See [[Method Determinism]].

### f32
A 32-bit IEEE-754 float. The battle math computes some intermediate values at 32-bit precision, and the reconstruction models that exactly rather than using ordinary 64-bit math. See [[Battle Engine]].

### Frame
One message on the wire: an opcode followed by a payload, length-prefixed. See [[Opcode Index]].

### Generation
One version of a born world inside the data root. Restores and resets create a new generation and retire the old one, so a swap is atomic and never half-applied. See [[World and Generations]].

### Opcode
The number that identifies a message. Written `C` for client to server and `S` for server to client. See [[Opcode Index]].

### Reference Implementation
The private, authoritative reproduction of the server whose behavior the shipped code is pinned against. It is the source of truth for what is correct. Its outputs, not its source, are what the public reconstruction is compared to. See [[Method Differential Harness]].

### Revision
A monotonic counter on a saved state. Each committed change increments it, which lets the server detect concurrent edits and lets the harness compare state at a known point.

### Session
An authenticated connection to the game, issued a token at sign-in. See [[Accounts and Sign-in]].

### SplitMix64
The 64-bit pseudo-random generator the battle engine draws from. Reproduced exactly, including unsigned arithmetic and draw order. See [[Method Determinism]].

### Waiting Attribution
In the harness, when the reconstruction cannot yet answer a message, the wait is charged to the first system responsible, not to every message in the cascade. This makes it clear which system to build next. See [[Method Differential Harness]].

### Wire Identity
The numeric account id a character presents to the world, distinct from its internal character id. Allocated from a fixed range and never reused by a bot. See [[World and Generations]].
