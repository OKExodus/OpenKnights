package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.PyInt
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.TypedValues
import io.github.okexodus.openknights.server.game.HeroDictionaryProgression.f32
import io.github.okexodus.openknights.server.game.HeroDictionaryProgression.truncU32
import io.github.okexodus.openknights.server.game.HeroDictionaryProgression.unpackHeroId
import java.math.BigInteger

/**
 * The owned-hero base-stat model of `hero_stats.py`, shared by evolution, whole-card Fortify, EXP-item Fortify and
 * the leader repair:
 *
 *     wire_stat[i] = floor(full_grow[i] * (level + grade + 3) * permille[i] / 10000) + dev[i]
 *
 * `full_grow` = trunc(f32((1 + sum503 / 10000) * raw511..514)) at the CURRENT packed grade (binary32 at every step);
 * `permille` = 10000 + property 533 for a super-class digit + the awaken slots' stat bonuses; `dev` = fields 15..18.
 * The model is only applied to a hero whose current wire stats it reproduces exactly; anything else is rejected
 * before any change.
 */
object HeroStats {
    const val UID = 0L
    const val TEMPLATE = 1L
    const val LEVEL = 2L
    const val EXP = 3L
    val STAT_IDS = listOf(4L, 6L, 8L, 10L)
    val GROW_IDS = listOf(5L, 7L, 9L, 11L)
    const val GROW_TYPE = 13L
    const val IS_GUIDE = 14L
    val DEV_IDS = listOf(15L, 16L, 17L, 18L)
    val REBORN_IDS = listOf(19L, 20L, 21L, 22L, 23L)
    const val AWAKEN = 24L
    const val U32 = 0xFFFFFFFFL
    val STAT_KINDS = mapOf(1L to 0, 7L to 1, 8L to 2, 6L to 3)
    const val AWAKEN_STAT_TYPE = 4L
    const val ERROR_INVALID = 102

    open class ProfileUnsupported(message: String, val code: Int = ERROR_INVALID) : IllegalArgumentException(message)

    /** The exception a check raises (`reject(message)` / `reject(message, code)` of the reference). */
    fun interface Reject {
        fun of(message: String, code: Int): Exception
    }

    val PROFILE_UNSUPPORTED = Reject { m, c -> ProfileUnsupported(m, c) }

    /** `field_map(fields)`: {field id: value} (a repeated id is refused). */
    fun fieldMap(fields: JArr): LinkedHashMap<Long, JObj> {
        val result = LinkedHashMap<Long, JObj>()
        for (field in fields) {
            val f = field.asObj
            val id = f.long("id")
            if (id in result) throw PyValues.ValueError("Duplicate hero dynamic property ID")
            result[id] = f.obj("value")
        }
        return result
    }

    /** `recompute_grow(raw_511_514, potential_rate)`: grow[i] = trunc(f32(f32(1 + rate/10000) * raw[i])), full width. */
    fun recomputeGrow(raw511to514: List<Long>, potentialRate: Long): List<Long> {
        val factor = f32(f32(f32((potentialRate and U32).toDouble()) / f32(10000.0)) + f32(1.0))
        return raw511to514.map { truncU32(f32(factor * f32((it and U32).toDouble()))) }
    }

    /** `awaken_permille(awaken_level, awaken_slots)`: per-stat permille of the active awaken slots (1..level, type 4). */
    fun awakenPermille(awakenLevel: BigInteger, awakenSlots: JArr?): List<Long> {
        val bonus = longArrayOf(0, 0, 0, 0)
        for (s in awakenSlots ?: JArr()) {
            val slot = s.asObj
            val number = (slot["slot"] as? JInt)?.value ?: BigInteger.ZERO
            if (number > awakenLevel || (slot["type"] as? JInt)?.value?.toLong() != AWAKEN_STAT_TYPE) continue
            for ((kindKey, permilleKey) in listOf("kind" to "permille", "kind2" to "permille2")) {
                val kind = (slot[kindKey] as? JInt)?.value?.toLong()
                val permille = (slot[permilleKey] as? JInt)?.value?.toLong()
                if (kind != null && kind in STAT_KINDS && permille != null && permille != 0L) bonus[STAT_KINDS.getValue(kind)] += permille
            }
        }
        return bonus.toList()
    }

