package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asObj
import java.math.BigInteger

/**
 * Player EXP and level-up outside combat (`player_level.py`: quest, bounty, guild-task and training EXP). roleexp.csv
 * col 102 is the EXP needed to leave a level; the role EXP property (4) is the EXP inside the current level. A level-up
 * sends one S128 with the level (3) and the remaining EXP (4), then the level achievement S578 and the city buildings
 * the new level unlocks.
 */
object PlayerLevel {
    const val ROLE_LEVEL = 3L
    const val ROLE_EXP = 4L
    /** Achievement kind 1 follows the player level. */
    const val ACH_LEVEL = 1L

    /**
     * Add EXP and settle level-ups by roleexp col 102 (at the top level the EXP keeps accumulating). Returns (frames,
     * levels gained): one S128 with EXP (and the level when it changed), then the S578 of every level achievement entry
     * and the S640 of every newly unlocked building when the level changed.
     */
    fun grantExp(owned: Owned, amount: BigInteger, inputs: AcquisitionInputs): Pair<List<Frame>, BigInteger> {
        if (amount.signum() <= 0) return emptyList<Frame>() to BigInteger.ZERO
        owned.roleAdd(ROLE_EXP, amount)
        val levelBefore = owned.roleBits(ROLE_LEVEL)
        val daily = inputs as? DailyInputs
        val top = if (daily != null) BigInteger.valueOf(daily.maxRoleLevel()) else levelBefore
        var exp = owned.roleBits(ROLE_EXP)
        var level = levelBefore
        while (level < top) {
            val need = daily?.roleExp(level.longValueExact())
            if (need == null || need == 0L || exp < BigInteger.valueOf(need)) break
            exp -= BigInteger.valueOf(need)
            level += BigInteger.ONE
        }
        val frames = ArrayList<Frame>()
        val fields = if (level != levelBefore) {
            owned.roleAdd(ROLE_LEVEL, level - levelBefore)
            owned.roleAdd(ROLE_EXP, exp - owned.roleBits(ROLE_EXP))
            listOf(ROLE_LEVEL, ROLE_EXP)
        } else listOf(ROLE_EXP)
        frames.add(Acquisition.S_ROLE to Acquisition.roleUpdatePayload(fields.map { Triple(it, owned.role(it).long("tag"), owned.roleBits(it)) }))
        if (level != levelBefore) {
            for (entry in owned.state.obj("subsystems").obj("achievements").arr("entries")) {
                val wire = entry.asObj.arr("wire_values")
                if (wire[0] == JInt(ACH_LEVEL)) {
                    wire[2] = JInt(level)
                    frames.add(Acquisition.S_ACHIEVEMENT to Acquisition.achievementPayload(wire))
                }
            }
            // City buildings unlocked by the new level join at once (the login refresh does the same for older saves).
            val buildings: JValue? = owned.state.obj("subsystems")["buildings"]
            if (daily != null && buildings != null && buildings != JNull) {
                frames.addAll(Castle.unlockBuildings(owned.state, daily, level.longValueExact(), above = levelBefore.longValueExact()))
            }
        }
        return frames to (level - levelBefore)
    }

    /** [grantExp] of a whole-number amount. */
    fun grantExp(owned: Owned, amount: Long, inputs: AcquisitionInputs): Pair<List<Frame>, BigInteger> =
        grantExp(owned, BigInteger.valueOf(amount), inputs)
}
