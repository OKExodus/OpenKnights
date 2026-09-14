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
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.store.StateStore
import java.math.BigInteger
import java.time.Duration
import java.time.OffsetDateTime

/**
 * The login parts of `event_hall.py`: the S1760 login set (types 1 level gift, 2 Palace, 4 Magic Pie, 5 Blacksmith,
 * 6 / 8 closed, 7 Great Offer on the 1st – 3rd of the month, 9 exchanges with weekly / daily limit periods of the
 * device clock), followed by the Temp VIP S1824.
 */
object EventHall {
    const val S_EVENT_UPDATE = 1760
    const val S_EVENT_REWARD = 1762
    const val T_LEVEL_GIFT = 1
    const val T_PALACE = 2
    const val T_MAGIC_PIE = 4
    const val T_BLACKSMITH = 5
    const val T_HERO_POOL = 6
    const val T_GREAT_OFFER = 7
    const val T_REBATE = 8
    const val T_EXCHANGE = 9
    const val C_PALACE_VISIT = 1635
    const val C_PALACE_CLAIM = 1637
    const val C_MAGIC_PIE = 1641
    const val C_COMBINE = 3077
    const val C_EXCHANGE = 1665
    const val C_GREAT_OFFER = 1649
    const val PALACE_PROFILE = "palace_state_v1"
    const val PALACE_CYCLE_VISITS = 3L
    const val EXCHANGE_PROFILE = "exchange_state_v2"
    const val PERMANENT_SECONDS = 5L * 365 * 86400
    const val GREAT_OFFER_PROFILE = "sgxj_state_v1"
    const val GREAT_OFFER_DAYS = 3L

    // --- action-planner constants (event_hall.py) ---
    const val PAIR_DIAMOND = 20003L
    const val PAIR_GOLD = 20001L
    const val ERROR_EVENT = 30000                       // "The Daily Event Reward is invalid" (generic daily-event refusal)
    const val PIE_PROFILE = "magic_pie_state_v1"
    const val PIE_STAMINA = 40L
    const val PIE_ENERGY = 300L
    /** SpecialEventMFDG TimeIntervalChecker windows (served hours). */
    val PIE_PIECES: Map<Long, Pair<Long, Long>> = mapOf(0x65L to (12L to 13L), 0x66L to (18L to 19L))
    // Combine (ZBHC)
    const val S_COMBINE = 3300
    const val ERROR_MATERIALS = 1011                    // "You don't have all the Materials yet"
    const val S_BAG_REMOVE = 102
    const val S_SET_EQUIP = 104
    const val S_EQUIP_REMOVE = 98
    // Exchanges (DHHD)
    const val ERROR_EXCHANGE_EVENT = 35000              // "The Event is invalid"
    const val ERROR_EXCHANGE_LIMIT = 35001              // "Exceed the exchange limit"
    const val ITEM_KIND = 1L
    const val HERO_KIND = 2L
    const val EQUIP_KIND = 3L
    const val JEWEL_KIND = 9L
    val MATERIAL_KINDS = setOf(ITEM_KIND, HERO_KIND, EQUIP_KIND, JEWEL_KIND)
    val RESULT_KINDS = setOf(ITEM_KIND, EQUIP_KIND, JEWEL_KIND)
    const val INSTANCE_POLICY = "least_invested_first_v1"
    const val S_HERO_BENCH_REMOVE = 40
    const val S_HERO_REMOVE = 34
    const val S_JEWEL_LIST_REMOVE = 3074
    const val ERROR_NO_JEWEL_LIST = 102                 // the opcode-3072 list is neither stored nor served
    // Great Offer (SGXJ)
    const val ERROR_OFFER = 33000                       // "Offer is not currently available"
    const val GREAT_OFFER_DRAW = "table_weights_seeded_v1"

    private fun copy(value: JValue): JObj = value.deepCopy() as JObj

    fun s1760(eventType: Int, body: ByteArray = ByteArray(0)): Frame = S_EVENT_UPDATE to (byteArrayOf(eventType.toByte()) + body)

    // --- Palace -------------------------------------------------------------------------------------------------------

    /** Document from a seed S1760 type-2 frame (`02 visits bonus_day visited bonus_state`), else the fresh cycle. */
    fun seedPalace(payload: ByteArray?, provenance: JValue?): JObj {
        var p = payload
        var prov = provenance
        if (p == null || p.size != 5 || p[0].toInt() != T_PALACE) {
            p = byteArrayOf(T_PALACE.toByte(), PALACE_CYCLE_VISITS.toByte(), 3, 0, 0)
            prov = jobj("source" to "policy_default_cycle", "note" to "fresh cycle as captured on Server 10 / 11")
        }
        return jobj("profile" to PALACE_PROFILE, "visits_needed" to (p[1].toInt() and 0xFF), "bonus_day" to (p[2].toInt() and 0xFF),
            "visit_day" to null, "bonus_state" to (p[4].toInt() and 0xFF), "cycle_day" to null, "seed" to (prov ?: JNull))
    }

    /** Daily rollover: after the cycle's bonus was claimed on an earlier day a new cycle starts with the next bonus day. */
    fun palaceRoll(document: JValue, today: String, inputs: DailyInputs): JObj {
        val doc = copy(document)
        val cycleDay = PyDocs.get(doc, "cycle_day")
        if (PyDocs.at(doc, "bonus_state") == JInt(2) && cycleDay != null && cycleDay != JStr(today)) {
            val days = inputs.palaceBonusDays().sorted().ifEmpty { listOf(3L) }
            val later = days.filter { PyDocs.compare(JInt(it), PyDocs.at(doc, "bonus_day")) > 0 }
            doc["bonus_day"] = JInt(if (later.isNotEmpty()) later[0] else days[0])
            doc["visits_needed"] = JInt(PALACE_CYCLE_VISITS)
            doc["bonus_state"] = JInt(0)
            doc["cycle_day"] = JNull
        }
        return doc
    }

    fun palaceBody(document: JObj, today: String): ByteArray = PyDocs.bytes(listOf(PyDocs.long(PyDocs.at(document, "visits_needed")),
        PyDocs.long(PyDocs.at(document, "bonus_day")), if (PyDocs.get(document, "visit_day") == JStr(today)) 1L else 0L,
        PyDocs.long(PyDocs.at(document, "bonus_state"))))

    // --- Magic Pie ----------------------------------------------------------------------------------------------------

