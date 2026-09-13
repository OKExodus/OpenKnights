package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.PyRandom
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.WireWriter
import java.math.BigInteger
import java.security.MessageDigest

/**
 * The login / query parts of `daily.py`: Check In (month sign + timed gift chain, S1152 / S1154), the salary flag of
 * the S18, the inactive comeback S3296, the Daily Mission view (S2912) and the Royal Door day (S2720 / S2722, with its
 * four seeded daily tasks). Day boundaries are the device clock's local midnight ([Shops.dayOf]).
 */
object Daily {
    const val C_SIGN_QUERY = 1089
    const val C_SIGN_MONTH = 1091
    const val C_SIGN_GIFT = 1093
    const val S_SIGN_MONTH = 1152
    const val S_SIGN_GIFT = 1154
    const val S_SIGN_REWARD = 1156
    const val SIGN_PROFILE = "sign_in_state_v1"
    const val GIFT_ROWS = 4

    /** After the day's last timed gift: the unsigned maximum never comes due (POLICY value of the reference). */
    const val NO_MORE_GIFTS_WAIT = 0xFFFFFFFFL

    const val C_SALARY = 481
    const val S_SALARY = 512

    const val C_COMEBACK_QUERY = 3073
    const val C_COMEBACK_GIFT = 3075
    const val S_COMEBACK_INFO = 3296
    const val ERROR_COMEBACK = 25000
    val COMEBACK_INACTIVE = byteArrayOf(0)

    const val C_MISSION_INFO = 2539
    const val S_MISSION_INFO = 2912
    const val MISSION_PROFILE = "daily_mission_state_v1"

    const val C_DOOR_INFO = 2369
    const val C_DOOR_DAILY = 2371
    const val C_DOOR_LEVEL_UP = 2373
    const val C_DOOR_DONATE = 2375
    const val C_DOOR_DONATED = 2377
    const val S_DOOR_INFO = 2720
    const val S_DOOR_DONATED = 2722
    const val DOOR_PROFILE = "royal_door_state_v1"
    const val DOOR_TASKS = 4
    val DOOR_TASK_POOL: List<Long> = (1L..8L).toList()

    private fun copy(document: JValue): JObj = document.deepCopy() as JObj

    // --- Check In ------------------------------------------------------------------------------------------------------

    fun monthKey(now: Long): String {
        val moment = Shops.localDatetime(now)
        return "%04d-%02d".format(moment.year, moment.monthValue)
    }

    /**
     * Document from a seed S1152 (`u8 month0, u8 days, u8 wday1, u8 today, u8 n, n × u8 day`): the signed days are
     * kept only when the seed's month byte is the current month (no year check); the timed chain always starts anew.
     */
    fun seedSignIn(monthPayload: ByteArray?, now: Long, provenance: JValue?): JObj {
        val signed = JArr()
        if (monthPayload != null && monthPayload.size >= 5) {
            val n = monthPayload[4].toInt() and 0xFF
            if ((monthPayload[0].toInt() and 0xFF) == Shops.localDatetime(now).monthValue - 1 && monthPayload.size == 5 + n) {
                for (i in 5 until 5 + n) signed.add(JInt(monthPayload[i].toInt() and 0xFF))
            }
        }
        return jobj("profile" to SIGN_PROFILE, "month" to monthKey(now), "signed" to signed, "chain_day" to null, "row" to 1,
            "available_at" to 0, "seed" to (provenance ?: JNull))
    }

    /** (document, changed): a new month empties the list; the first touch of a new day restarts the timed chain. */
    fun signRoll(document: JValue, now: Long, inputs: DailyInputs): Pair<JObj, Boolean> {
        val doc = copy(document)
        var changed = false
        val month = monthKey(now)
        if (PyDocs.at(doc, "month") != JStr(month)) {
            doc["month"] = JStr(month)
            doc["signed"] = JArr()
            changed = true
        }
        val today = Shops.dayOf(now)
        if (PyDocs.get(doc, "chain_day") != JStr(today)) {
            val gift = inputs.timeGift(1) ?: throw PyDocs.TypeError("'NoneType' object is not subscriptable")
            doc["chain_day"] = JStr(today)
            doc["row"] = JInt(1)
            doc["available_at"] = JInt(now + gift.long("wait"))
            changed = true
        }
        return doc to changed
    }

