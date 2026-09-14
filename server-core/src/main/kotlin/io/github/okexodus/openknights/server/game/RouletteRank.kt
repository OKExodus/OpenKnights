package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.WireWriter

/**
 * The Fate Store (roulette) ranking of `roulette_rank.py`: C643 / S706 lists and the C645 rank reward (S708).
 *
 * S706 = `u8 tab, u8 n, n × (u32 role, cstring name, u32 score, u8 flagA, u8 flagB)`; rank = row + 1; tabs 1 today, 2
 * yesterday, 3 total. Flags: 0 none, 1 claimable (the own row's button), 2 claimed. Score = Fate Vouchers used; the
 * listing threshold is property 500001 (4000). POLICY: the world document keeps today's and yesterday's scores (the
 * device's local days) and every participant's total; the save's counters are authoritative for its own score; top 5
 * listed; yesterday's reward can be claimed until the next local midnight.
 */
object RouletteRank {
    const val C_RANK = 643
    const val S_RANK = 706
    const val C_CLAIM = 645
    const val S_CLAIM = 708
    const val TODAY = 1
    const val YESTERDAY = 2
    const val TOTAL = 3
    const val THRESHOLD_PROPERTY = 500001
    const val THRESHOLD_DEFAULT = 4000L
    const val LISTED = 5
    const val VOUCHER = 30232L
    /** rank → (bead box, count, vouchers, Fate Vouchers needed): the captured S3872 rows. */
    val REWARDS: Map<Int, List<Long>> = mapOf(1 to listOf(370443L, 3L, 1200L, 6000L), 2 to listOf(370443L, 2L, 1000L, 5000L),
        3 to listOf(370442L, 3L, 800L, 4000L), 4 to listOf(370442L, 2L, 800L, 4000L), 5 to listOf(370442L, 1L, 800L, 4000L))
    const val PROFILE = "roulette_rank_world_v1"
    const val CLAIMS_PROFILE = "roulette_claims_v1"

    fun emptyDocument(): JObj = jobj("profile" to PROFILE, "days" to JObj(), "total" to JObj())

    private fun setdefault(d: JObj, key: String): JObj = (d[key] ?: JObj().also { d[key] = it }) as JObj

    /** The participant's own counters (the save is authoritative); only today and yesterday are kept. */
    fun record(document: JObj, role: Long, todayScore: JValue, totalScore: JValue, today: String, yesterday: String): JObj {
        setdefault(setdefault(document, "days"), today)[role.toString()] = JInt(PyDocs.int(todayScore))
        setdefault(document, "total")[role.toString()] = JInt(PyDocs.int(totalScore))
        val days = JObj()
        for ((d, v) in document.obj("days")) if (d == today || d == yesterday) days[d] = v
        document["days"] = days
        return document
    }

    /** The top [LISTED] (role, score) rows of a tab at or above the threshold, by score descending, then role. */
    fun listing(document: JObj, tab: Int, today: String, yesterday: String, threshold: Long): List<Pair<Long, JValue>> {
        val scores = if (tab == TOTAL) (document["total"] ?: JObj()) as JObj
            else (((document["days"] ?: JObj()) as JObj)[if (tab == TODAY) today else yesterday] ?: JObj()) as JObj
        val rows = scores.entries.filter { PyDocs.compare(it.value, JInt(threshold)) >= 0 }.map { PyValues.parseLong(it.key) to it.value }
        return rows.sortedWith { a, b ->
            val c = PyDocs.compare(b.second, a.second)
            if (c != 0) c else a.first.compareTo(b.first)
        }.take(LISTED)
    }

    /** The own row's flag on the Yesterday tab: (1 claimable | 2 claimed | 0 not ranked / below its rank's need, rank). */
    fun ownFlag(rows: List<Pair<Long, JValue>>, role: Long, claimed: JValue?): Pair<Int, Int?> {
        rows.forEachIndexed { i, (who, score) ->
            val index = i + 1
            if (who == role) {
                if (Py.truthy(claimed)) return 2 to index
                return (if (PyDocs.compare(score, JInt(REWARDS.getValue(index)[3])) >= 0) 1 else 0) to index
            }
        }
        return 0 to null
    }

    fun rankPayload(tab: Int, rows: List<Pair<Long, JValue>>, names: Map<Long, ByteArray>, flags: Map<Long, Int>): ByteArray {
        val w = WireWriter().number('B', tab.toLong()).number('B', rows.size.toLong())
        for ((role, score) in rows) {
            val flag = flags[role] ?: 0
            val name = names[role] ?: ByteArray(0)
            val nul = name.indexOf(0.toByte())
            w.number('I', role).raw(if (nul >= 0) name.copyOfRange(0, nul) else name).raw(byteArrayOf(0))
            w.number('I', score).number('B', flag.toLong()).number('B', flag.toLong())
        }
        return w.bytes()
    }

    /** C645: the rank's bead box and vouchers, the claimed day recorded, S708. */
    fun planClaim(owned: Owned, claims: JValue?, rank: Int, yesterday: String): Plan {
        val (bead, count, vouchers, _) = REWARDS.getValue(rank)
        val frames = ArrayList<Frame>()
        frames.add(owned.grantItem(bead, count))
        frames.add(owned.grantItem(VOUCHER, vouchers))
        val reward = BattleReport.emptyReward()
        reward["items"] = jarr(jarr(bead, count), jarr(VOUCHER, vouchers))
        val claimed = JObj()
        val previous = if (Py.truthy(claims)) (claims as JObj)["claimed"] else null
        (previous as? JObj ?: (if (previous == null) JObj() else throw PyDocs.TypeError("'${previous}' object is not a mapping")))
            .forEach { (k, v) -> claimed[k] = v }
        claimed[yesterday] = JInt(rank)
        val after = jobj("profile" to CLAIMS_PROFILE, "claimed" to claimed)
        frames.add(S_CLAIM to BattleReport.encodeReward(reward))
        return Plan(jobj("rank" to rank, "day" to yesterday, "roulette_claims_after" to after,
            "evidence_class" to "native_use_capture_rewards_policy"), frames)
    }
}