    /**
     * `_pie_on_device_clock()`: true when a device / service clock is installed (release, or a replay that pinned the
     * day). Without one (bare tests) the served clock follows raw UTC, as before the device-clock fix (EVENTS_CONTRACT §4.1).
     */
    private fun pieOnDeviceClock(): Boolean = DeviceClock.active != null

    /** `_pie_day(served_time)`: the device-local calendar day (a day_of string) when clocked, else the served UTC day. */
    private fun pieDay(servedTime: Long): JValue =
        if (pieOnDeviceClock()) JStr(Shops.dayOf(servedTime)) else JInt(Math.floorDiv(servedTime, 86400L))

    /** `_pie_hour(served_time)`: the device-local hour when clocked, else the raw served UTC hour. */
    private fun pieHour(servedTime: Long): Long =
        if (pieOnDeviceClock()) Shops.localDatetime(servedTime).hour.toLong() else Math.floorDiv(Math.floorMod(servedTime, 86400L), 3600L)

    /** Claimed piece ids of the served day, keyed on the device-local calendar day (EVENTS_CONTRACT §4.1). */
    fun pieView(document: JValue?, servedTime: Long): List<JValue> {
        val doc = if (Py.truthy(document)) document as JObj else JObj()
        if (PyDocs.get(doc, "served_day") != pieDay(servedTime)) return emptyList()
        return ((doc["claimed"] ?: JArr()) as JArr).toList()
    }

    fun pieBody(document: JValue?, servedTime: Long): ByteArray {
        val claimed = pieView(document, servedTime)
        val w = WireWriter().number('I', servedTime).number('B', claimed.size.toLong())
        for (c in claimed) w.number('I', c)
        return w.bytes()
    }

    // --- exchanges ------------------------------------------------------------------------------------------------------

    private fun cstr(text: JValue?): ByteArray {
        val raw = (text as JStr).value.toByteArray(Charsets.UTF_8)
        if (raw.contains(0.toByte())) throw PyValues.ValueError("Exchange texts must not contain NUL")
        return raw + byteArrayOf(0)
    }

    private val PERIOD = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH")

    /** (period start label, next reset epoch) of an exchange's limits: weekly (`reset: {weekday, hour}`) or daily. */
    fun exchangePeriod(event: JObj, now: Long): Pair<String, Long> {
        val local = Shops.localDatetime(now)
        val start: OffsetDateTime
        val following: OffsetDateTime
        if (PyDocs.get(event, "limit_scope") == JStr("weekly")) {
            val reset = (event["reset"] ?: JObj()) as JObj
            val weekday = PyDocs.long(reset["weekday"] ?: JInt(0))
            val hour = PyDocs.long(reset["hour"] ?: JInt(0))
            val back = Math.floorMod((local.dayOfWeek.value - 1) - weekday, 7L)
            if (hour < 0 || hour > 23) throw PyValues.ValueError("hour must be in 0..23")
            var s = local.minusDays(back).withHour(hour.toInt()).withMinute(0).withSecond(0).withNano(0)
            if (s.isAfter(local)) s = s.minusDays(7)
            start = s
            following = s.plusDays(7)
        } else {
            start = local.withHour(0).withMinute(0).withSecond(0).withNano(0)
            following = start.plusDays(1)
        }
        return PERIOD.format(start) to now + Duration.between(local, following).seconds
    }

    fun exchangeUsed(document: JValue?, eventId: JValue, index: Int): BigInteger {
        val doc = if (Py.truthy(document)) document as JObj else JObj()
        val used = (doc["used"] ?: JObj()) as JObj
        return PyDocs.int(used["${PyDocs.str(eventId)}:$index"] ?: JInt(0))
    }

    /** Restart the used counts of every exchange whose limit period changed (a v1 document restarts everything). */
    fun exchangeRoll(document: JValue?, exchanges: List<JValue>, now: Long): JObj {
        val base = if (!Py.truthy(document) || PyDocs.get(document as JObj, "profile") != JStr(EXCHANGE_PROFILE))
            jobj("profile" to EXCHANGE_PROFILE, "periods" to JObj(), "used" to JObj()) else document
        val doc = copy(base)
        for (e in exchanges) {
            val event = e.asObj
            val (period, _) = exchangePeriod(event, now)
            val key = PyDocs.str(PyDocs.at(event, "id"))
            if (PyDocs.get(doc.obj("periods"), key) != JStr(period)) {
                val kept = JObj()
                for ((k, v) in doc.obj("used")) if (!k.startsWith("$key:")) kept[k] = v
                doc["used"] = kept
                doc.obj("periods")[key] = JStr(period)
            }
        }
        return doc
    }

    /** S1760 type 9 body: `u8 n, n × (u32 id, u32 cd, name, desc, u8 f, f × (u8 m, m × (u8 kind, u32 id, u32 count),
     * u8 kind, u32 id, u32 count, u32 remaining))`. */
    fun exchangeBody(exchanges: List<JValue>, document: JValue?, servedTime: Long, now: Long? = null): ByteArray {
        val events = exchanges.map { it.asObj }.filter { Py.truthy(it["materials_kind_ok"] ?: JBool(true)) }
        val w = WireWriter().raw(PyDocs.bytes(listOf(events.size.toLong())))
        for (event in events) {
            val scope = PyDocs.get(event, "limit_scope")
            var cd: BigInteger = if ((scope == JStr("weekly") || scope == JStr("daily")) && now != null && !event.containsKey("end")) {
                BigInteger.valueOf(exchangePeriod(event, now).second - now)
            } else {
                PyDocs.int(event["end"] ?: JInt(servedTime + PERMANENT_SECONDS)) - BigInteger.valueOf(servedTime)
            }
            cd = cd.max(BigInteger.ZERO).min(BigInteger.valueOf(0x7fffffff))
            w.number('I', PyDocs.at(event, "id")).number('I', cd).raw(cstr(event["name"] ?: throw PyDocs.KeyError("'name'")))
                .raw(cstr(event["desc"] ?: JStr("")))
            val formulas = event.arr("formulas")
            w.raw(PyDocs.bytes(listOf(formulas.size.toLong())))
            formulas.forEachIndexed { index, f ->
                val formula = f.asObj
                val materials = formula.arr("materials")
                w.raw(PyDocs.bytes(listOf(materials.size.toLong())))
                for (m in materials) w.values("BII", m.asArr.let { if (it.size != 3) throw PyValues.ValueError("not enough values to unpack") else it })
                val result = formula.arr("result")
                if (result.size != 3) throw PyValues.ValueError("not enough values to unpack")
                val remaining = (PyDocs.int(PyDocs.at(formula, "limit")) - exchangeUsed(document, PyDocs.at(event, "id"), index)).max(BigInteger.ZERO)
                w.number('B', result[0]).number('I', result[1]).number('I', result[2]).number('I', remaining)
            }
        }
        return w.bytes()
    }

