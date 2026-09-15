# Method: Differential Harness

**Definition.** How the reconstruction is proven exact: by replaying recorded scenarios against both the reference implementation and the shipped code and comparing every result byte for byte.

A reproduction that merely looks right is not enough for a preservation project. The claim OpenKnights makes is stronger: for the scenarios it covers, the shipped server produces the same bytes the original would. The differential harness is how that claim is tested and kept true.

## What the Harness Compares

For each step of a recorded scenario, the harness runs the same input through the reconstruction and checks it against the reference's recorded result across several dimensions at once:

- The server messages sent back, byte for byte, in order.
- The changes written to the stored state, compared by logical content rather than raw file bytes.
- Any HTTP response bodies from the sign-in service.
- The random draws consumed, so determinism holds. See [[Method Determinism]].
- The files and backups the action produces on disk.

A step passes only when every dimension matches with zero differences.

## Bundles

A recorded scenario is called a bundle. Bundles are recorded under a pinned clock and a pinned entropy source, so a replay is deterministic and a comparison is meaningful. A suite of bundles covers the login burst, character creation and selection, and each game system, along with longer recorded play sessions. The suite is run in full to check that a change did not regress any covered behavior.

The bundle contents are recordings of the reference's behavior on private data and are not published. What is published is the reconstruction and the fact of the result. See [[What Is Not In This Repository]].

## Waiting Attribution

While a system is still being built, the reconstruction cannot yet answer some messages. The harness does not merely count how many messages went unanswered; it charges each wait to the first system responsible, so a cascade of follow-on messages is attributed to its root cause rather than inflating unrelated counts. This tells contributors exactly which system to build next, and it lets the project state precisely which systems are complete: a system is done when it causes zero waits and every bundle still passes with zero differences.

## Native Proof

The harness proves parity at the level of bytes. A native proof complements it at the level of play: the patched game runs against the reconstruction on a device and is driven through real scenarios, confirming that the client, which is the game's own unmodified logic, accepts the reconstruction and behaves correctly end to end.

## How a System Page Cites the Harness

Every system page ends with how its behavior is known. For most systems that is the set of bundles that exercise it. When a page states that a behavior is exact, this harness is what backs the statement.
