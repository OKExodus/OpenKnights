package io.github.okexodus.openknights.exact

import java.math.BigInteger

/**
 * The client's binary32 arithmetic as the reference server reproduces it. Several helpers exist there with slightly
 * different edges, so each has its own function here:
 *
 * - [pack]: `struct.pack("<f", x)`: nearest-even; a finite value too large for binary32 is an [F32Overflow].
 * - [round]: `struct.unpack("<f", struct.pack("<f", float(x)))[0]` (battle engine, battle stats, card reset).
 * - [roundFinite]: the same, and a non-finite result is refused (hero progression, gear evolve).
 * - [exact]: the correctly rounded binary32 of an exact rational, as binary64 (battle stats `f32_exact`).
 * - [exactFraction]: the same rounding kept as a rational, no overflow check (card-reset returns `f32`).
 * - [fmadd]: ARM64 FMADD with one rounding.
 * - [fcvtzu], [fcvtzuFraction], [fcvtzs], [truncU32], [ucvtf]: the conversions next to them.
 */
object F32 {
    private const val U32 = 0xFFFFFFFFL

    class F32Overflow : ArithmeticException("float too large to pack with f format")

    fun pack(x: Double): Float {
        val f = x.toFloat()
        if (f.isInfinite() && x.isFinite()) throw F32Overflow()
        return f
    }

    fun round(x: Double): Double = pack(x).toDouble()

    fun roundFinite(x: Double): Double {
        val result = round(x)
        if (!result.isFinite()) throw IllegalArgumentException("non-finite float32 result")
        return result
    }

    /** `f32_exact(q)`: exponent from the bit lengths (one correction), subnormals at 2^-126, half to even. */
    fun exact(q: Fraction): Double {
        if (q.signum == 0) return 0.0
        val negative = q.signum < 0
        val value = q.abs()
        var exp = value.numerator.bitLength() - value.denominator.bitLength()
        if (Fraction.pow2(exp) > value) exp -= 1
        exp = maxOf(exp, -126)
        val scale = Fraction.pow2(exp - 23)
        val scaled = value / scale
        val (n0, rem) = scaled.numerator.divideAndRemainder(scaled.denominator)
        var n = n0
        val twice = rem.shiftLeft(1)
        val cmp = twice.compareTo(scaled.denominator)
        if (cmp > 0 || (cmp == 0 && n.testBit(0))) n = n.add(BigInteger.ONE)
        val result = (Fraction.of(n) * scale).toDouble()
        return if (negative) -result else result
    }

    /** `reset_returns.f32(q)`: exact exponent search, the rounded value stays a rational (no overflow). */
    fun exactFraction(q: Fraction): Fraction {
        if (q.signum == 0) return Fraction.ZERO
        val negative = q.signum < 0
        val value = q.abs()
        var e = value.numerator.bitLength() - value.denominator.bitLength()
        while (Fraction.pow2(e) > value) e -= 1
        while (Fraction.pow2(e + 1) <= value) e += 1
        val scale = Fraction.pow2(maxOf(e, -126) - 23)
        val m = value / scale
        var n = m.numerator.divide(m.denominator)
        val r = m - Fraction.of(n)
        if (r > Fraction.HALF || (r == Fraction.HALF && n.testBit(0))) n = n.add(BigInteger.ONE)
        val result = Fraction.of(n) * scale
        return if (negative) -result else result
    }

    /** `f32_from_double(x)`: the binary32 of a binary64, as an exact rational. */
    fun fromDouble(x: Double): Fraction = Fraction.of(pack(x).toDouble())

    fun fmadd(a: Double, b: Double, c: Double): Double = exact(Fraction.of(a) * Fraction.of(b) + Fraction.of(c))

    /** FCVTZU to 32 bits: NaN and values <= 0 give 0, otherwise truncate and saturate at 2^32 - 1. */
    fun fcvtzu(x: Double): Long {
        if (x.isNaN() || x <= 0) return 0
        if (x.isInfinite()) throw ArithmeticException("cannot convert float infinity to integer")
        val t = PyInt.truncate(x)
        return if (t > BigInteger.valueOf(U32)) U32 else t.toLong()
    }

    fun fcvtzuFraction(q: Fraction): Long {
        if (q.signum <= 0) return 0
        val t = q.trunc()
        return if (t > BigInteger.valueOf(U32)) U32 else t.toLong()
    }

    /** FCVTZS to 32 bits of an exact rational: truncate, saturate to the signed 32-bit range. */
    fun fcvtzs(q: Fraction): Long {
        val t = q.trunc()
        return t.max(BigInteger.valueOf(Int.MIN_VALUE.toLong())).min(BigInteger.valueOf(Int.MAX_VALUE.toLong())).toLong()
    }

    /** `_trunc_u32`: a finite value in [0, 2^32) truncated; anything else is refused. */
    fun truncU32(x: Double): Long {
        if (!x.isFinite() || x < 0 || x >= 4294967296.0) throw IllegalArgumentException("FCVTZU input outside bounded unsigned 32-bit domain")
        return x.toLong()
    }

    /** UCVTF of the low 32 bits of an integer. */
    fun ucvtf(value: BigInteger): Double = round(value.and(BigInteger.valueOf(U32)).toDouble())
    fun ucvtf(value: Long): Double = ucvtf(BigInteger.valueOf(value))
}
