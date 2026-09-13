package io.github.okexodus.openknights.exact

import java.math.BigInteger
import java.security.MessageDigest

/**
 * SplitMix64 as the reference server uses it: the battle engine's generator (`below` by rejection, `chance` in basis
 * points, a draw counter) and the campaign settlement's generator (`random` from the top 53 bits, `randint` by plain
 * modulo). Both advance the same state the same way; they differ only in the derived methods, so both sets live here.
 */
class SplitMix64(seed: ULong) {
    var state: ULong = seed
        private set

    /** Draws taken so far (the battle engine records it). */
    var draws: Long = 0
        private set

    fun nextU64(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        draws++
        return z xor (z shr 31)
    }

    /** Battle engine: unbiased integer in [0, n) by rejection against `2^64 - (2^64 mod n)`. */
    fun below(n: Long): Long {
        require(n > 0) { "below() needs n > 0" }
        return below(n.toULong()).toLong()
    }

    /** [below] for any n in [1, 2^64). */
    fun below(un: ULong): ULong {
        require(un > 0uL) { "below() needs n > 0" }
        val remainder = ((ULong.MAX_VALUE % un) + 1uL) % un      // 2^64 mod n
        while (true) {
            val value = nextU64()
            // limit = 2^64 - remainder; when the remainder is 0 every value passes.
            if (remainder == 0uL || value < 0uL - remainder) return value % un
        }
    }

    /** Battle engine: a chance in basis points; no draw when the outcome is certain. */
    fun chance(bps: Long): Boolean {
        if (bps <= 0) return false
        if (bps >= 10000) return true
        return below(10000) < bps
    }

    /** Campaign settlement: `(next >> 11) / 2^53`. */
    fun random(): Double = (nextU64() shr 11).toLong().toDouble() / (1L shl 53).toDouble()

    /** Campaign settlement: `low + next % (high - low + 1)` (plain modulo). */
    fun randint(low: Long, high: Long): Long {
        val span = (high - low + 1).toULong()
        return low + (nextU64() % span).toLong()
    }

    constructor(seed: BigInteger) : this(seed.and(MASK_64).toString().toULong())

    private companion object {
        val MASK_64: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
    }
}

/** Seeds the reference derives from hashes: the first 8 bytes of a SHA-256, read little-endian. */
object Seeds {
    fun sha256Le64(data: ByteArray): BigInteger {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        var value = BigInteger.ZERO
        for (i in 7 downTo 0) value = value.shiftLeft(8).or(BigInteger.valueOf((digest[i].toInt() and 0xFF).toLong()))
        return value
    }

    fun sha256Le64(text: String): BigInteger = sha256Le64(text.toByteArray(Charsets.UTF_8))

    /** `"|".join(map(str, parts))` hashed: the helpers named `_rng` and `battle_seed`. */
    fun joined(vararg parts: Any): BigInteger = sha256Le64(parts.joinToString("|") { pyStr(it) })

    /** `seed_for(request_bytes, revision, salt)`: sha256(f"{revision}:{salt}:" + request bytes). */
    fun seedFor(requestBytes: ByteArray, revision: Long, salt: String = ""): BigInteger =
        sha256Le64("$revision:$salt:".toByteArray(Charsets.UTF_8) + requestBytes)

    /** `str(x)` of the values the reference joins into seeds (text, integers, booleans). */
    fun pyStr(value: Any): String = when (value) {
        is String -> value
        is Boolean -> if (value) "True" else "False"
        is Int, is Long, is BigInteger -> value.toString()
        is Double -> PyFloat.repr(value)
        is JValue -> when (value) {
            is JStr -> value.value
            is JInt -> value.value.toString()
            is JBool -> if (value.value) "True" else "False"
            is JFloat -> PyFloat.repr(value.value)
            JNull -> "None"
            else -> throw IllegalArgumentException("no seed text for a JSON container")
        }
        else -> throw IllegalArgumentException("no seed text for ${value::class.simpleName}")
    }
}