    // --- Great Offer --------------------------------------------------------------------------------------------------

    /** shengouxianji.csv rows in attempt order: cost, the display pair, the (prize, weight) pairs with both > 0. */
    fun greatOfferRows(inputs: DailyInputs?): List<JObj> {
        if (inputs == null) return emptyList()
        val rows = ArrayList<JObj>()
        for (fields in inputs.tableRows("shengouxianji")) {
            val value = LinkedHashMap<Long, Long>()
            for ((k, v) in fields.fields()) {
                if (!PyValues.isDigit(k)) continue
                val text = PyValues.strip(v)
                value[PyValues.parseLong(k)] = if (text.isEmpty()) 0L else PyValues.parseLong(text)
            }
            if ((value[101L] ?: 0L) == 0L) continue
            val prizes = listOf(105L, 107L, 109L).map { p -> (value[p] ?: 0L) to (value[p + 1] ?: 0L) }
            rows.add(jobj("id" to value.getValue(101L), "cost" to (value[102L] ?: 0L), "floor" to (value[103L] ?: 0L),
                "top" to (value[104L] ?: 0L), "prizes" to prizes.filter { it.first > 0 && it.second > 0 }.map { listOf(it.first, it.second) },
                "col111" to (value[111L] ?: 0L)))
        }
        return rows.filter { it.long("cost") > 0 && it.arr("prizes").isNotEmpty() }.sortedBy { it.long("id") }
    }

    /** (window label `YYYY-MM`, end epoch) while the local date is day 1 – 3 of a month, else null. */
    fun greatOfferWindow(now: Long): Pair<String, Long>? {
        val local = Shops.localDatetime(now)
        if (local.dayOfMonth < 1 || local.dayOfMonth > GREAT_OFFER_DAYS) return null
        val start = local.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
        val end = start.plusDays(GREAT_OFFER_DAYS)
        return "%04d-%02d".format(start.year, start.monthValue) to now + Duration.between(local, end).seconds
    }

    fun greatOfferRoll(document: JValue?, window: String): JObj {
        val doc = document as? JObj
        if (!Py.truthy(document) || PyDocs.get(doc, "profile") != JStr(GREAT_OFFER_PROFILE) || PyDocs.get(doc, "window") != JStr(window)) {
            return jobj("profile" to GREAT_OFFER_PROFILE, "window" to window, "spins" to 0, "draws" to JArr())
        }
        return copy(doc!!)
    }

    /** Null when closed, else {window, end, spins, remaining, row, rows}. */
    fun greatOfferView(document: JValue?, inputs: DailyInputs?, now: Long): JObj? {
        val rows = greatOfferRows(inputs)
        val (window, end) = greatOfferWindow(now) ?: return null
        if (rows.isEmpty()) return null
        // Clamp to [0, len(rows)] so a negative stored `spins` can't make remaining exceed the rows or index from the end.
        val spins = PyDocs.int(PyDocs.at(greatOfferRoll(document, window), "spins")).min(BigInteger.valueOf(rows.size.toLong())).max(BigInteger.ZERO)
        return jobj("window" to window, "end" to end, "spins" to spins, "remaining" to (BigInteger.valueOf(rows.size.toLong()) - spins),
            "row" to PyDocs.index(rows, spins.min(BigInteger.valueOf(rows.size - 1L)).toInt()), "rows" to rows)
    }

    /** S1760 type 7 body: `00` closed; open `u8 1, u32 cost, u32 top, u8 attempts left, u32 cd`. */
    fun greatOfferBody(document: JValue?, inputs: DailyInputs?, now: Long): ByteArray {
        val view = greatOfferView(document, inputs, now) ?: return byteArrayOf(0)
        val row = view.obj("row")
        val cd = maxOf(0L, minOf(0x7fffffffL, view.long("end") - now))
        return WireWriter().number('B', 1).number('I', PyDocs.at(row, "cost")).number('I', PyDocs.at(row, "top"))
            .number('B', PyDocs.at(view, "remaining")).number('I', cd).bytes()
    }

    // --- login set ------------------------------------------------------------------------------------------------------

    /**
     * The login S1760 order: types 1, 2, 4, 5, 6, 7, 8, 9 (type 10 comes from the claims after them), then the Temp VIP
     * S1824. The type-5 body reads a document no code writes, so it is always eight zero bytes here (the Blacksmith's
     * real cooldowns follow with the training pushes).
     */
    fun loginFrames(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, inputs: DailyInputs, now: Long, servedTime: Long): List<Frame> {
        val today = Shops.dayOf(now)
        val seedType = LinkedHashMap<Int, ByteArray>()
        for (payload in seeds?.all(S_EVENT_UPDATE) ?: emptyList()) {
            if (payload.isNotEmpty()) seedType.putIfAbsent(payload[0].toInt() and 0xFF, payload)
        }
        val levelGift = seedType[T_LEVEL_GIFT] ?: byteArrayOf(T_LEVEL_GIFT.toByte(), 0, 0, 0, 0)
        val stored = PyDocs.get(current, "palace_state")
        val palace = palaceRoll(if (Py.truthy(stored)) stored!! else seedPalace(seedType[T_PALACE], null), today, inputs)
        // The reference reads `current["blacksmith_body"]`, a key no code writes: always eight zero bytes.
        return listOf(S_EVENT_UPDATE to levelGift,
            s1760(T_PALACE, palaceBody(palace, today)),
            s1760(T_MAGIC_PIE, pieBody(PyDocs.get(current, "magic_pie_state"), servedTime)),
            s1760(T_BLACKSMITH, ByteArray(8)),
            s1760(T_HERO_POOL, byteArrayOf(0)),
            s1760(T_GREAT_OFFER, greatOfferBody(PyDocs.get(current, "sgxj_state"), inputs, now)),
            s1760(T_REBATE, byteArrayOf(0)),
            s1760(T_EXCHANGE, exchangeBody(exchanges(), exchangeRoll(PyDocs.get(current, "exchange_state"), exchanges(), now), servedTime, now)),
            SweepFeatures.tmpVipFrame(current, seeds, now))
    }

