# Method: Determinism

**Definition.** How the game's randomness, timing, and floating-point math are reproduced exactly, so that a battle, a draw, or a timed reward comes out identical to the original.

Much of Pocket Knights is deterministic given its inputs. A battle plays out the same way every time from the same seed and the same teams. If the reconstruction is even one bit off in how it draws a random number or rounds a float, the whole result diverges. Determinism is therefore not a detail; it is a precondition for exactness.

## Randomness

The engine draws pseudo-random numbers from a 64-bit generator, SplitMix64. Reproducing it exactly means three things:

- **The algorithm, bit for bit,** including that its arithmetic is unsigned 64-bit.
- **The seeding,** because the same generator with a different seed gives different numbers. Seeds are derived from recorded inputs, not chosen freshly.
- **The draw order,** because the sequence of draws is what determines the outcome. The engine's rule for when a draw is consumed and when it is skipped is reproduced precisely. For example, a chance that cannot fail or cannot succeed consumes no draw.

See [[Battle Engine]] for how these draws drive combat. This is our reproduction of the generator, from `exact/.../SplitMix64.kt`:

```kotlin
fun nextU64(): ULong {
    state += 0x9E3779B97F4A7C15uL
    var z = state
    z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
    z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
    draws++
    return z xor (z shr 31)
}

/** A chance in basis points; certain outcomes take no draw, which keeps later draws aligned. */
fun chance(bps: Long): Boolean {
    if (bps <= 0) return false
    if (bps >= 10000) return true
    return below(10000) < bps
}
```

The `ULong` arithmetic is what makes it unsigned 64-bit, and the two early returns in `chance` are the skip rules: a certain or impossible outcome consumes no draw, so every later draw stays in the position the original put it.

## Floating-Point Math

Some intermediate values are computed at 32-bit float precision and others at 64-bit. Ordinary code would compute everything at 64-bit and get subtly different rounding. The reconstruction models the exact precision at each step, narrowing to 32-bit where the game does, so the rounded results match. The core of it, from `exact/.../F32.kt`, is a deliberate round-trip through a 32-bit float:

```kotlin
fun round(x: Double): Double = pack(x).toDouble()   // narrow to binary32, then widen back
```

Around that sit variants with the exact edges each site needs: one that refuses a non-finite result, one that keeps the rounded value as an exact rational, and the integer conversions that follow the game's saturating rules. See [[Battle Engine]] for the damage math that uses them.

## Time

Behavior that depends on the clock, such as daily resets, event windows, and timed rewards, is reproduced against a pinned clock rather than the wall clock. The clock in force during a capture is recorded with it, and the reconstruction reads time only through a single controlled source so a result can be regenerated. The shipped game keys these on the device's local clock, which is what makes fully offline play possible.

## Identity and Entropy

New identifiers and secrets are also a source of divergence. The reconstruction issues them only through controlled sources so that, under test, they can be pinned to the same values a capture recorded, and in production they are generated freshly. Any new site that needs the clock, a random number, or a fresh identifier must go through these controlled sources, or the exactness check fails.

## Why This Is Enforceable

Because randomness, time, and identity all flow through single controlled sources, a test can pin all three and replay a scenario deterministically. That is what makes the [[Method Differential Harness]] able to compare the reconstruction against the reference byte for byte.