    /** `stat_permille(template, inputs, awaken_level, reject)`: 10000 + super-class property 533 + awaken bonuses, per stat. */
    fun statPermille(template: BigInteger, inputs: JObj, awakenLevel: BigInteger, reject: Reject = PROFILE_UNSUPPORTED): List<Long> {
        val digit = unpackHeroId(template).hundredsDigit
        var superPermille = 0L
        if (digit % 3 != 0L) {
            val p533 = inputs["property_533"] as? JInt
                ?: throw reject.of("Super-class hero requires property 533 in the catalog inputs", ERROR_INVALID)
            superPermille = p533.value.longValueExact()
        }
        if (awakenLevel.signum() != 0) {
            if ("awaken_slots" !in inputs) throw reject.of("Awakened hero requires its herojuexing slots in the catalog inputs", ERROR_INVALID)
            if (!Py.truthy(inputs["awaken_slots"])) throw reject.of("Awakened hero has no configured herojuexing row", ERROR_INVALID)
        }
        val bonus = awakenPermille(awakenLevel, inputs["awaken_slots"] as? JArr)
        return bonus.map { 10000 + superPermille + it }
    }

    fun statPermille(template: Long, inputs: JObj, awakenLevel: BigInteger, reject: Reject = PROFILE_UNSUPPORTED): List<Long> =
        statPermille(BigInteger.valueOf(template), inputs, awakenLevel, reject)

    /** `base_stats(full_grow, level, grade, permille, dev)`: floor(full_grow * (level + grade + 3) * permille / 10000) + dev. */
    fun baseStats(fullGrow: List<BigInteger>, level: BigInteger, grade: Long, permille: List<Long>, dev: List<BigInteger>): List<BigInteger> {
        val factor = level + BigInteger.valueOf(grade) + BigInteger.valueOf(3)
        return fullGrow.indices.filter { it < permille.size && it < dev.size }.map { i ->
            PyInt.floorDiv(fullGrow[i] * factor * BigInteger.valueOf(permille[i]), BigInteger.valueOf(10000)) + dev[i]
        }
    }

    /** `hero_profile(values)`: grow type, guide, development, reborn and awaken fields (0 when absent). */
    fun heroProfile(values: Map<Long, JObj>): JObj {
        fun bits(fieldId: Long): BigInteger {
            val value = values[fieldId] ?: return BigInteger.ZERO
            return when (val b = value["bits"]) { null -> BigInteger.ZERO; is JInt -> b.value; else -> throw IllegalStateException("bits is not an integer") }
        }
        return jobj("grow_type" to bits(GROW_TYPE), "guide" to bits(IS_GUIDE), "dev" to DEV_IDS.map { bits(it) },
            "reborn" to REBORN_IDS.map { bits(it) }, "awaken" to bits(AWAKEN))
    }

    private fun width(value: JObj): Int = when (TypedValues.WIDTH_FORMAT.getValue(value.long("tag").toInt())) {
        'B' -> 8; 'H' -> 16; 'I' -> 32; else -> 64
    }

    private fun scalar(value: JObj?): Boolean =
        value != null && "bits" in value && (value["tag"] as? JInt)?.value?.toInt() in TypedValues.WIDTH_FORMAT

    private fun bitsOf(value: JObj): BigInteger = (value.getValue("bits") as JInt).value

