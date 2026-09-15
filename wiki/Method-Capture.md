# Method: Capture

**Definition.** How the game's behavior was observed so it could be reproduced. Capture is the raw material for every other method and for every system page.

The reconstruction is behavior-first. Before a system is reimplemented, its actual behavior is watched: what the client sends, what the server sends back, and how the game's stored state changes as a result. Everything downstream, the opcode layouts, the data-file meanings, and the exactness proofs, rests on these observations.

## What Is Captured

- **Traffic.** The frames exchanged on the login, game, and sign-in services, in order, with their timing.
- **State.** The game's stored state before and after an action, so an opcode can be tied to the exact change it causes.
- **Timing and identity.** The clock and identity values in effect at the moment of an action, because the game's behavior depends on them and they must be pinned to reproduce a result.

## How a Capture Becomes Documentation

1. Perform one action in the game and record the traffic and the state change it produced.
2. Repeat the action with systematic variations to see which inputs move which outputs.
3. Reduce the observation to the smallest description that explains it: a message layout, a rule, a table lookup.
4. Reproduce that description in code, then confirm the reproduction matches the capture byte for byte. See [[Method Differential Harness]].

The goal is never to replay a recording. It is to understand the rule well enough to regenerate the exact bytes from first principles.

## Discipline

- Observations are made against the player's own game and a controlled local environment. This wiki describes the method in general terms and does not publish device identifiers or the specifics of any capture session. See [[What Is Not In This Repository]].
- The clock and randomness in force during a capture are recorded alongside it, because a result that depends on them cannot be reproduced without them. This is why the reconstruction pins both. See [[Method Determinism]].
- A capture is evidence, not authority. The authority is the reference implementation, which turns the understanding into a source of truth that the shipped code is measured against.

## Where Capture Leads

- To message layouts, refined in [[Method Opcode Decoding]].
- To table meanings and relations, refined in [[Method CSV Decryption]] and the [[Data File Index]].
- To exactness, proven in [[Method Differential Harness]].
