package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.WireWriter
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

    /** Claimed piece ids of the served day (a UTC day of the served clock). */
    fun pieView(document: JValue?, servedTime: Long): List<JValue> {
        val doc = if (Py.truthy(document)) document as JObj else JObj()
        val day = Math.floorDiv(servedTime, 86400L)
        if (PyDocs.get(doc, "served_day") != JInt(day)) return emptyList()
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
        val spins = PyDocs.int(PyDocs.at(greatOfferRoll(document, window), "spins")).min(BigInteger.valueOf(rows.size.toLong()))
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
    // Lead-written stubs with fixed signatures so the daily / sweep routes wire in without conflicts; the Event Hall
    // slice replaces each body with the port of the matching `event_hall.py` function. Until then a NotPorted keeps the
    // step waiting in the harness (the request's first cause moves here from the route).

    /** `plan_palace_visit(owned, inputs, document, now)`: C1635 → S1762 `02`+Reward(Gold), S128 Gold, S1760 `02`. */
    fun planPalaceVisit(owned: Owned, inputs: DailyInputs, document: JObj, now: Long): Plan =
        throw NotPorted("event_hall.plan_palace_visit (C1635)")

    /** `plan_palace_claim(owned, inputs, document, now)`: C1637 → Diamond / bonus items, S1762 `02`+Reward, S1760 `02`. */
    fun planPalaceClaim(owned: Owned, inputs: DailyInputs, document: JObj, now: Long): Plan =
        throw NotPorted("event_hall.plan_palace_claim (C1637)")

    /** `decode_pie_request(payload)`: C1641 `u32 piece id`. */
    fun decodePieRequest(payload: ByteArray): JObj =
        throw NotPorted("event_hall.decode_pie_request (C1641)")

    /** `plan_magic_pie(request, owned, document, served_time)`: C1641 → S128 AP, S128 Energy, S1762 `04`, S1760 `04`. */
    fun planMagicPie(request: JObj, owned: Owned, document: JValue?, servedTime: Long): Plan =
        throw NotPorted("event_hall.plan_magic_pie (C1641)")

    /** `decode_combine(payload)`: C3077 `u32 recipe, u8 4, 4 × u32 equipment uid`. */
    fun decodeCombine(payload: ByteArray): JObj =
        throw NotPorted("event_hall.decode_combine (C3077)")

    /** `plan_combine(request, owned, inputs)`: C3077 → the recipe's result equipment (equip_hecheng.csv), S3300. */
    fun planCombine(request: JObj, owned: Owned, inputs: DailyInputs): Plan =
        throw NotPorted("event_hall.plan_combine (C3077)")

    /** `decode_exchange(payload)`: C1665 `u32 event, u8 formula index, u32 amount`. */
    fun decodeExchange(payload: ByteArray): JObj =
        throw NotPorted("event_hall.decode_exchange (C1665)")

    /**
     * `plan_exchange(request, owned, exchanges, document, now, served_time, excluded_heroes)`: C1665 → consume the
     * formula's materials × amount (bag items, Diamond / Gold role props, cards picked by least_invested_first_v1),
     * grant its result × amount, S1762 `09`+Reward, S1760 `09`.
     */
    fun planExchange(request: JObj, owned: Owned, exchanges: List<JValue>, document: JValue?, now: Long,
                     servedTime: Long, excludedHeroes: Set<JValue> = emptySet()): Plan =
        throw NotPorted("event_hall.plan_exchange (C1665)")

    /**
     * `plan_great_offer(owned, document, inputs, now, seed)`: one Great Offer spin (the caller refused closed /
     * exhausted): S128 Diamond, S1762 `07`+Reward{diamond}, S1760 `07`, then the Diamond-spend achievement.
     */
    fun planGreatOffer(owned: Owned, document: JValue?, inputs: DailyInputs, now: Long, seed: BigInteger): Plan =
        throw NotPorted("event_hall.plan_great_offer (C1649)")
}