    fun exchanges(): List<JValue> = (Events.ACTIVE["exchanges"] as? JArr) ?: emptyList()

    const val S_JEWEL_LIST_ADD = 3076
    /** Level, EXP, grade, flag, extra of a granted jewel (U6 policy, as card_reset). */
    val FRESH_JEWEL = listOf(1L, 0L, 1L, 0L, 0L)

    /** `_chunks(values, size)`: consecutive slices of at most `size`. */
    fun <T> chunks(values: List<T>, size: Int = 255): List<List<T>> = values.chunked(size)

    /**
     * `_grant_jewels(owned, template, count, jewels)`: new jewels into the unequipped list `jewels` (entries appended):
     * record `(uid, template, 1, 0, 1, 0, 0)`, uid = max(equipped ∪ listed before this transaction ∪ added) + 1;
     * S3076 `u8 n, n x 22-byte record` per 255.
     */
    fun grantJewels(owned: Owned, template: Long, count: Long, jewels: JArr): List<Frame> {
        if (!owned.inputs.exists("jewelry", template)) throw Acquisition.Rejected("Unknown jewelry template $template")
        val known = HashSet<Long>(ItemFortify.jewelryView(owned.state.arr("formation")).keys)
        for (e in owned.current.jewelEntriesView!!) known.add(e.asObj.arr("record")[0].long)
        for (e in jewels) known.add(e.asObj.arr("record")[0].long)
        val added = ArrayList<List<Long>>()
        for (n in 0 until count) {
            val uid = (known.maxOrNull() ?: 0L) + 1
            known.add(uid)
            val record = listOf(uid, template) + FRESH_JEWEL
            jewels.add(jobj("record" to record, "tail" to null))
            added.add(record)
        }
        owned.log.add(jobj("op" to "new_jewels", "template" to template, "uids" to added.map { it[0] }))
        return chunks(added).map { part ->
            val w = WireWriter().u8(part.size)
            for (r in part) w.u32(r[0]).u32(r[1]).u32(r[2]).u32(r[3]).u8(r[4].toInt()).u8(r[5].toInt()).u32(r[6])
            S_JEWEL_LIST_ADD to w.bytes()
        }
    }

    // --- Event Hall action planners (group 8, owned by the Event Hall slice) -------------------------------------------

    /** `_counted(uids)`: `u8 n, n × u32 uid`. */
    private fun counted(uids: List<Long>): ByteArray {
        val w = WireWriter().number('B', uids.size.toLong())
        for (u in uids) w.number('I', u)
        return w.bytes()
    }

    // --- Palace (CBNW) ---

    /** `plan_palace_visit(owned, inputs, document, now)`: C1635 → S1762 `02`+Reward(Gold), S128 Gold, S1760 `02`. */
    fun planPalaceVisit(owned: Owned, inputs: DailyInputs, document: JObj, now: Long): Plan {
        val today = Shops.dayOf(now)
        val doc = palaceRoll(document, today, inputs)
        if (PyDocs.get(doc, "visit_day") == JStr(today)) throw Acquisition.Rejected("The Palace was already visited today", ERROR_EVENT)
        val row = inputs.palaceRow(PyDocs.long(PyDocs.at(doc, "bonus_day"))) ?: JObj()
        val gold = if (Py.truthy(row["gold"])) PyDocs.long(row["gold"]) else 0L
        if (gold <= 0L) throw Acquisition.Rejected("No canbainvwang row for this cycle", ERROR_EVENT)
        val reward = Acquisition.emptyReward()
        reward["gold"] = JInt(gold)
        owned.roleAdd(Acquisition.GOLD, gold)
        doc["visit_day"] = JStr(today)
        val visits = PyDocs.long(PyDocs.at(doc, "visits_needed"))
        if (visits > 0L) {
            val remaining = visits - 1
            doc["visits_needed"] = JInt(remaining)
            if (remaining == 0L && PyDocs.long(PyDocs.at(doc, "bonus_state")) == 0L) doc["bonus_state"] = JInt(1)
        }
        val goldRole = owned.role(Acquisition.GOLD)
        val frames = listOf(
            S_EVENT_REWARD to (byteArrayOf(T_PALACE.toByte()) + BattleReport.encodeReward(reward)),
            Acquisition.S_ROLE to Acquisition.roleUpdatePayload(listOf(Triple(Acquisition.GOLD, goldRole.long("tag"), owned.roleBits(Acquisition.GOLD)))),
            s1760(T_PALACE, palaceBody(doc, today)))
        return Plan(jobj("gold" to gold, "palace_state_after" to doc, "evidence_class" to "capture_observed_csv_calculation"), frames)
    }

    /** `plan_palace_claim(owned, inputs, document, now)`: C1637 → Diamond / bonus items, S1762 `02`+Reward, S1760 `02`. */
    fun planPalaceClaim(owned: Owned, inputs: DailyInputs, document: JObj, now: Long): Plan {
        val today = Shops.dayOf(now)
        val doc = palaceRoll(document, today, inputs)
        if (PyDocs.long(PyDocs.at(doc, "bonus_state")) != 1L) throw Acquisition.Rejected("The Palace bonus is not claimable", ERROR_EVENT)
        val row = inputs.palaceRow(PyDocs.long(PyDocs.at(doc, "bonus_day")))
        if (row == null || !Py.truthy(row["bonus"])) throw Acquisition.Rejected("No canbainvwang bonus for this cycle", ERROR_EVENT)
        val reward = Acquisition.emptyReward()
        val roles = ArrayList<Long>()
        val items = ArrayList<Frame>()
        for (entry in row.arr("bonus")) {
            val b = entry.asArr
            val item = b[1].long
            val count = b[2].long
            if (item == PAIR_DIAMOND) {                     // Diamond pair (CLAIMS_CONTRACT §2 currency remap)
                owned.roleAdd(Acquisition.DIAMOND, count)
                reward["diamond"] = JInt(reward.long("diamond") + count)
                roles.add(Acquisition.DIAMOND)
            } else {
                items.add(owned.grantItem(item, count))
                reward.arr("items").add(jarr(item, count))
            }
        }
        val frames = ArrayList<Frame>()
        if (roles.isNotEmpty()) {
            frames.add(Acquisition.S_ROLE to Acquisition.roleUpdatePayload(
                roles.toSortedSet().map { Triple(it, owned.role(it).long("tag"), owned.roleBits(it)) }))
        }
        frames.addAll(items)
        doc["bonus_state"] = JInt(2)
        doc["cycle_day"] = JStr(today)
        frames.add(S_EVENT_REWARD to (byteArrayOf(T_PALACE.toByte()) + BattleReport.encodeReward(reward)))
        frames.add(s1760(T_PALACE, palaceBody(doc, today)))
        return Plan(jobj("bonus_day" to PyDocs.at(doc, "bonus_day"), "reward" to reward, "palace_state_after" to doc,
            "evidence_class" to "capture_observed_csv_calculation"), frames)
    }

