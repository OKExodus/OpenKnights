package io.github.okexodus.openknights.exact

import java.math.BigInteger

/** The reference's integer arithmetic where Kotlin differs: floor division, the sign of `%`, true division. */
object PyInt {
    private val MASK_64: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)

    /** `a // b` (rounds toward minus infinity). */
    fun floorDiv(a: BigInteger, b: BigInteger): BigInteger {
        val (q, r) = a.divideAndRemainder(b)
        return if (r.signum() != 0 && (r.signum() != b.signum())) q.subtract(BigInteger.ONE) else q
    }

    /** `a % b` (takes the sign of b). */
    fun mod(a: BigInteger, b: BigInteger): BigInteger {
        val r = a.mod(b.abs())
        return if (b.signum() < 0 && r.signum() != 0) r.add(b) else r
    }

    fun floorDiv(a: Long, b: Long): Long = Math.floorDiv(a, b)
    fun mod(a: Long, b: Long): Long = Math.floorMod(a, b)

    /** `x & 0xFFFFFFFFFFFFFFFF` (two's complement for negative x, like the reference's unbounded integers). */
    fun and64(x: BigInteger): BigInteger = x.and(MASK_64)

    /**
     * `a / b` for integers: the correctly rounded binary64 quotient (the reference rounds once, even above 2^53).
     * Raises [ArithmeticException] for b == 0 and an overflow error beyond the binary64 range.
     */
    fun trueDiv(a: BigInteger, b: BigInteger): Double {
        if (b.signum() == 0) throw ArithmeticException("division by zero")
        if (a.signum() == 0) return if (b.signum() < 0) -0.0 else 0.0
        val negative = (a.signum() < 0) != (b.signum() < 0)
        val n = a.abs()
        val d = b.abs()
        // Scale so that the integer quotient has at least 55 bits (53 + guard + sticky), then round half to even.
        var shift = 55 - (n.bitLength() - d.bitLength())
        val num = if (shift > 0) n.shiftLeft(shift) else n
        val den = if (shift < 0) d.shiftLeft(-shift) else d
        val (q0, r0) = num.divideAndRemainder(den)
        var q = q0
        var sticky = r0.signum() != 0
        // q has 55 or 56 bits; value = q * 2^-shift (plus the sticky remainder).
        var exponent = q.bitLength() - 1 - shift          // unbiased exponent of the leading bit
        val precision = if (exponent < -1022) 53 - (-1022 - exponent) else 53
        if (precision <= 0) {
            // Below half the smallest subnormal (or at it): 0 or the smallest subnormal.
            val half = BigInteger.ONE.shiftLeft(shift - 1075)
            val result = when {
                precision < 0 -> 0.0
                else -> { // precision == 0: compare with half of 2^-1074
                    val cmp = q.compareTo(half)
                    if (cmp > 0 || (cmp == 0 && sticky)) Double.MIN_VALUE else 0.0
                }
            }
            return if (negative) -result else result
        }
        val drop = q.bitLength() - precision
        if (drop > 0) {
            val dropped = q.and(BigInteger.ONE.shiftLeft(drop).subtract(BigInteger.ONE))
            q = q.shiftRight(drop)
            val half = BigInteger.ONE.shiftLeft(drop - 1)
            val cmp = dropped.compareTo(half)
            if (cmp > 0 || (cmp == 0 && (sticky || q.testBit(0)))) q = q.add(BigInteger.ONE)
            shift -= drop
        }
        if (q.bitLength() > 53) { q = q.shiftRight(1); shift -= 1 }
        exponent = q.bitLength() - 1 - shift
        if (exponent > 1023) throw ArithmeticException("integer division result too large for a float")
        val result = Math.scalb(q.toDouble(), -shift)
        return if (negative) -result else result
    }

    fun trueDiv(a: Long, b: Long): Double = trueDiv(BigInteger.valueOf(a), BigInteger.valueOf(b))

    /** `int(x)` of a finite binary64: toward zero, any size. */
    fun truncate(x: Double): BigInteger {
        require(x.isFinite()) { "cannot convert float ${x} to integer" }
        return java.math.BigDecimal(x).toBigInteger()
    }

    /** `math.floor(x)` / `math.ceil(x)` of a finite binary64, any size. */
    fun floor(x: Double): BigInteger = java.math.BigDecimal(x).setScale(0, java.math.RoundingMode.FLOOR).toBigIntegerExact()
    fun ceil(x: Double): BigInteger = java.math.BigDecimal(x).setScale(0, java.math.RoundingMode.CEILING).toBigIntegerExact()
}