    /**
     * `resolve_profile(fields, inputs, reject, guide_code)`: validates one owned hero against the bounded stat model and
     * returns its components (values, profile, template, level, grade, full / wire grow, widths, permille, dev, …).
     * Level tag 4, EXP / stat tag 6, growth tag 4; guiding heroes and a nonzero GrowType are refused; the growth is
     * recomputed from the catalog when its inputs are present; the model must reproduce the wire stats exactly.
     */
    fun resolveProfile(fields: JArr, inputs: JObj?, reject: Reject = PROFILE_UNSUPPORTED, guideCode: Int = ERROR_INVALID): JObj {
        if (inputs == null) throw reject.of("Catalog inputs are unavailable for the requested hero", ERROR_INVALID)
        val values = fieldMap(fields)
        for (fieldId in listOf(UID, TEMPLATE, LEVEL, EXP) + STAT_IDS + GROW_IDS) {
            if (!scalar(values[fieldId])) throw reject.of("Hero field $fieldId is absent or not a scalar", ERROR_INVALID)
        }
        if (values.getValue(LEVEL).long("tag") != 4L || values.getValue(EXP).long("tag") != 6L ||
            STAT_IDS.any { values.getValue(it).long("tag") != 6L } || GROW_IDS.any { values.getValue(it).long("tag") != 4L }) {
            throw reject.of("Hero level/EXP/stat/growth tags are outside the evidenced profile", ERROR_INVALID)
        }
        for (fieldId in listOf(GROW_TYPE, IS_GUIDE) + DEV_IDS + REBORN_IDS + AWAKEN) {
            val value = values[fieldId]
            if (value != null && !scalar(value)) throw reject.of("Hero field $fieldId is not a scalar", ERROR_INVALID)
        }
        if (bitsOf(values.getValue(UID)).signum() == 0 || bitsOf(values.getValue(TEMPLATE)).signum() == 0) {
            throw reject.of("Hero identity fields must be nonzero", ERROR_INVALID)
        }
        val profile = heroProfile(values)
        if (profile.int("guide").signum() != 0) throw reject.of("Guiding hero is outside the supported profile", guideCode)
        if (profile.int("grow_type").signum() != 0) throw reject.of("Hero GrowType is nonzero; profile not evidenced", ERROR_INVALID)
        val template = bitsOf(values.getValue(TEMPLATE))
        val inputTemplate = inputs["template"]
        if (inputTemplate != null && inputTemplate != JNull && inputTemplate != JInt(template)) {
            throw reject.of("Catalog inputs do not belong to the requested hero template", ERROR_INVALID)
        }
        val dims = unpackHeroId(template)
        val level = bitsOf(values.getValue(LEVEL))
        val wireGrow = GROW_IDS.map { bitsOf(values.getValue(it)) }
        val widths = GROW_IDS.map { width(values.getValue(it)) }
        val raw = inputs["raw_511_514"]
        val potential = inputs["current_potential_rate"]
        val fullGrow: List<BigInteger>
        val recomputed: Boolean
        if (raw != null && raw != JNull && potential != null && potential != JNull) {
            fullGrow = recomputeGrow((raw as JArr).map { (it as JInt).value.toLong() }, (potential as JInt).value.toLong()).map { BigInteger.valueOf(it) }
            for (i in fullGrow.indices) {
                if (i >= wireGrow.size) break
                val mask = BigInteger.ONE.shiftLeft(widths[i]) - BigInteger.ONE
                if (fullGrow[i].and(mask) != wireGrow[i]) throw reject.of("Hero growth on the wire does not match the catalog recompute at its grade", ERROR_INVALID)
            }
            recomputed = true
        } else {
            fullGrow = wireGrow.toList()
            recomputed = false
        }
        val permille = statPermille(template, inputs, profile.int("awaken"), reject)
        val dev = profile.arr("dev").map { (it as JInt).value }
        val expected = baseStats(fullGrow, level, dims.grade, permille, dev)
        val wireStats = STAT_IDS.map { bitsOf(values.getValue(it)) }
        if (expected != wireStats) throw reject.of("Hero stats are not reproduced by the bounded stat model; profile unsupported", ERROR_INVALID)
        val components = ArrayList<String>()
        if (dev.any { it.signum() != 0 }) components.add("development")
        if (profile.arr("reborn").any { (it as JInt).value.signum() != 0 }) components.add("reborn_fields_inert")
        if (dims.hundredsDigit % 3 != 0L) components.add("super_class_property_533")
        if (profile.int("awaken").signum() != 0) components.add("awaken_herojuexingskill")
        val valuesJson = JObj()
        for ((k, v) in values) valuesJson[k.toString()] = v
        return jobj("values" to valuesJson, "profile" to profile, "template" to template, "level" to level, "grade" to dims.grade,
            "full_grow" to fullGrow, "wire_grow" to wireGrow, "grow_widths" to widths, "grow_recomputed" to recomputed,
            "permille" to permille, "dev" to dev, "zero_profile" to components.isEmpty(),
            "components" to components,
            "evidence_class" to (if (components.isEmpty()) "capture_observed_zero_profile" else "bounded_calculation_owned_profile"))
    }

    /** `stats_at(resolved, level, grade, full_grow)`: the model stats of a resolved hero at another level / grade / growth. */
    fun statsAt(resolved: JObj, level: BigInteger, grade: Long? = null, fullGrow: List<BigInteger>? = null): List<BigInteger> =
        baseStats(fullGrow ?: resolved.arr("full_grow").map { (it as JInt).value }, level, grade ?: resolved.long("grade"),
            resolved.arr("permille").map { (it as JInt).value.toLong() }, resolved.arr("dev").map { (it as JInt).value })

    /** `wire_grow_bits(full_grow, widths)`: the low bits the wire field keeps of each growth value. */
    fun wireGrowBits(fullGrow: List<BigInteger>, widths: List<Int>): List<BigInteger> =
        fullGrow.indices.filter { it < widths.size }.map { fullGrow[it].and(BigInteger.ONE.shiftLeft(widths[it]) - BigInteger.ONE) }
}