    /** S1152: `month − 1, days in month, weekday of the 1st (0 = Sunday), today, n, signed days (sorted)`. */
    fun monthPayload(document: JObj, now: Long): ByteArray {
        val moment = Shops.localDatetime(now)
        val days = moment.toLocalDate().lengthOfMonth().toLong()
        val first = moment.withDayOfMonth(1)
        val wday1 = (first.dayOfWeek.value % 7).toLong()
        val signed = PyDocs.sorted(PyDocs.at(document, "signed") as JArr).map { PyDocs.long(it) }
        return PyDocs.bytes(listOf(moment.monthValue - 1L, days, wday1, moment.dayOfMonth.toLong(), signed.size.toLong()) + signed)
    }

    /** S1154 `u8 row, u32 wait` (the unsigned maximum after the fourth gift). */
    fun giftPayload(document: JObj, now: Long): ByteArray {
        val row = PyDocs.at(document, "row")
        val wait = if (PyDocs.int(row) > BigInteger.valueOf(GIFT_ROWS.toLong())) NO_MORE_GIFTS_WAIT
            else maxOf(0L, PyDocs.long(PyDocs.at(document, "available_at")) - now)
        return WireWriter().number('B', row).number('I', wait).bytes()
    }

    /** C1089 reply / login: S1154 then S1152. */
    fun signFrames(document: JObj, now: Long): List<Frame> =
        listOf(S_SIGN_GIFT to giftPayload(document, now), S_SIGN_MONTH to monthPayload(document, now))

    // --- Salary ------------------------------------------------------------------------------------------------------------

    /** S18 `title_reward_flag`: 1 = today's salary claimed, else 0. */
    fun salaryFlag(document: JValue?, now: Long): Long {
        val doc = if (Py.truthy(document)) document as JObj else JObj()
        return if (PyDocs.get(doc, "claim_day") == JStr(Shops.dayOf(now))) 1 else 0
    }

    // --- Daily Mission -------------------------------------------------------------------------------------------------------

    fun missionView(document: JValue?, now: Long): JObj {
        val doc = if (Py.truthy(document)) document as JObj else JObj()
        if (PyDocs.get(doc, "day") != JStr(Shops.dayOf(now))) {
            return jobj("profile" to MISSION_PROFILE, "day" to Shops.dayOf(now), "counts" to JObj(), "claimed" to JArr())
        }
        return copy(doc)
    }

    /** Counter hook (`mission_count`, labeled policy): add to a Daily Mission counter, capped at dailyactivities col 103. */
    fun missionCount(document: JValue?, ident: Long, amount: Long, inputs: DailyInputs, now: Long): JObj {
        val doc = missionView(document, now)
        val row = inputs.dailyActivity(ident)
        if (row == null || amount <= 0) return doc
        val counts = doc.obj("counts")
        val before = counts[ident.toString()] ?: JInt(0)
        val sum = JInt(PyDocs.int(before) + BigInteger.valueOf(amount))
        counts[ident.toString()] = if (PyDocs.compare(sum, row["max"]!!) < 0) sum else row["max"]!!
        return doc
    }

    /** S2912 `u8 n, n × (u32 activity, u32 count)` (counts > 0, by id), `u8 m, m × u32 claimed gift`. */
    fun missionPayload(document: JObj): ByteArray {
        val counts = document.obj("counts").entries.filter { PyDocs.compare(it.value, JInt(0)) > 0 }
            .map { PyValues.parseInt(it.key) to it.value }
            .sortedWith { a, b -> val c = a.first.compareTo(b.first); if (c != 0) c else PyDocs.compare(a.second, b.second) }
        val w = WireWriter().number('B', counts.size.toLong())
        for ((k, v) in counts) w.number('I', k).number('I', v)
        val claimed = document.arr("claimed")
        w.number('B', claimed.size.toLong())
        for (g in claimed) w.number('I', g)
        return w.bytes()
    }

