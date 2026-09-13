package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.WireWriter
import java.math.BigInteger
import java.util.WeakHashMap

/**
 * Achievements / medals (`achievements.py`): `achieve.csv` rows (row id = kind × 100 + step), the counters no other
 * code keeps (hero evolve 21, gear evolve 22), and the completion of every reached step of the live kinds inside the
 * follow-up `daily_counters` revision: S576 per completed step (its new-medal mail S258 goes right after it, written to
 * the world after the commit), one S578 per changed kind, one S128 {18} with the added medal points.
 */
object Achievements {
    const val S_COMPLETE = 576
    const val S_ACHIEVEMENT = 578
    const val S_MAIL_ADD = 258
    const val ROLE_ID = 0L
    const val ROLE_ACHIEVE_POINT = 18L
    const val TEXT_MAIL_TITLE = 1100L
    const val TEXT_MAIL_BODY = 1101L
    private val MAIL_SENDER = "System".toByteArray(Charsets.UTF_8)
    const val MAIL_TYPE = 0L
    /** Labeled policy: the medal mail pays achieve.csv f108 as Gold and f109 as Diamonds. */
    const val MEDAL_REWARD_POLICY = "gold_f108_diamond_f109"

    /** The kinds that complete steps offline (the others stay as the save has them). */
    val LIVE_KINDS = setOf(1L, 2L, 3L, 4L, 6L, 7L, 21L, 22L, 26L, 27L, 28L, 29L, 31L, 12L, 30L, 32L, 33L)
    /** Events of the counters this module keeps. */
    val FED = mapOf("hero_evolve" to 21L, "gear_evolve" to 22L)
    val ACTION_FEEDS = mapOf("evolve_hero" to "hero_evolve", "evolve_leader_hero" to "hero_evolve", "evolve_gear" to "gear_evolve")
    /** Marker: the action's own reply moved an achievement (an S578 in its packets). */
    const val CHECK = "achievements"
    val EVENTS = FED.keys + CHECK

    private fun int(value: String?, default: Long = 0): Long = PyValues.digitInt(value, default)

    private val rowCache = WeakHashMap<DailyInputs, Map<Long, JObj>>()

    /** {row id: row} of achieve.csv; {} when the catalog has no such table. */
    fun rows(inputs: DailyInputs): Map<Long, JObj> = synchronized(rowCache) {
        rowCache.getOrPut(inputs) {
            val fields = try { inputs.tableRows("achieve") } catch (e: Exception) { return@getOrPut emptyMap() }
            val table = LinkedHashMap<Long, JObj>()
            for (f in fields) {
                val ident = int(f.field("101"))
                val kind = int(f.field("105"))
                if (ident == 0L || kind == 0L) continue
                val target = PyValues.strip(f.field("106") ?: "")
                table[ident] = jobj("id" to ident, "kind" to kind, "step" to ident - kind * 100, "name_text" to int(f.field("103")),
                    "target" to (if (PyValues.isDigit(target.trimStart('-'))) int(target) else null),
                    "gold" to int(f.field("108")), "f109" to int(f.field("109")), "points" to int(f.field("110")),
                    "enabled" to (int(f.field("111")) == 1L), "broadcast" to (int(f.field("900")) == 1L))
            }
            table
        }
    }

    /** The rows the entry [kind, step, progress] completes now: live kinds only, enabled rows with a target. */
    fun completable(values: JArr, table: Map<Long, JObj>): List<JObj> {
        val kind = PyDocs.long(values[0])
        val step = PyDocs.long(values[1])
        val progress = values[2]
        val done = ArrayList<JObj>()
        if (kind !in LIVE_KINDS) return done
        while (true) {
            val row = table[kind * 100 + step + done.size]
            if (row == null || row["enabled"] != io.github.okexodus.openknights.exact.JBool(true) || row["target"] == null ||
                row["target"] == io.github.okexodus.openknights.exact.JNull || PyDocs.compare(progress, row["target"]!!) < 0) return done
            done.add(row)
        }
    }

    private fun text(inputs: DailyInputs, ident: Long, fallback: String = ""): String = try {
        inputs.text(ident).ifEmpty { fallback }
    } catch (e: Exception) {
        fallback
    }

    fun medalReward(row: JObj): JObj {
        val reward = BattleReport.emptyReward()
        if (MEDAL_REWARD_POLICY == "gold_f108_diamond_f109") {
            reward["gold"] = row["gold"]!!
            reward["diamond"] = row["f109"]!!
        }
        return reward
    }