    // --- Magic Pie (MFDG) ---

    /** `decode_pie_request(payload)`: C1641 `u32 piece id`. */
    fun decodePieRequest(payload: ByteArray): JObj {
        if (payload.size != 4) throw Acquisition.Rejected("C1641 is u32 piece id")
        return jobj("piece" to WireReader(payload).u32())
    }

    /** `plan_magic_pie(request, owned, document, served_time)`: C1641 → S128 AP, S128 Energy, S1762 `04`, S1760 `04`. */
    fun planMagicPie(request: JObj, owned: Owned, document: JValue?, servedTime: Long): Plan {
        val piece = request.long("piece")
        val window = PIE_PIECES[piece] ?: throw Acquisition.Rejected("Unknown Magic Pie piece", ERROR_EVENT)
        val (start, end) = window
        val hour = pieHour(servedTime)                  // device-local hour (EVENTS_CONTRACT §4.1)
        val claimed = pieView(document, servedTime)
        if (!(start <= hour && hour < end) || claimed.any { it == JInt(piece) })
            throw Acquisition.Rejected("This Magic Pie piece is not claimable now", ERROR_EVENT)
        val doc = jobj("profile" to PIE_PROFILE, "served_day" to pieDay(servedTime),
            "claimed" to JArr((claimed + JInt(piece)).toMutableList()))
        val frames = ArrayList<Frame>()
        frames.add(owned.roleAdd(Acquisition.STAMINA, PIE_STAMINA))
        frames.add(owned.roleAdd(Acquisition.ENERGY, PIE_ENERGY))
        val reward = Acquisition.emptyReward()
        reward["stamina"] = JInt(PIE_STAMINA)
        reward["energy"] = JInt(PIE_ENERGY)
        frames.add(S_EVENT_REWARD to (byteArrayOf(T_MAGIC_PIE.toByte()) + BattleReport.encodeReward(reward)))
        frames.add(s1760(T_MAGIC_PIE, pieBody(doc, servedTime)))
        return Plan(jobj("piece" to piece, "magic_pie_state_after" to doc, "served_time" to servedTime,
            "evidence_class" to "capture_observed"), frames)
    }

    // --- Combine (ZBHC) ---

    /** `decode_combine(payload)`: C3077 `u32 recipe, u8 4, 4 × u32 equipment uid`. */
    fun decodeCombine(payload: ByteArray): JObj {
        if (payload.size != 21) throw Acquisition.Rejected("C3077 is u32 recipe, u8 count, 4 x u32 uid")
        val r = WireReader(payload)
        val recipe = r.u32()
        val count = r.u8()
        val uids = listOf(r.u32(), r.u32(), r.u32(), r.u32())
        if (count != 4) throw Acquisition.Rejected("C3077 carries four materials")
        return jobj("recipe" to recipe, "uids" to uids)
    }

    /** `plan_combine(request, owned, inputs)`: C3077 → the recipe's result equipment (equip_hecheng.csv), S3300. */
    fun planCombine(request: JObj, owned: Owned, inputs: DailyInputs): Plan {
        val recipe = inputs.combineRecipe(request.long("recipe")) ?: throw Acquisition.Rejected("Unknown Combine recipe", ERROR_MATERIALS)
        val uids = request.arr("uids").map { it.long }
        if (uids.toSet().size != 4) throw Acquisition.Rejected("Four distinct materials are required", ERROR_MATERIALS)
        val state = owned.state
        val records = LinkedHashMap<Long, JObj>()
        for (r in state.arr("equipment")) { val o = r.asObj; records[o.arr("wire_values")[0].long] = o }
        val materials = recipe.arr("materials")
        for (i in uids.indices) {
            val mat = materials[i].asArr
            val template = mat[0].long; val minLevel = mat[1].big; val minGrade = mat[2].big
            val record = records[uids[i]]
            val wire = record?.arr("wire_values")
            if (record == null || wire!![1].long != template || wire[2].big < minLevel || wire[4].big < minGrade)
                throw Acquisition.Rejected("A material does not fit the recipe", ERROR_MATERIALS)
        }
        val frames = ArrayList<Frame>(owned.consumeTemplate(recipe.long("cost_item"), recipe.long("cost")))
        val bag = state.arr("bag_equipment_uids").map { it.long }
        for (uid in uids) if (uid in bag) frames.add(S_BAG_REMOVE to counted(listOf(uid)))
        for (slot in state.arr("formation")) {
            val s = slot.asObj
            val kept = JArr()
            for (assignment in s.arr("assignments")) {
                val a = assignment.asArr
                val position = a[0].long; val uid = a[1].long
                if (uid in uids) frames.add(S_SET_EQUIP to WireWriter().u8(s.long("slot_id").toInt()).u8(position.toInt()).u32(0).bytes())
                else kept.add(jarr(position, uid))
            }
            s["assignments"] = kept
        }
        frames.add(S_BAG_REMOVE to counted(uids))
        frames.add(S_EQUIP_REMOVE to counted(uids))
        val uidSet = uids.toSet()
        state["equipment"] = JArr(state.arr("equipment").filter { it.asObj.arr("wire_values")[0].long !in uidSet }.toMutableList())
        state["bag_equipment_uids"] = JArr(bag.filter { it !in uidSet }.mapTo(ArrayList()) { JInt(it) })
        owned.log.add(jobj("op" to "combine_consume", "uids" to uids, "recipe" to recipe["recipe"]))
        val (newUid, groups) = owned.grantEquipment(recipe.long("result"))
        val book = (groups["book"] ?: emptyList()).filter { it.first == Acquisition.S_COLLECTION || it.first == Acquisition.S_ACHIEVEMENT }
        val reward = Acquisition.emptyReward()
        reward["equips"] = jarr(jarr(recipe.long("result")))
        frames.addAll(groups["add"] ?: emptyList())
        frames.addAll(book)
        frames.add(S_COMBINE to BattleReport.encodeReward(reward))
        return Plan(jobj("recipe" to recipe["recipe"], "consumed_uids" to uids, "new_uid" to newUid, "result" to recipe.long("result"),
            "evidence_class" to "capture_observed_csv_recipe"), frames)
    }

