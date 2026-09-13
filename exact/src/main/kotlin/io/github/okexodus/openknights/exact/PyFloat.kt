package io.github.okexodus.openknights.exact

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The reference's float text (`repr(float)`, also what its JSON writer uses): the shortest decimal that reads back as
 * the same binary64, the closest one when several are equally short, printed in positional form for decimal-point
 * positions -3..16 and in exponent form otherwise (`1e+16`, `1e-05`). Java's own `Double.toString` differs in the
 * layout and, for values whose shortest form has one digit, can pick a two-digit form.
 */
object PyFloat {
    fun repr(value: Double): String {
        if (value.isNaN()) return "nan"
        if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
        if (value == 0.0) return if (1.0 / value < 0) "-0.0" else "0.0"
        val (digits, decpt) = shortest(Math.abs(value))
        val sign = if (value < 0) "-" else ""
        return sign + layout(digits, decpt)
    }

    /** Shortest round-trip digits (no trailing zeros) and the decimal-point position: value = 0.DIGITS × 10^decpt. */
    fun shortest(magnitude: Double): Pair<String, Int> {
        require(magnitude > 0 && magnitude.isFinite())
        val exact = BigDecimal(magnitude)
        for (precision in 1..17) {
            val low = exact.round(MathContext(precision, RoundingMode.FLOOR))
            val high = exact.round(MathContext(precision, RoundingMode.CEILING))
            val candidates = listOf(low, high).distinct().filter { it.toString().toDouble() == magnitude }
            if (candidates.isEmpty()) continue
            val best = if (candidates.size == 1) candidates[0] else {
                val dl = exact.subtract(low).abs()
                val dh = high.subtract(exact).abs()
                when {
                    dl < dh -> low
                    dh < dl -> high
                    // Equally close: the even last digit, as the reference's correctly rounded conversion does.
                    else -> if (low.setScale(low.scale() + precision - low.precision()).unscaledValue().testBit(0)) high else low
                }
            }
            val stripped = best.stripTrailingZeros()
            val digits = stripped.unscaledValue().toString()
            return digits to (digits.length - stripped.scale())
        }
        error("no round-trip decimal within 17 digits")
    }

    private fun layout(digits: String, decpt: Int): String {
        if (decpt <= -4 || decpt > 16) {
            val exponent = decpt - 1
            val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            val expText = Math.abs(exponent).toString().padStart(2, '0')
            return mantissa + "e" + (if (exponent < 0) "-" else "+") + expText
        }
        return when {
            decpt <= 0 -> "0." + "0".repeat(-decpt) + digits
            decpt >= digits.length -> digits + "0".repeat(decpt - digits.length) + ".0"
            else -> digits.substring(0, decpt) + "." + digits.substring(decpt)
        }
    }
}