    // --- Royal Door ------------------------------------------------------------------------------------------------------------

    /** Four distinct Royal Door tasks of the day (labeled policy: deterministic per world and day). */
    fun doorTasks(worldBirth: String, day: String): List<Long> {
        val digest = MessageDigest.getInstance("SHA-256").digest("royal-door|$worldBirth|$day".toByteArray(Charsets.UTF_8))
        val seed = BigInteger(1, digest.copyOfRange(0, 8).reversedArray())
        return PyRandom.seeded(seed).sample(DOOR_TASK_POOL, DOOR_TASKS).sorted()
    }

    /** The character's Royal Door day (a new day clears the daily claim and donations and picks new tasks). */
    fun doorView(document: JValue?, now: Long, worldBirth: JValue?): JObj {
        val doc = if (Py.truthy(document)) copy(document!!) else jobj("profile" to DOOR_PROFILE, "day" to null,
            "daily_claimed" to false, "level_up_claimed" to 0, "tasks" to JArr(), "donated" to JObj(), "gold_donated" to 0)
        val today = Shops.dayOf(now)
        if (PyDocs.get(doc, "day") != JStr(today)) {
            doc["day"] = JStr(today)
            doc["daily_claimed"] = JBool(false)
            doc["tasks"] = JArr(doorTasks(PyDocs.str(worldBirth), today).mapTo(ArrayList()) { JInt(it) })
            doc["donated"] = JObj()
            doc["gold_donated"] = JInt(0)
        }
        return doc
    }

    /** S2720 `u64 door exp, u32 level, u8 daily claimable, u8 level-up claimable, u8 n, n × u8 task`. */
    fun doorPayload(document: JObj, worldDoor: JObj): ByteArray {
        val level = PyDocs.at(worldDoor, "level")
        val w = WireWriter().number('Q', PyDocs.at(worldDoor, "exp")).number('I', level)
            .number('B', if (Py.truthy(PyDocs.at(document, "daily_claimed"))) 0L else 1L)
            .number('B', if (PyDocs.compare(PyDocs.at(document, "level_up_claimed"), level) < 0) 1L else 0L)
        val tasks = document.arr("tasks")
        w.raw(PyDocs.bytes(listOf(tasks.size.toLong()) + tasks.map { PyDocs.long(it) }))
        return w.bytes()
    }

    /**
     * The world Door after `amount` EXP (`door_after`): level-ups by lv_yijiezhimen col 102 (EXP kept per level); the top
     * level has no col 102. Shared by the donation reply and `WorldContext.raiseDoorExp`.
     */
    fun doorAfter(worldDoor: JObj, amount: Long, inputs: DailyInputs): JObj {
        var level = PyDocs.long(PyDocs.at(worldDoor, "level"))
        var exp = PyDocs.int(PyDocs.at(worldDoor, "exp")) + BigInteger.valueOf(amount)
        while (true) {
            val row = inputs.royalDoorLevel(level)
            val next = row?.get("next_exp")
            if (row == null || next == null || next == JNull || exp < PyDocs.int(next)) break
            exp -= PyDocs.int(next)
            level += 1
        }
        return PyDocs.shallow(worldDoor).also { it["level"] = JInt(level); it["exp"] = JInt(exp) }
    }

    /** S2722 `u32 n, n × (u32 item, u32 donated)` (by item), `u32 gold units donated`. */
    fun donatedPayload(document: JObj): ByteArray {
        val donated = document.obj("donated").entries.map { PyValues.parseInt(it.key) to it.value }
            .sortedWith { a, b -> val c = a.first.compareTo(b.first); if (c != 0) c else PyDocs.compare(a.second, b.second) }
        val w = WireWriter().number('I', donated.size.toLong())
        for ((k, v) in donated) w.number('I', k).number('I', v)
        return w.number('I', PyDocs.at(document, "gold_donated")).bytes()
    }
}