    // --- Exchanges (DHHD) ---

    /** `decode_exchange(payload)`: C1665 `u32 event, u8 formula index, u32 amount`. */
    fun decodeExchange(payload: ByteArray): JObj {
        if (payload.size != 9) throw Acquisition.Rejected("C1665 is u32 event, u8 formula, u32 amount")
        val r = WireReader(payload)
        return jobj("event" to r.u32(), "formula" to r.u8(), "amount" to r.u32())
    }

    /** `decode_exchange_body(body)`: inverse of `exchangeBody` for a captured S1760 type-9 body (after the type byte). */
    fun decodeExchangeBody(body: ByteArray): JArr {
        var offset = 0
        fun u8(): Long { val v = body[offset].toLong() and 0xFF; offset += 1; return v }
        fun u32at(): Long { var v = 0L; for (i in 0 until 4) v = v or ((body[offset + i].toLong() and 0xFF) shl (8 * i)); offset += 4; return v }
        fun cstring(): String {
            var e = offset
            while (body[e].toInt() != 0) e++
            val text = String(body, offset, e - offset, Charsets.UTF_8)
            offset = e + 1
            return text
        }
        val count = u8()
        val events = JArr()
        for (n in 0 until count) {
            val ident = u32at(); val cd = u32at()
            val name = cstring(); val desc = cstring()
            val formulas = JArr()
            val formulaCount = u8()
            for (f in 0 until formulaCount) {
                val m = u8()
                val materials = JArr()
                for (k in 0 until m) { val kind = u8(); val item = u32at(); val cnt = u32at(); materials.add(jarr(kind, item, cnt)) }
                val kind = u8(); val item = u32at(); val qty = u32at(); val remaining = u32at()
                formulas.add(jobj("materials" to materials, "result" to jarr(kind, item, qty), "remaining" to remaining))
            }
            events.add(jobj("id" to ident, "cd" to cd, "name" to name, "desc" to desc, "formulas" to formulas))
        }
        if (offset != body.size) throw PyValues.ValueError("S1760 type 9 has trailing bytes")
        return events
    }

    /** `_hero_investment(values)`: sort key of least_invested_first_v1 for a hero card. */
    private fun heroInvestment(values: Map<Long, JValue?>): List<BigInteger> {
        fun v(k: Long): BigInteger = values[k]?.takeIf { Py.truthy(it) }?.let { PyDocs.int(it) } ?: BigInteger.ZERO
        val powerUp = listOf(15L, 16L, 17L, 18L).fold(BigInteger.ZERO) { acc, f -> acc + v(f) }
        return listOf(v(2L), v(3L), powerUp, v(19L), v(24L), PyDocs.int(values[0L]).negate())
    }

    /** `_record_investment(record)`: sort key for a gear / jewel record `(uid, template, level, EXP, grade, super, enchant)`. */
    private fun recordInvestment(record: JArr): List<BigInteger> = listOf(
        PyDocs.int(record[2]), PyDocs.int(record[3]), PyDocs.int(record[4]), PyDocs.int(record[5]), PyDocs.int(record[6]),
        PyDocs.int(record[0]).negate())

    private val KEY_ORDER: Comparator<List<BigInteger>> = Comparator { a, b ->
        var c = 0
        for (i in 0 until minOf(a.size, b.size)) { c = a[i].compareTo(b[i]); if (c != 0) return@Comparator c }
        a.size.compareTo(b.size)
    }

    /** `protected_heroes(owned, excluded_heroes)`: {uid: why} of the hero cards an exchange never takes. */
    fun protectedHeroes(owned: Owned, excludedHeroes: Collection<JValue> = emptyList()): Map<Long, String> {
        val state = owned.state
        val isLeader = owned.inputs as? DailyInputs         // getattr(inputs, "hero_is_leader", None)
        val protectedMap = LinkedHashMap<Long, String>()
        for (fields in state.arr("heroes")) {
            val values = Acquisition.heroValues(fields.asArr)
            val uid = PyDocs.int(values[0L]).toLong()
            if (Py.truthy(values[14L])) protectedMap[uid] = "tutorial hero"
            else if (isLeader != null && isLeader.heroIsLeader(if (Py.truthy(values[1L])) PyDocs.long(values[1L]) else 0L)) protectedMap[uid] = "leader"
        }
        for (slot in (state["formation"] as? JArr) ?: JArr()) {
            val hu = slot.asObj["hero_uid"]
            if (Py.truthy(hu)) protectedMap.putIfAbsent(PyDocs.long(hu), "lineup")
        }
        val secondaryTeam = PyDocs.get(owned.current, "secondary_team")
        val secondary = (if (Py.truthy(secondaryTeam)) (secondaryTeam as JObj)["document"] else null)?.takeIf { Py.truthy(it) } as? JObj ?: JObj()
        for (reference in (secondary["references"] as? JArr) ?: JArr()) {
            if (reference is JObj && Py.truthy(reference["hero_uid"])) protectedMap.putIfAbsent(PyDocs.long(reference["hero_uid"]), "alternate team")
        }
        for (uid in excludedHeroes) protectedMap.putIfAbsent(PyDocs.long(uid), "set out / mining")
        return protectedMap
    }

