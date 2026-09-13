package io.github.okexodus.openknights.exact

import java.math.BigInteger
import java.security.MessageDigest

/**
 * The reference's seeded random generator: the Mersenne Twister (MT19937) with the seeding and the derived methods of
 * its standard library (version 3.13), so every draw equals the reference's for the same seed.
 *
 * Seeding: an integer seed uses all bits of its absolute value as little-endian 32-bit key words (`init_by_array`);
 * text and bytes are first turned into `int.from_bytes(data + sha512(data))` (seed version 2).
 */
class PyRandom private constructor() {
    private val mt = IntArray(N)
    private var index = N + 1

    companion object {
        private const val N = 624
        private const val M = 397
        private const val MATRIX_A = 0x9908b0df.toInt()
        private const val UPPER_MASK = 0x80000000.toInt()
        private const val LOWER_MASK = 0x7fffffff

        fun seeded(seed: Long): PyRandom = seeded(BigInteger.valueOf(seed))

        fun seeded(seed: BigInteger): PyRandom = PyRandom().apply { seedInt(seed.abs()) }

        fun seeded(text: String): PyRandom = seeded(text.toByteArray(Charsets.UTF_8))

        fun seeded(bytes: ByteArray): PyRandom {
            val digest = MessageDigest.getInstance("SHA-512").digest(bytes)
            return seeded(BigInteger(1, bytes + digest))
        }
    }

    private fun seedInt(n: BigInteger) {
        // Little-endian 32-bit words of n; at least one word (zero seeds use the key [0]).
        val words = maxOf(1, (n.bitLength() + 31) / 32)
        val key = IntArray(words) { n.shiftRight(32 * it).toInt() }
        initByArray(key)
    }

    private fun initGenrand(s: Int) {
        mt[0] = s
        for (i in 1 until N) {
            mt[i] = 1812433253 * (mt[i - 1] xor (mt[i - 1] ushr 30)) + i
        }
        index = N
    }

