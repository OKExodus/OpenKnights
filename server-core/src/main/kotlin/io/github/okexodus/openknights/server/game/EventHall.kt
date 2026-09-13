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
import io.github.okexodus.openknights.protocol.WireReader
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
        val doc = if (PyDocs.truthy(document)) document as JObj else JObj()
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
        val doc = if (PyDocs.truthy(document)) document as JObj else JObj()
        val used = (doc["used"] ?: JObj()) as JObj
        return PyDocs.int(used["${PyDocs.str(eventId)}:$index"] ?: JInt(0))
    }

    /** Restart the used counts of every exchange whose limit period changed (a v1 document restarts everything). */
    fun exchangeRoll(document: JValue?, exchanges: List<JValue>, now: Long): JObj {
        val base = if (!PyDocs.truthy(document) || PyDocs.get(document as JObj, "profile") != JStr(EXCHANGE_PROFILE))
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
        val events = exchanges.map { it.asObj }.filter { PyDocs.truthy(it["materials_kind_ok"] ?: JBool(true)) }
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
        if (!PyDocs.truthy(document) || PyDocs.get(doc, "profile") != JStr(GREAT_OFFER_PROFILE) || PyDocs.get(doc, "window") != JStr(window)) {
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
        val palace = palaceRoll(if (PyDocs.truthy(stored)) stored!! else seedPalace(seedType[T_PALACE], null), today, inputs)
        // The reference reads `current["blacksmith_body"]`, a key no code writes: always eight zero bytes.
        return listOf(S_EVENT_UPDATE to levelGift,
            s1760(T_PALACE, palaceBody(palace, today)),
            s1760(T_MAGIC_PIE, pieBody(PyDocs.get(current, "magic_pie_state"), servedTime)),
            s1760(T_BLACKSMITH, ByteArray(8)),
            s1760(T_HERO_POOL, byteArrayOf(0)),
            s1760(T_GREAT_OFFER, greatOfferBody(PyDocs.get(current, "sgxj_state"), inputs, now)),
            s1760(T_REBATE, byteArrayOf(0)),
            s1760(T_EXCHANGE, exchangeBody(exchanges(), exchangeRoll(PyDocs.get(current, "exchange_state"), exchanges(), now), servedTime, now)),
            TmpVip.frame(current, seeds, now))
    }

    fun exchanges(): List<JValue> = (Events.ACTIVE["exchanges"] as? JArr) ?: emptyList()

    /**
     * The Temp VIP S1824 of the login set — the reference's `sweep_features.tmp_vip_frame` (owned by the sweep-features
     * port; kept here so the login set is complete): claimed and running → `01 <seconds left>`, unclaimed `00`, ended
     * (or a real VIP of 4 and more) `02`.
     */
    object TmpVip {
        const val S_TMP_VIP = 1824
        const val UNCLAIMED = 0L
        const val ACTIVE = 1L
        const val EXPIRED = 2L
        const val LEVEL = 4L
        const val SECONDS = 4L * 86400
        const val PROFILE = "tmp_vip_v1"

        fun payload(state: Long, seconds: BigInteger): ByteArray =
            WireWriter().number('B', state).number('I', seconds.min(BigInteger.valueOf(0x7fffffff)).max(BigInteger.ZERO)).bytes()

        fun decode(payload: ByteArray): Pair<Long, Long> {
            if (payload.size != 5) throw PyValues.ValueError("S1824 is u8 state, u32 seconds")
            val r = WireReader(payload)
            return r.u8().toLong() to r.u32()
        }

        fun document(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?): JObj {
            PyDocs.get(current, "tmp_vip")?.let { return it.deepCopy() as JObj }
            val payload = seeds?.first(S_TMP_VIP)
            val doc = jobj("profile" to PROFILE, "claimed" to false, "claimed_at" to null, "expires_at" to null)
            if (payload == null) return doc.also { it["seed"] = jobj("source" to "unclaimed_default_policy") }
            val (state, _) = decode(payload)
            if (state == ACTIVE || state == EXPIRED) {
                doc["claimed"] = JBool(true)
                doc["expires_at"] = JInt(0)
            }
            val seed = seeds.provenance(S_TMP_VIP)
            seed["state"] = JInt(state)
            doc["seed"] = seed
            return doc
        }

        fun realVipLevel(current: StateStore.Current): JValue {
            for (f in current.state.arr("role_properties")) {
                val field = f.asObj
                if (field["id"] == JInt(27)) {
                    val bits = field.obj("value")["bits"] ?: JInt(0)
                    return if (PyDocs.truthy(bits)) bits else JInt(0)
                }
            }
            return JInt(0)
        }

        fun view(document: JObj, now: Long, vipLevel: JValue): Pair<Long, BigInteger> {
            if (PyDocs.compare(vipLevel, JInt(LEVEL)) >= 0) return EXPIRED to BigInteger.ZERO
            if (!PyDocs.truthy(document["claimed"])) return UNCLAIMED to BigInteger.ZERO
            val expires = PyDocs.get(document, "expires_at")
            val left = (if (PyDocs.truthy(expires)) PyDocs.int(expires) else BigInteger.ZERO) - BigInteger.valueOf(now)
            if (left.signum() > 0) return ACTIVE to left.min(PyDocs.int(document["duration_seconds"] ?: JInt(SECONDS)))
            return EXPIRED to BigInteger.ZERO
        }

        fun frame(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, now: Long): Frame {
            val (state, seconds) = view(document(current, seeds), now, realVipLevel(current))
            return S_TMP_VIP to payload(state, seconds)
        }
    }
}