    /** `pick_instances(kind, template, need, owned, excluded_heroes, taken)`: the `need` least-invested unequipped instances. */
    fun pickInstances(kind: Long, template: Long, need: Long, owned: Owned, excludedHeroes: Collection<JValue> = emptyList(),
                      taken: Collection<Long> = emptyList()): List<Long> {
        val takenSet = taken.toSet()
        val pool = ArrayList<Pair<List<BigInteger>, Long>>()
        when (kind) {
            HERO_KIND -> {
                val protectedMap = protectedHeroes(owned, excludedHeroes)
                for (f in owned.state.arr("heroes")) {
                    val v = Acquisition.heroValues(f.asArr)
                    val uid = PyDocs.int(values0(v)).toLong()
                    if (v[1L] == JInt(template) && uid !in protectedMap && uid !in takenSet)
                        pool.add((heroInvestment(v) + BigInteger.valueOf(uid)) to uid)
                }
            }
            EQUIP_KIND -> {
                val bag = owned.state.arr("bag_equipment_uids").map { it.long }.toSet()
                for (r in owned.state.arr("equipment")) {
                    val wire = r.asObj.arr("wire_values")
                    val uid = wire[0].long
                    if (wire[1] == JInt(template) && uid in bag && uid !in takenSet)
                        pool.add((recordInvestment(wire) + BigInteger.valueOf(uid)) to uid)
                }
            }
            JEWEL_KIND -> {
                val entries = owned.current.jewelEntriesView
                    ?: throw Acquisition.Rejected("No unequipped-jewelry list to take jewels from", ERROR_NO_JEWEL_LIST)
                for (e in entries) {
                    val rec = e.asObj.arr("record")
                    val uid = rec[0].long
                    if (rec[1] == JInt(template) && uid !in takenSet)
                        pool.add((recordInvestment(rec) + BigInteger.valueOf(uid)) to uid)
                }
            }
            else -> throw Acquisition.Rejected("Not a card material kind", ERROR_EXCHANGE_EVENT)
        }
        if (pool.size < need) throw Acquisition.Rejected("Not enough cards outside the protected ones", Acquisition.ERROR_NOT_ENOUGH)
        return pool.sortedWith(compareBy(KEY_ORDER) { it.first }).take(need.toInt()).map { it.second }
    }

    private fun values0(v: Map<Long, JValue?>): JValue = v[0L] ?: throw PyDocs.KeyError(0)

    /** `_remove_cards(kind, uids, owned, jewels)`: removal frames of the existing paths; mutates `jewels` for jewel kind. */
    private fun removeCards(kind: Long, uids: List<Long>, owned: Owned, jewels: JArr): List<Frame> {
        val frames = ArrayList<Frame>()
        when (kind) {
            HERO_KIND -> {
                for (uid in uids) owned.removeHero(uid)
                for (part in chunks(uids)) { frames.add(S_HERO_BENCH_REMOVE to counted(part)); frames.add(S_HERO_REMOVE to counted(part)) }
            }
            EQUIP_KIND -> {
                val gone = uids.toSet()
                owned.state["equipment"] = JArr(owned.state.arr("equipment").filter { it.asObj.arr("wire_values")[0].long !in gone }.toMutableList())
                owned.state["bag_equipment_uids"] = JArr(owned.state.arr("bag_equipment_uids").filter { it.long !in gone }.toMutableList())
                for (part in chunks(uids)) { frames.add(S_BAG_REMOVE to counted(part)); frames.add(S_EQUIP_REMOVE to counted(part)) }
            }
            else -> {
                val gone = uids.toSet()
                val kept = jewels.filter { it.asObj.arr("record")[0].long !in gone }
                jewels.clear(); jewels.addAll(kept)
                for (part in chunks(uids)) frames.add(S_JEWEL_LIST_REMOVE to counted(part))
            }
        }
        owned.log.add(jobj("op" to "exchange_consume_cards", "kind" to kind, "uids" to uids, "policy" to INSTANCE_POLICY))
        return frames
    }

    /**
     * `plan_exchange(request, owned, exchanges, document, now, served_time, excluded_heroes)`: C1665 → consume the
     * formula's materials × amount (bag items, Diamond / Gold role props, cards picked by least_invested_first_v1),
     * grant its result × amount, S1762 `09`+Reward, S1760 `09`.
     */
    fun planExchange(request: JObj, owned: Owned, exchanges: List<JValue>, document: JValue?, now: Long,
                     servedTime: Long, excludedHeroes: Collection<JValue> = emptySet()): Plan {
        val event = exchanges.map { it.asObj }.firstOrNull { PyDocs.at(it, "id") == request["event"] }
        if (event == null || request.long("formula") >= event.arr("formulas").size ||
            BigInteger.valueOf(servedTime) >= PyDocs.int(event["end"] ?: JInt(0x7fffffffL)))
            throw Acquisition.Rejected("No such exchange", ERROR_EXCHANGE_EVENT)
        val formula = event.arr("formulas")[request.long("formula").toInt()].asObj
        val amount = request.long("amount")
        val doc = exchangeRoll(document, exchanges, now)
        val used = exchangeUsed(doc, PyDocs.at(event, "id"), request.long("formula").toInt())
        if (amount < 1L || used + BigInteger.valueOf(amount) > PyDocs.int(PyDocs.at(formula, "limit")))
            throw Acquisition.Rejected("Exchange limit exceeded", ERROR_EXCHANGE_LIMIT)
        val materials = formula.arr("materials")
        val resultTriple = formula.arr("result")
        if (materials.any { it.asArr[0].long !in MATERIAL_KINDS } || resultTriple[0].long !in RESULT_KINDS)
            throw Acquisition.Rejected("This exchange kind is not served offline yet", ERROR_EXCHANGE_EVENT)
        val touchesJewels = resultTriple[0].long == JEWEL_KIND || materials.any { it.asArr[0].long == JEWEL_KIND }
        if (touchesJewels && owned.current.jewelEntriesView == null)
            throw Acquisition.Rejected("No unequipped-jewelry list for this exchange", ERROR_NO_JEWEL_LIST)
        val jewels = JArr((owned.current.jewelEntriesView ?: JArr()).toMutableList())
        // Every card is picked before anything changes, so a shortage refuses the whole request.
        val picked = ArrayList<List<Long>?>()
        val taken = LinkedHashSet<Long>()
        for (m in materials) {
            val mm = m.asArr
            val kind = mm[0].long
            if (kind == HERO_KIND || kind == EQUIP_KIND || kind == JEWEL_KIND) {
                val uids = pickInstances(kind, mm[1].long, mm[2].long * amount, owned, excludedHeroes, taken)
                taken.addAll(uids)
                picked.add(uids)
            } else picked.add(null)
        }
        val frames = ArrayList<Frame>()
        val afterReward = ArrayList<Frame>()
        for (i in materials.indices) {
            val mm = materials[i].asArr
            val kind = mm[0].long; val item = mm[1].long
            val total = mm[2].long * amount
            val uids = picked[i]
            when {
                uids != null -> frames.addAll(removeCards(kind, uids, owned, jewels))
                item == PAIR_DIAMOND -> { frames.add(owned.roleAdd(Acquisition.DIAMOND, -total)); afterReward.addAll(Shops.diamondAchievement(owned, total, servedTime)) }
                item == PAIR_GOLD -> frames.add(owned.roleAdd(Acquisition.GOLD, -total))
                else -> frames.addAll(owned.consumeTemplate(item, total))
            }
        }
        val rKind = resultTriple[0].long; val rItem = resultTriple[1].long; val rTotal = resultTriple[2].long * amount
        val reward = Acquisition.emptyReward()
        when {
            rKind == EQUIP_KIND -> for (n in 0 until rTotal) {
                val (_, groups) = owned.grantEquipment(rItem)
                frames.addAll(groups["add"] ?: emptyList()); frames.addAll(groups["book"] ?: emptyList())
                reward.arr("equips").add(jarr(rItem))
            }
            rKind == JEWEL_KIND -> {
                frames.addAll(grantJewels(owned, rItem, rTotal, jewels))
                reward["jewels"] = JArr((0 until rTotal).mapTo(ArrayList()) { jarr(rItem) })
            }
            rItem == PAIR_DIAMOND -> { frames.add(owned.roleAdd(Acquisition.DIAMOND, rTotal)); reward["diamond"] = JInt(rTotal) }
            rItem == PAIR_GOLD -> { frames.add(owned.roleAdd(Acquisition.GOLD, rTotal)); reward["gold"] = JInt(rTotal) }
            else -> { frames.add(owned.grantItem(rItem, rTotal)); reward.arr("items").add(jarr(rItem, rTotal)) }
        }
        doc.obj("used")["${PyDocs.str(PyDocs.at(event, "id"))}:${request.long("formula")}"] = JInt(used + BigInteger.valueOf(amount))
        frames.add(S_EVENT_REWARD to (byteArrayOf(T_EXCHANGE.toByte()) + BattleReport.encodeReward(reward)))
        frames.add(s1760(T_EXCHANGE, exchangeBody(exchanges, doc, servedTime, now)))
        frames.addAll(afterReward)
        val plan = Plan(jobj("event" to PyDocs.at(event, "id"), "formula" to request.long("formula"), "amount" to amount,
            "exchange_state_after" to doc, "evidence_class" to "native_use_structural_candidate_policy"), frames)
        val cards = JArr()
        for (i in materials.indices) {
            val uids = picked[i]
            if (uids != null && uids.isNotEmpty()) cards.add(jobj("kind" to materials[i].asArr[0].long, "template" to materials[i].asArr[1].long, "uids" to uids))
        }
        if (cards.isNotEmpty()) { plan["cards_consumed"] = cards; plan["instance_policy"] = INSTANCE_POLICY }
        if (touchesJewels) plan["jewel_entries_after"] = JArr(jewels.sortedBy { it.asObj.arr("record")[0].long }.toMutableList())
        return plan
    }

