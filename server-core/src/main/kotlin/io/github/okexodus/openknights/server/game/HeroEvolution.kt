package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.protocol.WireWriter

/**
 * The parts of `hero_evolution.py` that entering the game reaches: the S2240 leader-info payload, the evolution
 * refusal and the check of the labeled evolution test policy (service start). The C71 / C2083 codecs and planners
 * belong to the hero group.
 */
object HeroEvolution {
    const val LEADER_INFO_OPCODE = 2240
    val STAT_IDS = listOf(4L, 6L, 8L, 10L)
    val GROW_IDS = listOf(5L, 7L, 9L, 11L)
    const val ERROR_INVALID = 102
    const val TEST_POLICY_PROFILE = "evolution_extended_tiers_test_policy_v1"

    open class EvolutionRejected(message: String, val code: Int = ERROR_INVALID) : IllegalArgumentException(message)

    private fun uint(value: Long, width: Int, label: String): Long {
        if (value < 0 || value >= (1L shl width)) throw PyValues.ValueError("$label must fit uint$width")
        return value
    }

    /** S2240 A != 0 branch: u32 A (the leader's owned uid), u32 B (its progress key). Eight bytes. */
    fun leaderInfoPayload(fieldA: Long, fieldB: Long): ByteArray =
        WireWriter().u32(uint(fieldA, 32, "Leader field A")).u32(uint(fieldB, 32, "Leader field B")).bytes()

    /**
     * `check_test_policy(test_policy)`: only the explicitly labeled local TEST policy document (or null) is accepted;
     * the loaded policy is returned unchanged.
     */
    fun checkTestPolicy(testPolicy: JValue?): JValue? {
        if (testPolicy == null || testPolicy == io.github.okexodus.openknights.exact.JNull) return null
        val doc = if (testPolicy is JObj) (testPolicy["document"] ?: testPolicy) else null
        if (doc !is JObj || (doc["profile"] as? JStr)?.value != TEST_POLICY_PROFILE || (doc["class"] as? JStr)?.value != "preservation_policy_test") {
            throw EvolutionRejected("Evolution test policy document is not the labeled extended-tiers profile")
        }
        return testPolicy
    }
}
