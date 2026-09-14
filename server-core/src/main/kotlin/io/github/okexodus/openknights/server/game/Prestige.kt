package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.protocol.WireWriter
import java.math.BigInteger
import java.util.WeakHashMap

/**
 * The login part of `prestige.py`: P-TITLE-AUTO — the title (role 22) is the highest title.csv row whose required
 * Reputation (col 104 ≤ role 12) and campaign stars (col 105 ≤ role 15) the character has; never lowered.
 */
object Prestige {
    const val ROLE_REPUTATION = 12L
    const val ROLE_STARS = 15L
    const val ROLE_TITLE = 22L

    private fun n(value: String?): Long = PyValues.digitInt(value, 0)

    private val cache = WeakHashMap<DailyInputs, List<Triple<Long, Long, Long>>>()

    /** [(title id, Reputation needed, stars needed)] of title.csv, ascending (cached on the inputs). */
    fun titleRows(inputs: DailyInputs): List<Triple<Long, Long, Long>> = synchronized(cache) {
        cache.getOrPut(inputs) {
            inputs.tableRows("title").filter { n(it.field("101")) != 0L }.map { Triple(n(it.field("101")), n(it.field("104")), n(it.field("105"))) }
                .sortedWith(compareBy<Triple<Long, Long, Long>> { it.first }.thenBy { it.second }.thenBy { it.third })
        }
    }

    /** The highest title row both values allow (1 when none). */
    fun titleFor(inputs: DailyInputs, reputation: BigInteger, stars: BigInteger): Long {
        var best = 1L
        for ((ident, needReputation, needStars) in titleRows(inputs)) {
            if (reputation >= BigInteger.valueOf(needReputation) && stars >= BigInteger.valueOf(needStars)) best = maxOf(best, ident)
        }
        return best
    }

    private fun roleBits(owned: Owned, fieldId: Long, default: BigInteger = BigInteger.ZERO): BigInteger =
        try { owned.roleBits(fieldId) } catch (e: Exception) { default }

    const val ACH_REPUTATION = 31L
    private val U32_MAX: BigInteger = BigInteger.valueOf(0xFFFFFFFFL)

    /** S578 [31, step, Reputation] (the captured form after the Arena reward); null without the kind-31 row (`achievement_frame`). */
    fun achievementFrame(owned: Owned): Frame? {
        val subsystems = owned.state["subsystems"] as? JObj ?: JObj()
        val achievements = subsystems["achievements"] as? JObj ?: JObj()
        for (entry in (achievements["entries"] as? JArr) ?: JArr()) {
            val wire = (entry as JObj).arr("wire_values")
            if (wire[0] == JInt(ACH_REPUTATION)) {
                wire[2] = JInt(roleBits(owned, ROLE_REPUTATION).min(U32_MAX))
                if (wire.size != 3) throw IllegalArgumentException("pack expected 3 items for packing (got ${wire.size})")
                return Acquisition.S_ACHIEVEMENT to WireWriter().number('B', wire[0]).number('B', wire[1]).number('I', wire[2]).bytes()
            }
        }
        return null
    }

    private fun setRole(owned: Owned, fieldId: Long, value: Long): Frame = owned.roleAdd(fieldId, BigInteger.valueOf(value) - owned.roleBits(fieldId))

    /** Raise role 22 to the title the current Reputation and stars allow. Returns the S128 frames. */
    fun promote(owned: Owned, inputs: DailyInputs): List<Frame> {
        val current = try { owned.roleBits(ROLE_TITLE) } catch (e: Exception) { return emptyList() }
        val target = titleFor(inputs, roleBits(owned, ROLE_REPUTATION), roleBits(owned, ROLE_STARS))
        if (BigInteger.valueOf(target) <= current) return emptyList()
        return listOf(setRole(owned, ROLE_TITLE, target))
    }
}
