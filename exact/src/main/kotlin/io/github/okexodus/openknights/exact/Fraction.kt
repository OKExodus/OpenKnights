package io.github.okexodus.openknights.exact

import java.math.BigInteger

/**
 * An exact rational, as the reference's `fractions.Fraction`: always in lowest terms with a positive denominator.
 * Built from integers or from a binary64 value (exactly). [toDouble] is correctly rounded (like `float(q)`) and
 * [trunc] rounds toward zero (like `int(q)`).
 */
class Fraction private constructor(val numerator: BigInteger, val denominator: BigInteger) : Comparable<Fraction> {
    companion object {
        val ZERO = Fraction(BigInteger.ZERO, BigInteger.ONE)
        val ONE = Fraction(BigInteger.ONE, BigInteger.ONE)
        val HALF = of(1, 2)

        fun of(numerator: BigInteger, denominator: BigInteger = BigInteger.ONE): Fraction {
            require(denominator.signum() != 0) { "Fraction(n, 0)" }
            var n = numerator
            var d = denominator
            if (d.signum() < 0) { n = n.negate(); d = d.negate() }
            val g = n.gcd(d)
            return if (g == BigInteger.ONE || g.signum() == 0) Fraction(n, d) else Fraction(n.divide(g), d.divide(g))
        }

        fun of(numerator: Long, denominator: Long = 1): Fraction = of(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator))

        /** The exact value of a finite binary64. */
        fun of(value: Double): Fraction {
            require(value.isFinite()) { "cannot convert ${value} to a Fraction" }
            if (value == 0.0) return ZERO
            val bits = java.lang.Double.doubleToRawLongBits(value)
            val sign = if (bits < 0) -1 else 1
            val exponent = ((bits ushr 52) and 0x7FF).toInt()
            val fraction = bits and 0xFFFFFFFFFFFFFL
            val (mantissa, power) = if (exponent == 0) fraction to -1074 else (fraction or (1L shl 52)) to (exponent - 1075)
            val m = BigInteger.valueOf(mantissa * sign)
            return if (power >= 0) of(m.shiftLeft(power)) else of(m, BigInteger.ONE.shiftLeft(-power))
        }

        /** `Fraction(2) ** e` for any integer e. */
        fun pow2(e: Int): Fraction = if (e >= 0) of(BigInteger.ONE.shiftLeft(e)) else of(BigInteger.ONE, BigInteger.ONE.shiftLeft(-e))

        /** Parse "n/d" (the vector notation). */
        fun parse(text: String): Fraction {
            val slash = text.indexOf('/')
            return if (slash < 0) of(BigInteger(text)) else of(BigInteger(text.substring(0, slash)), BigInteger(text.substring(slash + 1)))
        }
    }

    val signum: Int get() = numerator.signum()

    operator fun plus(o: Fraction) = of(numerator * o.denominator + o.numerator * denominator, denominator * o.denominator)
    operator fun minus(o: Fraction) = of(numerator * o.denominator - o.numerator * denominator, denominator * o.denominator)
    operator fun times(o: Fraction) = of(numerator * o.numerator, denominator * o.denominator)
    operator fun div(o: Fraction): Fraction {
        if (o.numerator.signum() == 0) throw ArithmeticException("Fraction division by zero")
        return of(numerator * o.denominator, denominator * o.numerator)
    }
    operator fun unaryMinus() = Fraction(numerator.negate(), denominator)
    fun abs() = if (numerator.signum() < 0) -this else this

    override fun compareTo(other: Fraction): Int = (numerator * other.denominator).compareTo(other.numerator * denominator)
    override fun equals(other: Any?) = other is Fraction && other.numerator == numerator && other.denominator == denominator
    override fun hashCode() = numerator.hashCode() * 31 + denominator.hashCode()
    override fun toString() = "$numerator/$denominator"

    /** `int(q)`: toward zero. */
    fun trunc(): BigInteger = numerator.divide(denominator)

    /** `q.numerator // q.denominator`: toward minus infinity. */
    fun floor(): BigInteger = PyInt.floorDiv(numerator, denominator)

    /** `float(q)`: correctly rounded (nearest, ties to even); OverflowError beyond the binary64 range. */
    fun toDouble(): Double = PyInt.trueDiv(numerator, denominator)
}
