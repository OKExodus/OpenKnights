package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.PyRandom
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import java.math.BigInteger
import java.security.MessageDigest

/**
 * The login / query parts of `daily.py`: Check In (month sign + timed gift chain, S1152 / S1154), the salary flag of
 * the S18, the inactive comeback S3296, the Daily Mission view (S2912) and the Royal Door day (S2720 / S2722, with its
 * four seeded daily tasks), and the claims of those systems (check-in, timed gifts, salary, Daily Mission gifts, Royal
 * Door daily / level-up bonuses and donations). Day boundaries are the device clock's local midnight ([Shops.dayOf]).
 * Planners work on the [Owned] view and return the per-character documents (`<table>_after`) the store commits.
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
    const val C_MISSION_GIFT = 2541
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

    // --- the daily actions (C1091, C1093, C481, C2541, C2371, C2373, C2375) ------------------------------------------

    const val ROLE_TITLE = 22L
    const val PAIR_DIAMOND = 20003L
    const val PAIR_GOLD = 20001L
    const val ERROR_SIGNED = 23000              // "You've already checked in today"
    const val ERROR_GIFTS_DONE = 23001          // "You've claimed all time based rewards"
    const val ERROR_GIFT_WAIT = 23002           // "You cannot claim the rewards yet"
    /** Timed gift count (server-only; x1 observed for rows 1 and 2, labeled policy for 3 / 4). */
    const val GIFT_COUNT = 1L
    const val SALARY_PROFILE = "salary_state_v1"
    const val ERROR_SALARY = 16000              // "Title reward has been claimed"
    const val S_MISSION_GIFT = 2914
    const val ERROR_MISSION = 30000             // "The Daily Event Reward is invalid" (candidate text, policy)
    const val S_DOOR_DAILY = 2724
    const val S_DOOR_LEVEL_UP = 2726
    const val ERROR_DOOR_DAILY = 57000
    const val ERROR_DOOR_LEVEL_UP = 57001
    const val ERROR_DONATE_ITEMS = 57006
    const val ERROR_DONATE_GOLD = 57008
    /** quest_yijiezhimen task 2 "Donate Items": the only row with the donate button. */
    const val TASK_DONATE = 2L
    /** The donate screen clamps the Gold amount to Gold / 10,000: one unit = 10,000 Gold. */
    const val GOLD_UNIT = 10_000L
    const val ERROR_DONATE_NOT_LISTED = 57003
    const val ERROR_DONATE_LIMIT = 57004
    const val ERROR_DONATE_GOLD_LIMIT = 57005
    const val ERROR_DONATE_SHORT = 57007
    const val S_DOOR_BANNER = 2728

    private fun notSubscriptable(): Nothing = throw PyDocs.TypeError("'NoneType' object is not subscriptable")

    /**
     * Items of (kind, item, count) triples in table order (S68 / S64 each) — the live grant order of these claims;
     * Gold / Diamond pairs are paid into the role and announced by one S128 after the items; the Reward item list is
     * ascending by item id (`grant_triples`).
     */
    fun grantTriples(owned: Owned, triples: List<JArr>, reward: JObj): List<Frame> {
        val frames = ArrayList<Frame>()
        val roles = ArrayList<Long>()
        for (triple in triples) {
            if (triple.size != 3) throw PyValues.ValueError("not enough values to unpack (expected 3, got ${triple.size})")
            val item = PyDocs.long(triple[1])
            val count = PyDocs.long(triple[2])
            when (item) {
                PAIR_DIAMOND -> {
                    owned.roleAdd(Acquisition.DIAMOND, count)
                    reward["diamond"] = JInt(PyDocs.int(PyDocs.at(reward, "diamond")) + BigInteger.valueOf(count))
                    roles.add(Acquisition.DIAMOND)
                }
                PAIR_GOLD -> {
                    owned.roleAdd(Acquisition.GOLD, count)
                    reward["gold"] = JInt(PyDocs.int(PyDocs.at(reward, "gold")) + BigInteger.valueOf(count))
                    roles.add(Acquisition.GOLD)
                }
                else -> {
                    frames.add(owned.grantItem(item, count))
                    reward.arr("items").add(jarr(item, count))
                }
            }
        }
        val items = reward.arr("items")
        val sorted = PyDocs.sorted(items)
        items.clear()
        items.addAll(sorted)
        if (roles.isNotEmpty()) {
            frames.add(Acquisition.S_ROLE to Acquisition.roleUpdatePayload(roles.toSortedSet().map { f ->
                Triple(f, owned.role(f).long("tag"), owned.roleBits(f))
            }))
        }
        return frames
    }

    /**
     * C1091 month check-in (`plan_month_sign`): [S68 / S64 per milestone item, S1156 Reward] → S1152. Milestones at the
     * month's check-in counts of qiandao.csv.
     */
    fun planMonthSign(owned: Owned, inputs: DailyInputs, document: JObj, now: Long): Plan {
        val doc = signRoll(document, now, inputs).first
        val today = Shops.localDatetime(now).dayOfMonth.toLong()
        val signed = PyDocs.at(doc, "signed") as JArr
        if (JInt(today) in signed) throw Acquisition.Rejected("Already checked in today", ERROR_SIGNED)
        doc["signed"] = PyDocs.sorted(signed + JInt(today))
        val count = (doc["signed"] as JArr).size.toLong()
        val frames = ArrayList<Frame>()
        val milestone = inputs.checkInMilestone(count)
        if (milestone != null) {
            val reward = Acquisition.emptyReward()
            frames += grantTriples(owned, milestone.arr("items").map { jarr(1, it.asArr[0], it.asArr[1]) }, reward)
            frames.add(S_SIGN_REWARD to BattleReport.encodeReward(reward))
        }
        frames.add(S_SIGN_MONTH to monthPayload(doc, now))
        return Plan(jobj("check_ins" to count, "milestone" to milestone?.get("row"), "sign_in_state_after" to doc,
            "evidence_class" to "capture_observed_csv_calculation"), frames)
    }

    /** C1093 time-based gift (`plan_time_gift`): S68 / S64 (the row's item) → S1156 Reward → S1154. */
    fun planTimeGift(owned: Owned, inputs: DailyInputs, document: JObj, now: Long): Plan {
        val doc = signRoll(document, now, inputs).first
        val row = PyDocs.at(doc, "row")
        if (PyDocs.compare(row, JInt(GIFT_ROWS)) > 0) throw Acquisition.Rejected("Every timed gift was claimed today", ERROR_GIFTS_DONE)
        if (PyDocs.compare(JInt(now), PyDocs.at(doc, "available_at")) < 0) throw Acquisition.Rejected("The timed gift is not ready", ERROR_GIFT_WAIT)
        val rowNumber = PyDocs.long(row)
        val gift = inputs.timeGift(rowNumber) ?: notSubscriptable()
        val reward = Acquisition.emptyReward()
        val frames = ArrayList(grantTriples(owned, listOf(jarr(gift["kind"], gift["item"], GIFT_COUNT)), reward))
        frames.add(S_SIGN_REWARD to BattleReport.encodeReward(reward))
        doc["row"] = JInt(rowNumber + 1)
        doc["available_at"] = JInt(if (rowNumber + 1 <= GIFT_ROWS) now + (inputs.timeGift(rowNumber + 1) ?: notSubscriptable()).long("wait") else 0L)
        frames.add(S_SIGN_GIFT to giftPayload(doc, now))
        return Plan(jobj("row" to row, "item" to gift["item"], "sign_in_state_after" to doc,
            "evidence_class" to "capture_observed_count_policy"), frames)
    }

    /**
     * C481 title salary (`plan_salary`): S512 Reward (Gold, Diamond of title.csv cols 106 / 107 for the title, role 22) →
     * S128 Gold + Diamond; `document` = the stored `salary_state` or null.
     */
    fun planSalary(owned: Owned, inputs: DailyInputs, document: JValue?, now: Long): Plan {
        if (salaryFlag(document, now) != 0L) throw Acquisition.Rejected("Today's salary was already claimed", ERROR_SALARY)
        val row = inputs.titleSalary(PyDocs.long(owned.role(ROLE_TITLE)["bits"]))
            ?: throw Acquisition.Rejected("No title.csv row for this title", ERROR_SALARY)
        val reward = Acquisition.emptyReward()
        reward["gold"] = PyDocs.at(row, "gold")
        reward["diamond"] = PyDocs.at(row, "diamond")
        owned.roleAdd(Acquisition.GOLD, row.long("gold"))
        owned.roleAdd(Acquisition.DIAMOND, row.long("diamond"))
        owned.state["title_reward_flag"] = JInt(1)
        val after = jobj("profile" to SALARY_PROFILE, "claim_day" to Shops.dayOf(now))
        val frames = listOf(S_SALARY to BattleReport.encodeReward(reward),
            Acquisition.S_ROLE to Acquisition.roleUpdatePayload(listOf(Acquisition.GOLD, Acquisition.DIAMOND).map { f ->
                Triple(f, owned.role(f).long("tag"), owned.roleBits(f))
            }))
        return Plan(jobj("title" to row["title"], "gold" to row["gold"], "diamond" to row["diamond"], "salary_state_after" to after,
            "evidence_class" to "capture_observed_csv_calculation"), frames)
    }

    /** The day's Daily Mission points (`mission_points`): each counter capped at its max, times its points. */
    fun missionPoints(document: JObj, inputs: DailyInputs): BigInteger {
        var total = BigInteger.ZERO
        for ((ident, count) in document.obj("counts")) {
            val row = inputs.dailyActivity(PyValues.parseLong(ident))
            if (row != null && row.isNotEmpty()) {
                val max = PyDocs.at(row, "max")
                val capped = if (PyDocs.compare(max, count) < 0) max else count
                total += PyDocs.int(capped) * PyDocs.int(PyDocs.at(row, "points"))
            }
        }
        return total
    }

    /** C2541 `u32 gift` (`decode_mission_gift`). */
    fun decodeMissionGift(payload: ByteArray): JObj {
        if (payload.size != 4) throw Acquisition.Rejected("C2541 is u32 gift id")
        return jobj("gift" to WireReader(payload).u32())
    }

    /**
     * C2541 Daily Mission gift (`plan_mission_gift`): grants → S2914 `u32 gift` (grant order policy — never captured);
     * `document` = the stored `daily_mission_state` or null.
     */
    fun planMissionGift(request: JObj, owned: Owned, inputs: DailyInputs, document: JValue?, now: Long): Plan {
        val doc = missionView(document, now)
        val giftId = PyDocs.at(request, "gift")
        val gift = inputs.dailyActivityGift(PyDocs.long(giftId))
        if (gift == null || giftId in doc.arr("claimed") || missionPoints(doc, inputs) < PyDocs.int(PyDocs.at(gift, "threshold"))) {
            throw Acquisition.Rejected("This Daily Mission gift is not claimable", ERROR_MISSION)
        }
        val reward = Acquisition.emptyReward()
        val frames = ArrayList(grantTriples(owned, listOf(jarr(gift["kind"], gift["item"], gift["count"])), reward))
        doc.arr("claimed").add(giftId)
        frames.add(S_MISSION_GIFT to WireWriter().number('I', giftId).bytes())
        return Plan(jobj("gift" to giftId, "daily_mission_state_after" to doc, "evidence_class" to "native_use_order_policy"), frames)
    }

    /**
     * C2371 daily → S68 / S64 … → S2724 Reward → S2720; C2373 level-up → S68 / S64 … → S2726 → S2720 (`plan_door_claim`,
     * kind "daily" / "level_up"; lv_yijiezhimen triples of the world Door level).
     */
    fun planDoorClaim(kind: String, owned: Owned, inputs: DailyInputs, document: JValue?, worldDoor: JObj, now: Long,
                      worldBirth: JValue?): Plan {
        val doc = doorView(document, now, worldBirth)
        val level = PyDocs.at(worldDoor, "level")
        val row = inputs.royalDoorLevel(PyDocs.long(level))
            ?: throw Acquisition.Rejected("No lv_yijiezhimen row for this Door level", ERROR_DOOR_DAILY)
        val triples: JArr
        val reply: Int
        if (kind == "daily") {
            if (Py.truthy(PyDocs.at(doc, "daily_claimed"))) throw Acquisition.Rejected("The Royal Door daily bonus was already claimed", ERROR_DOOR_DAILY)
            triples = row.arr("daily")
            reply = S_DOOR_DAILY
            doc["daily_claimed"] = JBool(true)
        } else {
            if (PyDocs.compare(PyDocs.at(doc, "level_up_claimed"), level) >= 0) {
                throw Acquisition.Rejected("The Royal Door level-up bonus was already claimed", ERROR_DOOR_LEVEL_UP)
            }
            triples = row.arr("level_up")
            reply = S_DOOR_LEVEL_UP
            doc["level_up_claimed"] = level
        }
        val reward = Acquisition.emptyReward()
        val frames = ArrayList(grantTriples(owned, triples.map { it.asArr }, reward))
        frames.add(reply to BattleReport.encodeReward(reward))
        frames.add(S_DOOR_INFO to doorPayload(doc, worldDoor))
        return Plan(jobj("kind" to kind, "door_level" to level, "royal_door_state_after" to doc,
            "evidence_class" to "capture_observed_csv_calculation"), frames)
    }

    /**
     * C2375 (`decode_donate`): `u8 1, u32 units` (Gold) or `u8 2, u32 n, n × (u32 item, u32 qty)` (items, each listed
     * once).
     */
    fun decodeDonate(payload: ByteArray): JObj {
        val form = if (payload.isEmpty()) -1 else payload[0].toInt() and 0xFF
        if (form != 1 && form != 2) throw Acquisition.Rejected("C2375 starts with u8 1 (Gold) or 2 (items)")
        if (form == 1) {
            if (payload.size != 5) throw Acquisition.Rejected("C2375 Gold is u8 1, u32 units", ERROR_DONATE_GOLD)
            return jobj("form" to "gold", "units" to WireReader(payload.copyOfRange(1, 5)).u32())
        }
        if (payload.size < 5) throw Acquisition.Rejected("C2375 items is u8 2, u32 n, n x (u32 item, u32 qty)", ERROR_DONATE_ITEMS)
        val n = WireReader(payload.copyOfRange(1, 5)).u32()
        if (n < 1 || payload.size.toLong() != 5 + 8 * n) throw Acquisition.Rejected("C2375 item list length mismatch", ERROR_DONATE_ITEMS)
        val reader = WireReader(payload.copyOfRange(5, payload.size))
        val items = JArr()
        repeat(n.toInt()) { items.add(jarr(reader.u32(), reader.u32())) }
        if (items.map { it.asArr[0] }.toSet().size.toLong() != n) throw Acquisition.Rejected("C2375 lists an item twice", ERROR_DONATE_ITEMS)
        return jobj("form" to "items", "items" to items)
    }

    /**
     * C2375 Royal Door donation (`plan_door_donate`, built from the client's code and tables): items within their
     * donatable flag and daily cap (Door EXP col 210, Essence col 211 each), or Gold in units of 10,000 within property
     * 908 units a day (the Donate task's own Essence / Door EXP per unit, POLICY); only while task 2 is among today's
     * tasks. Reply: the cost (S68 / S66 per stack, or S128 Gold), the Essence stack, S2728 banner, S2722, S2720 with the
     * raised Door. The world Door EXP is written by the session after the commit (`door_exp`).
     */
    fun planDoorDonate(request: JObj, owned: Owned, inputs: DailyInputs, document: JValue?, worldDoor: JObj, now: Long,
                       worldBirth: JValue?): Plan {
        val goldForm = PyDocs.at(request, "form") == JStr("gold")
        val fail = if (goldForm) ERROR_DONATE_GOLD else ERROR_DONATE_ITEMS
        val doc = doorView(document, now, worldBirth)
        if (JInt(TASK_DONATE) !in (PyDocs.at(doc, "tasks") as JArr)) {
            throw Acquisition.Rejected("The Donate task is not one of today's Royal Door tasks", fail)
        }
        val task = inputs.royalDoorTask(TASK_DONATE) ?: throw Acquisition.Rejected("No quest_yijiezhimen row for the Donate task", fail)
        val frames = ArrayList<Frame>()
        var essence = 0L
        var doorExp = 0L
        if (goldForm) {
            val units = PyDocs.long(PyDocs.at(request, "units"))
            if (units < 1) throw Acquisition.Rejected("Donate at least one unit of 10,000 Gold", ERROR_DONATE_GOLD)
            val donatedBefore = PyDocs.int(PyDocs.at(doc, "gold_donated"))
            if (donatedBefore + BigInteger.valueOf(units) > BigInteger.valueOf(inputs.donateGoldCap())) {
                throw Acquisition.Rejected("Today's Gold donation limit is reached", ERROR_DONATE_GOLD_LIMIT)
            }
            val cost = BigInteger.valueOf(units) * BigInteger.valueOf(GOLD_UNIT)
            if (owned.roleBits(Acquisition.GOLD) < cost) throw Acquisition.Rejected("Not enough Gold", ERROR_DONATE_GOLD)
            owned.roleAdd(Acquisition.GOLD, cost.negate())
            frames.add(Acquisition.S_ROLE to Acquisition.roleUpdatePayload(listOf(
                Triple(Acquisition.GOLD, owned.role(Acquisition.GOLD).long("tag"), owned.roleBits(Acquisition.GOLD)))))
            essence = units * task.long("essence")
            doorExp = units * task.long("door_exp")
            doc["gold_donated"] = JInt(donatedBefore + BigInteger.valueOf(units))
        } else {
            val donated = PyDocs.at(doc, "donated") as JObj
            for (pair in PyDocs.at(request, "items") as JArr) {
                val item = PyDocs.long(pair.asArr[0])
                val qty = PyDocs.long(pair.asArr[1])
                val row = inputs.donateItem(item) ?: throw Acquisition.Rejected("The item is not on the donation list", ERROR_DONATE_NOT_LISTED)
                val done = PyDocs.long(donated[item.toString()] ?: JInt(0))
                if (qty < 1 || done + qty > row.long("daily_cap")) throw Acquisition.Rejected("Today's donation limit of this item is reached", ERROR_DONATE_LIMIT)
                if (owned.countOf(item) < qty) throw Acquisition.Rejected("Not enough items", ERROR_DONATE_SHORT)
                frames += owned.consumeTemplate(item, qty)
                essence += qty * row.long("essence")
                doorExp += qty * row.long("door_exp")
                donated[item.toString()] = JInt(done + qty)
            }
        }
        if (essence != 0L) frames.add(owned.grantItem(task.long("essence_item"), essence))
        val door = doorAfter(worldDoor, doorExp, inputs)
        frames.add(S_DOOR_BANNER to WireWriter().number('I', essence).number('I', doorExp).bytes())
        frames.add(S_DOOR_DONATED to donatedPayload(doc))
        frames.add(S_DOOR_INFO to doorPayload(doc, door))
        return Plan(jobj("form" to request["form"], "essence" to essence, "door_exp" to doorExp, "door_level_after" to door["level"],
            "royal_door_state_after" to doc, "evidence_class" to "native_use_csv_policy_order"), frames)
    }
}
