package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.F32

/**
 * The client's item property formulas of `equip_evolve.py` (`Formula::GetEquipPropertyValue` /
 * `GetJewelPropertyValue`, binary32 in the instructions' order), used by the universal Power. The C2049 / C2629 evolve
 * codecs, planners and reply frames of that module belong to the gear group.
 */
object EquipEvolve {
    const val F32_TEN_THOUSAND = 10000.0
    const val ERROR_INVALID = 102

    /** `f32(value)`: binary32 rounding; a non-finite result is refused (a finite overflow raises like `struct.pack`). */
    fun f32(value: Double): Double {
        val result = F32.round(value)
        if (!result.isFinite()) throw PyValues.ValueError("Non-finite binary32 result")
        return result
    }

    /** ARM64 FCVTZU to 32 bits: truncate toward zero, saturate to [0, 2^32 - 1]. */
    fun fcvtzu(value: Double): Long = F32.fcvtzu(value)

    /** `sub w9,level,#1; cmp level,#0; scvtf; fcsel` → 0.0 at level 0. */
    fun levelFactor(level: Long): Double = if (level == 0L) 0.0 else f32((level - 1).toDouble())

    fun superFactor(value: Long, propertyRaw: Long): Long {
        val factor = f32(f32(f32(propertyRaw.toDouble()) / F32_TEN_THOUSAND) + 1.0)
        return fcvtzu(f32(factor * f32(value.toDouble())))
    }

    /** `Formula::GetEquipPropertyValue` 0x00b4e650. */
    fun equipPropertyValue(level: Long, superFlag: Long, extra: Long, base108: Long, growth109: Long, potential: Long, property912: Long): Long {
        val rate = f32(f32(potential.toDouble()) / F32_TEN_THOUSAND)
        var s0 = f32(rate + 1.0)
        s0 = f32(s0 * f32(growth109.toDouble()))
        s0 = f32(s0 * levelFactor(level))
        var value = (base108 + fcvtzu(s0)) and 0xFFFFFFFFL
        if (superFlag != 0L) value = superFactor(value, property912)
        return (extra + value) and 0xFFFFFFFFL
    }

    /** `Formula::GetJewelPropertyValue` 0x00b4ec84 for a fixed (non-random) base / ratio. */
    fun jewelPropertyValue(level: Long, superFlag: Long, extra: Long, base108: Long, ratio110: Long, potential: Long, property956: Long): Long {
        val perLevel = fcvtzu(f32(f32(f32(ratio110.toDouble()) / F32_TEN_THOUSAND) * f32(base108.toDouble())))
        val rate = f32(f32(potential.toDouble()) / F32_TEN_THOUSAND)
        var s0 = f32(rate + 1.0)
        s0 = f32(s0 * f32(perLevel.toDouble()))
        s0 = f32(s0 * levelFactor(level))
        var value = (base108 + fcvtzu(s0)) and 0xFFFFFFFFL
        if (superFlag != 0L) value = superFactor(value, property956)
        return (extra + value) and 0xFFFFFFFFL
    }
}