    private fun initByArray(key: IntArray) {
        initGenrand(19650218)
        var i = 1
        var j = 0
        var k = maxOf(N, key.size)
        while (k > 0) {
            mt[i] = (mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1664525)) + key[j] + j
            i++; j++
            if (i >= N) { mt[0] = mt[N - 1]; i = 1 }
            if (j >= key.size) j = 0
            k--
        }
        k = N - 1
        while (k > 0) {
            mt[i] = (mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1566083941)) - i
            i++
            if (i >= N) { mt[0] = mt[N - 1]; i = 1 }
            k--
        }
        mt[0] = 0x80000000.toInt()
    }

    /** genrand_uint32 as an unsigned value. */
    fun nextU32(): Long {
        if (index >= N) {
            var kk = 0
            while (kk < N - M) {
                val y = (mt[kk] and UPPER_MASK) or (mt[kk + 1] and LOWER_MASK)
                mt[kk] = mt[kk + M] xor (y ushr 1) xor (if (y and 1 != 0) MATRIX_A else 0)
                kk++
            }
            while (kk < N - 1) {
                val y = (mt[kk] and UPPER_MASK) or (mt[kk + 1] and LOWER_MASK)
                mt[kk] = mt[kk + (M - N)] xor (y ushr 1) xor (if (y and 1 != 0) MATRIX_A else 0)
                kk++
            }
            val y = (mt[N - 1] and UPPER_MASK) or (mt[0] and LOWER_MASK)
            mt[N - 1] = mt[M - 1] xor (y ushr 1) xor (if (y and 1 != 0) MATRIX_A else 0)
            index = 0
        }
        var y = mt[index++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and 0x9d2c5680.toInt())
        y = y xor ((y shl 15) and 0xefc60000.toInt())
        y = y xor (y ushr 18)
        return y.toLong() and 0xFFFFFFFFL
    }

    /** `random()`: 53 bits in [0, 1). */
    fun random(): Double {
        val a = nextU32() ushr 5
        val b = nextU32() ushr 6
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0)
    }

    /** `getrandbits(k)`: k >= 0 bits, little-endian 32-bit words. */
    fun getrandbits(k: Int): BigInteger {
        require(k >= 0) { "number of bits must be non-negative" }
        if (k == 0) return BigInteger.ZERO
        if (k <= 32) return BigInteger.valueOf(nextU32() ushr (32 - k))
        var remaining = k
        var result = BigInteger.ZERO
        var shift = 0
        while (remaining > 0) {
            var r = nextU32()
            if (remaining < 32) r = r ushr (32 - remaining)
            result = result.or(BigInteger.valueOf(r).shiftLeft(shift))
            shift += 32
            remaining -= 32
        }
        return result
    }

    /** `_randbelow(n)` for n > 0: rejection sampling on `n.bit_length()` bits. */
    fun randbelow(n: BigInteger): BigInteger {
        require(n.signum() > 0)
        val k = n.bitLength()
        var r = getrandbits(k)
        while (r >= n) r = getrandbits(k)
        return r
    }

    fun randbelow(n: Long): Long = randbelow(BigInteger.valueOf(n)).toLong()

    fun randbelow(n: Int): Int = randbelow(n.toLong()).toInt()

    /** `randrange(stop)`. */
    fun randrange(stop: Long): Long {
        require(stop > 0) { "empty range for randrange()" }
        return randbelow(stop)
    }

    /** `randrange(start, stop, step)`. */
    fun randrange(start: Long, stop: Long, step: Long = 1): Long {
        val width = stop - start
        if (step == 1L) {
            require(width > 0) { "empty range in randrange($start, $stop)" }
            return start + randbelow(width)
        }
        val n = when {
            step > 0 -> Math.floorDiv(width + step - 1, step)
            step < 0 -> Math.floorDiv(width + step + 1, step)
            else -> throw IllegalArgumentException("zero step for randrange()")
        }
        require(n > 0) { "empty range in randrange($start, $stop, $step)" }
        return start + step * randbelow(n)
    }

    /** `randint(a, b)`: both ends included. */
    fun randint(a: Long, b: Long): Long = randrange(a, b + 1)

    /** `choice(seq)`. */
    fun <T> choice(seq: List<T>): T {
        if (seq.isEmpty()) throw IndexOutOfBoundsException("Cannot choose from an empty sequence")
        return seq[randbelow(seq.size)]
    }

    /** `shuffle(x)` in place. */
    fun <T> shuffle(x: MutableList<T>) {
        for (i in x.size - 1 downTo 1) {
            val j = randbelow(i + 1)
            val t = x[i]; x[i] = x[j]; x[j] = t
        }
    }

    /** `sample(population, k)` (no counts). */
    fun <T> sample(population: List<T>, k: Int): List<T> {
        val n = population.size
        require(k in 0..n) { "Sample larger than population or is negative" }
        val result = ArrayList<T>(k)
        var setsize = 21
        if (k > 5) setsize += pow4CeilLog4(k * 3)
        if (n <= setsize) {
            val pool = population.toMutableList()
            for (i in 0 until k) {
                val j = randbelow(n - i)
                result.add(pool[j])
                pool[j] = pool[n - i - 1]
            }
        } else {
            val selected = HashSet<Int>()
            for (i in 0 until k) {
                var j = randbelow(n)
                while (j in selected) j = randbelow(n)
                selected.add(j)
                result.add(population[j])
            }
        }
        return result
    }

    /**
     * `choices(population, weights=weights, k=k)` with integer weights: cumulative sums, `total = cum[-1] + 0.0`,
     * `bisect_right(cum, random() * total, 0, n - 1)`.
     */
    fun <T> choices(population: List<T>, weights: List<Long>, k: Int = 1): List<T> {
        val n = population.size
        require(weights.size == n) { "The number of weights does not match the population" }
        val cumulative = LongArray(n)
        var sum = 0L
        for (i in 0 until n) { sum = Math.addExact(sum, weights[i]); cumulative[i] = sum }
        val total = cumulative[n - 1].toDouble()
        require(total > 0.0) { "Total of weights must be greater than zero" }
        val hi = n - 1
        return List(k) { population[bisectRight(cumulative, random() * total, hi)] }
    }

    /** `choices(population, k=k)` without weights: `floor(random() * n)`. */
    fun <T> choices(population: List<T>, k: Int = 1): List<T> {
        val n = population.size.toDouble()
        return List(k) { population[Math.floor(random() * n).toInt()] }
    }

    /** `uniform(a, b)`. */
    fun uniform(a: Double, b: Double): Double = a + (b - a) * random()

    /** bisect_right over integers against a float probe, comparing exactly as the reference does. */
    private fun bisectRight(cumulative: LongArray, x: Double, hiExclusive: Int): Int {
        var lo = 0
        var hi = hiExclusive
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (PyCompare.lessThan(x, cumulative[mid])) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** `4 ** ceil(log(n, 4))` with the reference's floating-point logarithm. */
    private fun pow4CeilLog4(n: Int): Int {
        val exponent = Math.ceil(Math.log(n.toDouble()) / Math.log(4.0)).toInt()
        var value = 1
        repeat(exponent) { value *= 4 }
        return value
    }
}

/** Exact comparisons between binary64 and integers, as the reference compares mixed numbers. */
object PyCompare {
    fun lessThan(x: Double, y: Long): Boolean = compare(x, y) < 0

    fun compare(x: Double, y: Long): Int {
        require(!x.isNaN())
        if (x.isInfinite()) return if (x > 0) 1 else -1
        return java.math.BigDecimal(x).compareTo(java.math.BigDecimal.valueOf(y))
    }
}