    private fun mail(inputs: DailyInputs, row: JObj, recipient: io.github.okexodus.openknights.exact.JValue, atPacket: Int): JObj {
        val name = text(inputs, row.long("name_text"))
        val body = text(inputs, TEXT_MAIL_BODY, "Congratulations, you've earned the Medal [##0##].").replace("##0##", name)
        return jobj("at_packet" to atPacket, "kind" to row["kind"], "step" to row["step"], "recipient" to recipient,
            "type" to MAIL_TYPE, "sender_name_hex" to MAIL_SENDER.toHexString(),
            "title_hex" to text(inputs, TEXT_MAIL_TITLE, "New Medal Unlocked!").toByteArray(Charsets.UTF_8).toHexString(),
            "body_hex" to body.toByteArray(Charsets.UTF_8).toHexString(), "reward" to medalReward(row))
    }

    /** What [evaluate] returns: the frames, the completed [kind, step] pairs, the added points and the mail specs. */
    class Result(val packets: MutableList<Frame> = ArrayList(), val completed: JArr = JArr(), var points: Long = 0,
                 val mails: JArr = JArr())

    /**
     * Advance the counters this module keeps (FED events) and complete every reached step of the live kinds (`evaluate`).
     * Per changed kind: S576 per completed step (its mail spec records `at_packet` = `base` + its index in these frames),
     * then one S578 [kind, step, progress]; one S128 {18} with the added points at the end.
     */
    fun evaluate(owned: Owned, inputs: DailyInputs, events: List<DailyHooks.Event>, base: Int = 0): Result {
        val out = Result()
        val section = (owned.state["subsystems"] as? JObj)?.get("achievements")
        val table = rows(inputs)
        if (section == null || section == io.github.okexodus.openknights.exact.JNull || table.isEmpty()) return out
        val feeds = LinkedHashMap<Long, Long>()
        for (e in events) {
            val kind = FED[e.event]
            if (kind != null && e.units > 0) feeds[kind] = (feeds[kind] ?: 0L) + e.units
        }
        var recipient: io.github.okexodus.openknights.exact.JValue? = null
        for (f in (owned.state["role_properties"] as? JArr) ?: JArr()) {
            if (f.asObj["id"] == JInt(ROLE_ID)) { recipient = f.asObj.obj("value")["bits"] ?: io.github.okexodus.openknights.exact.JNull; break }
        }
        val packets = out.packets
        for (entry in section.asObj.arr("entries")) {
            val values = entry.asObj.arr("wire_values")
            val fed = feeds[PyDocs.long(values[0])] ?: 0L
            if (fed != 0L) values[2] = JInt(minOf(PyDocs.int(values[2]) + BigInteger.valueOf(fed), BigInteger.valueOf(0xFFFFFFFFL)))
            val done = completable(values, table)
            for (row in done) {
                packets.add(S_COMPLETE to WireWriter().number('B', row["kind"]!!).number('B', row["step"]!!).bytes())
                if (recipient != null && recipient != io.github.okexodus.openknights.exact.JNull) out.mails.add(mail(inputs, row, recipient, base + packets.size))
                out.completed.add(jarr(row["kind"], row["step"]))
                out.points += row.long("points")
            }
            values[1] = JInt(PyDocs.int(values[1]) + BigInteger.valueOf(done.size.toLong()))
            if (done.isNotEmpty() || fed != 0L) packets.add(S_ACHIEVEMENT to Acquisition.achievementPayload(values))
        }
        if (out.points != 0L) {
            try {
                packets.add(owned.roleAdd(ROLE_ACHIEVE_POINT, out.points))
            } catch (e: Acquisition.Rejected) {
                out.points = 0                       // a save without property 18: no points frame
            }
        }
        return out
    }

    /**
     * World write of the new-medal mails after the character's commit (`deliver_mails`, one `mail` document update);
     * returns the reply with each mail's S258 brief at its `at_packet` index. `update(change)` = the social context's
     * `update("mail", …)`.
     */
    fun deliverMails(packets: List<Frame>, mails: JArr, update: ((JObj) -> Pair<List<JObj>, JObj?>) -> List<JObj>, now: Long,
                     offset: Long = 0): List<Frame> {
        val out = ArrayList(packets)
        if (mails.isEmpty()) return out
        val specs = mails.map { it.asObj }
        val made = update { document ->
            val created = specs.map { m ->
                Mail.newMail(document, PyDocs.long(m["recipient"]), m.long("type"), 0, m.str("sender_name_hex").hexBytes(),
                    m.str("title_hex").hexBytes(), m.str("body_hex").hexBytes(), m["reward"], now)
            }
            created to jobj("to" to specs[0]["recipient"], "mails" to JArr(created.mapTo(ArrayList()) { it["id"]!! }),
                "medals" to JArr(specs.mapTo(ArrayList()) { jarr(it["kind"], it["step"]) }))
        }
        val pairs = specs.zip(made).sortedByDescending { it.first.long("at_packet") }
        for ((spec, mail) in pairs) out.add(spec.long("at_packet").toInt(), Mail.S_ADD to Mail.brief(mail, offset))
        return out
    }
}
