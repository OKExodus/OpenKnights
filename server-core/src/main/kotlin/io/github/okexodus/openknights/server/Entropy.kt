package io.github.okexodus.openknights.server

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asInt
import io.github.okexodus.openknights.exact.asStr
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.toHexString
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Every random value the server draws, in the reference's terms: `secrets.token_bytes / token_hex / token_urlsafe /
 * randbits / choice` and `uuid.uuid4()`. Everything else that looks random is seeded from state (CPython `random` in
 * the `exact` module). The live server draws from [SystemEntropy]; the differential harness replays the reference's
 * recorded draws ([TapeEntropy]), so both sides write the same ids, tokens, salts and seeds.
 */
interface Entropy {
    /** `secrets.token_bytes(n)`. */
    fun tokenBytes(n: Int = 32): ByteArray

    /** `secrets.token_hex(n)`: 2n lower-case hex digits. */
    fun tokenHex(n: Int = 32): String

    /** `secrets.token_urlsafe(n)`: base64url of n bytes without padding. */
    fun tokenUrlsafe(n: Int = 32): String

    /** `secrets.randbits(k)`. */
    fun randbits(k: Int): BigInteger

    /** The index `secrets.choice(seq)` picks from a sequence of [size] items. */
    fun choice(size: Int): Int

    /** `uuid.uuid4().hex`. */
    fun uuid4Hex(): String

    companion object {
        @Volatile
        var current: Entropy = SystemEntropy
    }
}

object SystemEntropy : Entropy {
    private val random = SecureRandom()

    override fun tokenBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
    override fun tokenHex(n: Int) = tokenBytes(n).toHexString()
    override fun tokenUrlsafe(n: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes(n))
    override fun randbits(k: Int) = BigInteger(k, random)
    override fun choice(size: Int): Int {
        require(size > 0) { "Cannot choose from an empty sequence" }
        return random.nextInt(size)
    }
    override fun uuid4Hex(): String = UUID.randomUUID().toString().replace("-", "")
}

/** A recorded draw of another kind or size than the code asks for: the two implementations have diverged. */
class TapeMismatch(message: String) : AssertionError(message)

/**
 * The reference's recorded draws `[kind, size, value]` in order (a recording's tape). [cursor] is
 * the next draw; the harness sets it to each step's first draw.
 */
class TapeEntropy(private val draws: JArr) : Entropy {
    var cursor = 0

    private fun next(kind: String, size: Int): io.github.okexodus.openknights.exact.JValue {
        if (cursor >= draws.size) throw TapeMismatch("entropy draw $cursor ($kind $size) beyond the tape")
        val draw = draws[cursor].asArr
        if (draw[0].asStr != kind || draw[1].asInt.toInt() != size) {
            throw TapeMismatch("entropy draw $cursor: tape has ${draw[0].asStr} ${draw[1]}, code asked $kind $size")
        }
        cursor++
        return draw[2]
    }

    override fun tokenBytes(n: Int) = next("token_bytes", n).asStr.hexBytes()
    override fun tokenHex(n: Int) = next("token_hex", n).asStr
    override fun tokenUrlsafe(n: Int) = next("token_urlsafe", n).asStr
    override fun randbits(k: Int): BigInteger = (next("randbits", k) as JInt).value
    override fun choice(size: Int) = next("choice", size).asInt.toInt()
    override fun uuid4Hex() = (next("uuid4", 16) as JStr).value
}