    // --- Great Offer (SGXJ) ---

    /** `_diamond_spend_frames(owned, amount)`: the Diamond-spending achievement (needs achievements + game_activities). */
    private fun diamondSpendFrames(owned: Owned, amount: Long): List<Frame> {
        val subsystems = owned.state["subsystems"] as? JObj ?: JObj()
        if (PyDocs.get(subsystems, "achievements") == null || PyDocs.get(subsystems, "game_activities") == null) return emptyList()
        return Shops.diamondAchievement(owned, amount, null)
    }

    /**
     * `plan_great_offer(owned, document, inputs, now, seed)`: one Great Offer spin (the caller refused closed /
     * exhausted): S128 Diamond, S1762 `07`+Reward{diamond}, S1760 `07`, then the Diamond-spend achievement.
     */
    fun planGreatOffer(owned: Owned, document: JValue?, inputs: DailyInputs, now: Long, seed: BigInteger): Plan {
        val view = greatOfferView(document, inputs, now)
        if (view == null || PyDocs.int(PyDocs.at(view, "remaining")) == BigInteger.ZERO)
            throw Acquisition.Rejected("Offer is not currently available", ERROR_OFFER)
        val row = view.obj("row")
        val diamonds = owned.roleBits(Acquisition.DIAMOND)
        val cost = row.long("cost")
        if (diamonds < BigInteger.valueOf(cost)) throw Acquisition.Rejected("Not enough Diamonds for the offer", Acquisition.ERROR_RESOURCES)
        val prizes = row.arr("prizes")
        val total = prizes.sumOf { it.asArr[1].long }
        val roll = PyRandom.seeded(seed).randrange(total)
        var cumulative = 0L
        var index = 0
        var prize = 0L
        for (i in prizes.indices) {
            val p = prizes[i].asArr
            index = i; prize = p[0].long
            cumulative += p[1].long
            if (roll < cumulative) break
        }
        val frames = ArrayList<Frame>()
        frames.add(owned.roleAdd(Acquisition.DIAMOND, prize - cost))
        val reward = Acquisition.emptyReward()
        reward["diamond"] = JInt(prize)
        val after = greatOfferRoll(document, view.str("window"))
        val draw = jobj("attempt" to PyDocs.at(row, "id"), "cost" to cost, "prize" to prize, "prize_index" to index, "roll" to roll,
            "weights_total" to total, "seed" to seed.toString(), "at" to now)
        after["spins"] = JInt(PyDocs.int(PyDocs.at(view, "spins")) + BigInteger.ONE)
        after["draws"] = JArr((after.arr("draws").toList() + draw).toMutableList())
        frames.add(S_EVENT_REWARD to (byteArrayOf(T_GREAT_OFFER.toByte()) + BattleReport.encodeReward(reward)))
        frames.add(s1760(T_GREAT_OFFER, greatOfferBody(after, inputs, now)))
        frames.addAll(diamondSpendFrames(owned, cost))
        return Plan(jobj("window" to view["window"], "attempt" to PyDocs.at(row, "id"), "cost" to cost, "prize" to prize,
            "diamonds_before" to diamonds, "diamonds_after" to (diamonds - BigInteger.valueOf(cost) + BigInteger.valueOf(prize)),
            "draw" to draw, "draw_policy" to GREAT_OFFER_DRAW, "sgxj_state_after" to after,
            "evidence_class" to "native_use_table_weights_policy_window_seeded_draw"), frames)
    }
}
