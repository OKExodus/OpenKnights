package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.Now
import io.github.okexodus.openknights.exact.PyRandom
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.jvalue
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.ReleaseData
import io.github.okexodus.openknights.server.ReleaseDataError
import io.github.okexodus.openknights.server.store.SqlConnection
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Shops (C1057 / C75), the Lucky Shop (C2725 / C2723) and the Fate roulette (C641) of `shops.py`, plus the day rules
 * of the device clock (`day_of`, `local_datetime`, `next_day_start`) and the plain per-character document tables
 * (`read_state` / `write_state`). The catalogs are server-authored: the local server serves the catalog observed live
 * (release-data/shop-catalog.json). Refresh cadences, the roulette draw and the Lucky Shop pool are labeled local
 * policies. The Lucky Shop cycle is a UTC day (`now // period` from the catalog anchor); every other daily reset is
 * the device's local midnight.
 */
object Shops {
    const val LIST_OPCODE = 1057
    const val BUY_OPCODE = 75
    const val S_LIST = 1120
    const val S_COUNTS = 1122
    const val S_BUY_REWARD = 1088
    const val LUCKY_INFO_OPCODE = 2725
    const val LUCKY_EXCHANGE_OPCODE = 2723
    const val S_LUCKY_INFO = 3170
    const val S_LUCKY_REWARD = 3172
    const val ROULETTE_OPCODE = 641
    const val S_ROULETTE = 704

    const val ERROR_NO_COMMODITY = 7001      // "Cannot find the item"
    const val ERROR_BUY_FAILED = 7002        // "Failed to Buy"
    const val ERROR_DAILY_LIMIT = 7003       // "You reached the purchase limit/ per day"
    const val ERROR_TOTAL_LIMIT = 7004       // "You reached the purchase limit/ per time"
    const val ERROR_VIP = 15005              // "Not enough VIP level"
    const val ERROR_PURCHASED = 69676        // "Item has been purchased"
    const val ERROR_EVENT_OVER = 1787        // client text "The Event is over."

    /** StoreManager::GetMyCurrency: resource currencies → role property ids; item currencies → item templates. */
    val CURRENCY_ROLE: Map<Long, Long> = linkedMapOf(90001L to 6L, 90002L to 7L, 90003L to 8L, 90004L to 11L, 90005L to 12L, 90007L to 30L)
    val CURRENCY_ITEM: Map<Long, Long> = linkedMapOf(90009L to 30105L, 90011L to 30104L, 90012L to 30103L)
    const val DIAMOND_ACHIEVEMENT = 18L        // live S578 [18, 2, n]: cumulative Diamonds spent
    const val VIP_LEVEL = 27L
    /** Class byte − 1 → (coupon template, per spin) (IsEnoughCoupon). */
    val ROULETTE_COUPON: Map<Long, Pair<Long, Long>> = mapOf(0L to (30232L to 1L), 1L to (30232L to 10L), 2L to (30110L to 1L))
    val ROULETTE_SCORE = listOf(1L, 10L, 1L)
    val ROULETTE_TIMES = listOf(1L, 10L, 100L)

    val RECORD = listOf("id" to 'I', "type" to 'B', "f08" to 'I', "item" to 'I', "count" to 'I', "kind" to 'I', "currency" to 'I',
        "price" to 'I', "list_price" to 'I', "on_sale" to 'i', "limit" to 'I', "order" to 'I', "icon" to 'I',
        "name" to 's', "desc" to 's', "f68" to 'I', "cd" to 'I', "vip_list" to 'B', "vip_buy" to 'B',
        "lifetime" to 'I', "f90" to 's', "channel" to 'B')
    val LUCKY_RECORD = listOf("id" to 'I', "type" to 'B', "pool" to 'I', "c6" to 'B', "bought" to 'B', "item" to 'I',
        "qty" to 'I', "kind" to 'B', "currency" to 'I', "price" to 'I')

    /**
     * Operator decision 2026-09-12 (QA): the 8-star and 6-star hero pieces of the on-sale Diamond shop (shop 4) are
     * withheld until a long, difficult way to earn them is designed; pieces sold for other currencies stay.
     */
    val WITHHELD_COMMODITIES: Set<Long> = (40028L..40040L).toSet()

    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    // --- the device clock's days -------------------------------------------------------------------------------------

    /** The device clock's local date-time of an epoch (a fixed offset at that epoch); without one, the host offset. */
    fun localDatetime(epoch: Long): OffsetDateTime = DeviceClock.active?.localDateTime(epoch)
        ?: OffsetDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.ofTotalSeconds(Now.offset(epoch)))

    /** The epoch of the next local midnight after `epoch` (the same clock as [dayOf]). */
    fun nextDayStart(epoch: Long): Long {
        DeviceClock.active?.let { return it.nextDayStart(epoch) }
        val midnight = localDatetime(epoch).withHour(0).withMinute(0).withSecond(0).withNano(0)
        return midnight.plusDays(1).toEpochSecond()
    }

    /** The calendar day `YYYY-MM-DD` of the device clock (release: the high-water day never goes back). */
    fun dayOf(epoch: Long): String = DeviceClock.active?.localDay(epoch) ?: DAY.format(localDatetime(epoch))

    // --- per-character document tables ---------------------------------------------------------------------------------

    fun readState(db: SqlConnection, table: String): JValue? {
        if (!db.tableExists(table)) return null
        val row = db.queryOne("SELECT document_json FROM $table WHERE id=1") ?: return null
        return Json.loads(row.string("document_json"))
    }

    /** Sorted compact JSON (NaN allowed, as `json.dumps(sort_keys=True, separators=(",", ":"))`), no checksum. */
    fun writeState(db: SqlConnection, table: String, document: JValue) {
        db.execute("CREATE TABLE IF NOT EXISTS $table (id INTEGER PRIMARY KEY CHECK(id=1), document_json TEXT NOT NULL)")
        db.execute("INSERT INTO $table VALUES(1,?) ON CONFLICT(id) DO UPDATE SET document_json=excluded.document_json",
            Json.dumps(document, sortKeys = true, itemSeparator = ",", keySeparator = ":"))
    }

    // --- records --------------------------------------------------------------------------------------------------------

    private fun encodeRecord(record: JObj, layout: List<Pair<String, Char>>, w: WireWriter) {
        for ((name, fmt) in layout) {
            if (fmt == 's') w.raw(((record[name] as JStr).value).toByteArray(Charsets.UTF_8) + byteArrayOf(0))
            else w.number(fmt, record[name] ?: throw PyDocs.KeyError("'$name'"))
        }
    }

    /** S1120: `u8 n`, n × commodity record. */
    fun encodeCommodityList(records: List<JValue>): ByteArray {
        val w = WireWriter().number('B', records.size.toLong())
        for (r in records) encodeRecord(r.asObj, RECORD, w)
        return w.bytes()
    }

    /** S1122: today's then the total purchase counts, each `u8 n, n × (u32 commodity, u32 count)` (zero counts left out). */
    fun countsPayload(today: JObj, total: JObj): ByteArray {
        val w = WireWriter()
        for (values in listOf(today, total)) {
            val items = values.entries.filter { Py.truthy(it.value) }.map { PyValues.parseInt(it.key) to it.value }
                .sortedWith { a, b -> a.first.compareTo(b.first).let { c -> if (c != 0) c else PyDocs.compare(a.second, b.second) } }
            w.number('B', items.size.toLong())
            for ((k, v) in items) w.number('I', k).number('I', v)
        }
        return w.bytes()
    }

    fun decodeListRequest(payload: ByteArray): JObj {
        if (payload.size != 1 || (payload[0].toInt() and 0xFF) !in 1..16) throw Acquisition.Rejected("C1057 is u8 shop type 1..16")
        return jobj("type" to (payload[0].toInt() and 0xFF))
    }

    fun decodeBuyRequest(payload: ByteArray): JObj {
        if (payload.size != 8) throw Acquisition.Rejected("C75 is u32 commodity, i32 quantity")
        val r = WireReader(payload)
        return jobj("commodity" to r.u32(), "quantity" to r.i32())
    }

    fun decodeLuckyRequest(payload: ByteArray): JObj {
        if (payload.size != 5) throw Acquisition.Rejected("C2723/C2725 is u8, u32")
        val r = WireReader(payload)
        return jobj("store_type" to r.u8(), "value" to r.u32())
    }

    fun decodeRouletteRequest(payload: ByteArray): JObj {
        if (payload.size != 5) throw Acquisition.Rejected("C641 is u8 class byte, u32 times")
        val r = WireReader(payload)
        return jobj("class_byte" to r.u8(), "times" to r.u32())
    }

    /** S3170: `u8 n`, n × 28-byte record, `u32 countdown, u32 countdown, u8 flag`. */
    fun encodeLuckyInfo(entries: List<JValue>, countdown1: Long, countdown2: Long, flag: Long): ByteArray {
        val w = WireWriter().number('B', entries.size.toLong())
        for (e in entries) encodeRecord(e.asObj, LUCKY_RECORD, w)
        return w.number('I', countdown1).number('I', countdown2).number('B', flag).bytes()
    }

    // --- Lucky Shop -------------------------------------------------------------------------------------------------------

    /** Entries 100.. from pool ids (a pool id always carried the same goods). */
    fun poolEntries(lucky: JObj, pools: List<JValue>): JArr {
        val out = JArr()
        pools.forEachIndexed { index, pool ->
            val goods = lucky.obj("pools")[PyDocs.str(pool)]?.let { it as JObj } ?: throw PyDocs.KeyError("'${PyDocs.str(pool)}'")
            val entry = jobj("id" to 100 + index, "type" to PyDocs.at(lucky, "store_type"), "pool" to pool, "c6" to 0, "bought" to 0)
            for (k in listOf("item", "qty", "kind", "currency", "price")) entry[k] = PyDocs.at(goods, k)
            out.add(entry)
        }
        return out
    }

    /** Labeled policy `uniform_over_observed_pools`: each entry draws one observed pool id, with replacement. */
    fun drawLuckyPools(lucky: JObj, seed: BigInteger): List<Long> {
        val rng = PyRandom.seeded(seed)
        val observed = lucky.obj("pools").keys.map { PyValues.parseLong(it) }.sorted()
        return lucky.arr("entries").map { rng.choice(observed) }
    }

    /** The draw seed of a cycle: the first 8 bytes (little-endian) of SHA-256 of `lucky-cycle:<cycle>`. */
    fun cycleSeed(cycle: Long): BigInteger {
        val digest = MessageDigest.getInstance("SHA-256").digest("lucky-cycle:$cycle".toByteArray(Charsets.UTF_8))
        return BigInteger(1, digest.copyOfRange(0, 8).reversedArray())
    }

    /**
     * (entries with this character's bought flags, seconds to the cycle's end, document) — `lucky_view`. Entries of a
     * cycle: the character's paid refresh of the cycle, else the observed set of that cycle, else (labeled policy) a
     * draw seeded by the cycle number, else the captured set.
     */
    fun luckyView(catalog: JObj, document: JValue?, now: Long, poolPolicy: Boolean = false): Triple<JArr, Long, JObj> {
        val lucky = catalog.obj("lucky")
        val period = PyDocs.long(PyDocs.at(lucky, "refresh_period_s"))
        val anchor = PyDocs.long(PyDocs.at(lucky, "refresh_anchor_epoch"))
        val cycle = if (now >= anchor) Math.floorDiv(now - anchor, period) else -1L
        val doc = (if (Py.truthy(document)) document!! else jobj("profile" to "lucky_state_v1", "cycle" to cycle, "bought" to JArr())).deepCopy() as JObj
        if (PyDocs.at(doc, "cycle") != JInt(cycle)) {
            doc["cycle"] = JInt(cycle)
            doc["bought"] = JArr()
            doc.remove("pools")
        }
        val deadline = anchor + (cycle + 1) * period
        val remaining = maxOf(0L, deadline - now)
        val observed = lucky["observed_cycles"] as? JObj ?: JObj()
        val base: List<JValue> = when {
            Py.truthy(doc["pools"]) -> poolEntries(lucky, doc.arr("pools"))
            cycle.toString() in observed -> poolEntries(lucky, observed.arr(cycle.toString()))
            poolPolicy && cycle >= 0 && Py.truthy(lucky["pools"]) -> poolEntries(lucky, drawLuckyPools(lucky, cycleSeed(cycle)).map { JInt(it) })
            else -> lucky.arr("entries")
        }
        val bought = PyDocs.at(doc, "bought") as JArr
        val entries = JArr()
        for (e in base) {
            val entry = PyDocs.shallow(e.asObj)
            entry["bought"] = JInt(if (PyDocs.at(entry, "id") in bought) 1 else 0)
            entries.add(entry)
        }
        return Triple(entries, remaining, doc)
    }

    /**
     * C2725: mode 0 serves the cycle's entries; mode 1 is the paid refresh (one Fate Voucher, then a labeled-policy
     * re-draw of the pools with [seed]). [forcedPools] replaces the draw with given observed pool ids (replays only).
     */
    @Suppress("UNUSED_PARAMETER")
    fun planLuckyInfo(request: JObj, owned: Owned, inputs: AcquisitionInputs, catalog: JObj, document: JValue?, now: Long,
                      seed: BigInteger? = null, poolPolicy: Boolean = false, forcedPools: List<Long>? = null, serverTime: Long? = null): Plan {
        if (PyDocs.at(request, "store_type") != JInt(2) || PyDocs.at(request, "value") !in listOf(JInt(0), JInt(1))) {
            throw Acquisition.Rejected("C2725 is u8 2, u32 mode 0/1")
        }
        var (entries, remaining, doc) = luckyView(catalog, document, now, poolPolicy)
        val packets = ArrayList<Frame>()
        var pools: List<Long>? = null
        val mode = PyDocs.long(PyDocs.at(request, "value"))
        if (mode == 1L) {
            // Paid refresh: live, one Fate Voucher (property 405 / 407 for store type 2) S68, then the re-rolled S3170.
            val lucky = catalog.obj("lucky")
            if (forcedPools == null && (!poolPolicy || seed == null || !Py.truthy(lucky["pools"]))) {
                throw Acquisition.Rejected("The Lucky Shop refresh needs the labeled lucky_refresh policy", Acquisition.ERROR_WRONG_TYPE)
            }
            val storeType = PyDocs.at(lucky, "store_type")
            val (itemKey, countKey) = if (storeType == JInt(1)) 402 to 403 else 405 to 407
            // a missing or non-numeric voucher row is a labelled refusal (it was an internal error)
            val (itemValue, countValue) = try {
                PyValues.parseInt(inputs.property(itemKey) ?: throw PyDocs.TypeError("no property $itemKey")) to
                    PyValues.parseInt(inputs.property(countKey) ?: throw PyDocs.TypeError("no property $countKey"))
            } catch (e: PyDocs.TypeError) {
                throw Acquisition.Rejected("The Lucky Shop refresh voucher is not configured (property $itemKey / $countKey)", Acquisition.ERROR_WRONG_TYPE)
            } catch (e: PyValues.ValueError) {
                throw Acquisition.Rejected("The Lucky Shop refresh voucher is not configured (property $itemKey / $countKey)", Acquisition.ERROR_WRONG_TYPE)
            }
            val item = itemValue.longValueExact()
            val count = countValue.longValueExact()
            packets.addAll(owned.consumeTemplate(item, count))
            pools = forcedPools?.toList() ?: drawLuckyPools(lucky, seed!!)
            doc["bought"] = JArr()
            doc["pools"] = jvalue(pools)
            entries = poolEntries(lucky, pools.map { JInt(it) })
        }
        packets.add(S_LUCKY_INFO to encodeLuckyInfo(entries, remaining, remaining, PyDocs.long(PyDocs.at(catalog.obj("lucky"), "flag"))))
        return Plan(jobj("mode" to mode, "lucky_state_after" to doc, "remaining" to remaining, "pools" to pools,
            "seed" to (if (!pools.isNullOrEmpty()) seed else null),
            "evidence_class" to (if (mode == 0L) "capture_observed_entries" else "preservation_policy_lucky_refresh")), packets)
    }

    /** C2723: buy one entry of the cycle (live, Diamonds: S578, [S1188], S68 grant, S128 Diamond, S3172 Reward + tail). */
    @Suppress("UNUSED_PARAMETER")
    fun planLuckyExchange(request: JObj, owned: Owned, inputs: AcquisitionInputs, catalog: JObj, document: JValue?, now: Long,
                          poolPolicy: Boolean = false, seed: BigInteger? = null, serverTime: Long? = null): Plan {
        val (entries, _, doc) = luckyView(catalog, document, now, poolPolicy)
        val entry = entries.map { it.asObj }.firstOrNull {
            PyDocs.at(it, "id") == PyDocs.at(request, "value") && PyDocs.at(it, "type") == PyDocs.at(request, "store_type")
        } ?: throw Acquisition.Rejected("Unknown Lucky Shop entry", ERROR_NO_COMMODITY)
        if (Py.truthy(entry["bought"])) throw Acquisition.Rejected("Entry already bought", ERROR_PURCHASED)
        val currency = PyDocs.long(PyDocs.at(entry, "currency"))
        val price = PyDocs.long(PyDocs.at(entry, "price"))
        val role = CURRENCY_ROLE[currency]
        if (role != null) {
            if (owned.roleBits(role) < BigInteger.valueOf(price)) throw Acquisition.Rejected("Not enough resources", Acquisition.ERROR_RESOURCES)
        } else if (owned.countOf(CURRENCY_ITEM[currency] ?: currency) < price) {
            throw Acquisition.Rejected("Not enough items", Acquisition.ERROR_NOT_ENOUGH)      // live: S6 2002
        }
        val achievement = if (currency == 90003L) diamondAchievement(owned, price, serverTime) else emptyList()
        val item = PyDocs.long(PyDocs.at(entry, "item"))
        val qty = PyDocs.long(PyDocs.at(entry, "qty"))
        val grant = owned.grantItem(item, qty)
        val (before, after) = pay(owned, currency, price)
        val bought = LinkedHashSet<JValue>(PyDocs.at(doc, "bought") as JArr).also { it.add(PyDocs.at(entry, "id")) }
        doc["bought"] = PyDocs.sorted(bought)
        val reward = BattleReport.emptyReward()
        reward["items"] = jarr(jarr(item, qty))
        val packets = achievement + listOf(grant) + before + after +
            listOf(S_LUCKY_REWARD to (BattleReport.encodeReward(reward) + WireWriter().number('I', PyDocs.at(entry, "id")).number('B', 1L).bytes()))
        return Plan(jobj("entry" to entry["id"], "item" to item, "qty" to qty, "currency" to currency, "price" to price,
            "lucky_state_after" to doc, "reward" to reward, "evidence_class" to "capture_observed"), packets)
    }

    // --- per-character shop counts, Diamond spending, payment -------------------------------------------------------------

    /** Today's counts reset at local midnight of the device clock (labeled policy, see [dayOf]). */
    fun shopCounts(document: JValue?, now: Long): JObj {
        val doc = (if (Py.truthy(document)) document!! else jobj("profile" to "shop_state_v1", "day" to dayOf(now), "today" to JObj(), "total" to JObj())).deepCopy() as JObj
        if (PyDocs.at(doc, "day") != JStr(dayOf(now))) {
            doc["day"] = JStr(dayOf(now))
            doc["today"] = JObj()
        }
        return doc
    }

    /** S578 (cumulative Diamonds spent), then the S1188 of every active Diamond-spending ladder (live order). */
    fun diamondAchievement(owned: Owned, amount: Long, serverTime: Long? = null): List<Frame> {
        val frames = ArrayList<Frame>()
        for (entry in owned.state.obj("subsystems").obj("achievements").arr("entries")) {
            val wire = PyDocs.at(entry.asObj, "wire_values") as JArr
            if (wire[0] == JInt(DIAMOND_ACHIEVEMENT)) {
                wire[2] = JInt(PyDocs.int(wire[2]) + BigInteger.valueOf(amount))
                frames.add(Acquisition.S_ACHIEVEMENT to Acquisition.achievementPayload(wire))
                break
            }
        }
        return frames + ActivityProgress.advance(owned.state, "diamond_spend", amount, serverTime)
    }

    /** (frames before the reward, frames after it) — live: the role S128 after S1088, item stacks before. */
    fun pay(owned: Owned, currency: Long, amount: Long): Pair<List<Frame>, List<Frame>> {
        CURRENCY_ROLE[currency]?.let { return emptyList<Frame>() to listOf(owned.roleAdd(it, -amount)) }
        return owned.consumeTemplate(CURRENCY_ITEM[currency] ?: currency, amount) to emptyList()
    }

    // --- shops ------------------------------------------------------------------------------------------------------------

    fun servedRecords(shop: JObj?): List<JValue> =
        (if (shop != null && Py.truthy(shop)) shop.arr("records") else JArr()).filter { PyDocs.long(PyDocs.at(it.asObj, "id")) !in WITHHELD_COMMODITIES }

    /** C1057: the shop's served records and this character's counts. */
    fun planList(request: JObj, catalog: JObj, document: JValue?, now: Long): Plan {
        val type = PyDocs.at(request, "type")
        val shop = catalog.obj("shops")[PyDocs.str(type)] as? JObj
        val records = servedRecords(shop)
        val counts = shopCounts(document, now)
        return Plan(jobj("type" to type, "records" to records.size, "served" to (if (shop != null && Py.truthy(shop)) "captured_catalog" else "empty_policy"),
            "shop_state_after" to counts),
            listOf(S_LIST to encodeCommodityList(records), S_COUNTS to countsPayload(counts.obj("today"), counts.obj("total"))))
    }

    /** HandleOnSaleGoodsList: limit × (col501 / 10000 + 1.0) in binary64, truncated. */
    fun dailyLimit(record: JObj, vipDiscount501: Long): Long =
        ((vipDiscount501.toDouble() / 10000.0 + 1.0) * PyDocs.long(PyDocs.at(record, "limit")).toDouble()).toLong()

    /** C75: the catalog price × quantity; the goods, the payment, the Diamond achievement, the counts, S1088. */
    fun planBuy(request: JObj, owned: Owned, inputs: AcquisitionInputs, catalog: JObj, document: JValue?, now: Long, serverTime: Long? = null): Plan {
        val commodity = PyDocs.long(PyDocs.at(request, "commodity"))
        val quantity = PyDocs.long(PyDocs.at(request, "quantity"))
        if (quantity <= 0) throw Acquisition.Rejected("Quantity must be positive")
        val record = catalog.obj("commodities")[commodity.toString()] as? JObj
        if (record == null || commodity in WITHHELD_COMMODITIES) throw Acquisition.Rejected("Unknown commodity", ERROR_NO_COMMODITY)
        if (!Py.truthy(record["on_sale"])) throw Acquisition.Rejected("Commodity is not on sale", ERROR_BUY_FAILED)
        var vip = owned.role(VIP_LEVEL).bits
        vip = SweepFeatures.tmpVipLevel(owned.current, vip, now)       // shop rows +0x80 / +0x81 read GetTmpVipLevel
        if (vip < PyDocs.long(PyDocs.at(record, "vip_buy"))) throw Acquisition.Rejected("VIP level too low", ERROR_VIP)
        val ceiling = inputs.shopVipCeiling(commodity)
        if (ceiling != null && vip >= ceiling) throw Acquisition.Rejected("Commodity is not offered at this VIP level", ERROR_NO_COMMODITY)
        val item = PyDocs.long(PyDocs.at(record, "item"))
        if (PyDocs.at(record, "kind") !in listOf(JInt(1), JInt(2)) || inputs.item(item) == null) {
            throw Acquisition.Rejected("Commodity goods outside the supported item profile", Acquisition.ERROR_WRONG_TYPE)
        }
        val currency = PyDocs.long(PyDocs.at(record, "currency"))
        if (currency !in CURRENCY_ROLE && inputs.item(CURRENCY_ITEM[currency] ?: currency) == null) {
            // e.g. shop 13 prices in 91005, neither a known role currency nor an item template
            throw Acquisition.Rejected("Commodity currency outside the supported profile", Acquisition.ERROR_WRONG_TYPE)
        }
        val counts = shopCounts(document, now)
        val key = commodity.toString()
        val limit = PyDocs.long(PyDocs.at(record, "limit"))
        val lifetime = PyDocs.long(PyDocs.at(record, "lifetime"))
        val limited = limit > 0 || lifetime > 0
        val today = counts.obj("today")
        val total = counts.obj("total")
        if (limit > 0 && PyDocs.long(today[key] ?: JInt(0)) + quantity > dailyLimit(record, inputs.vipRow(vip).long("discount_501"))) {
            throw Acquisition.Rejected("Daily purchase limit", ERROR_DAILY_LIMIT)
        }
        if (lifetime > 0 && PyDocs.long(total[key] ?: JInt(0)) + quantity > lifetime) throw Acquisition.Rejected("Purchase limit", ERROR_TOTAL_LIMIT)
        val cost = PyDocs.long(PyDocs.at(record, "price")) * quantity
        val granted = PyDocs.long(PyDocs.at(record, "count")) * quantity
        val grantFrame = owned.grantItem(item, granted)
        val (before, after) = pay(owned, currency, cost)
        val achievement = if (currency == 90003L) diamondAchievement(owned, cost, serverTime) else emptyList()
        // Live: a daily-limited commodity counts in the today list only; the total list (lifetime limit) stayed empty.
        if (limit > 0) today[key] = JInt(PyDocs.long(today[key] ?: JInt(0)) + quantity)
        if (lifetime > 0) total[key] = JInt(PyDocs.long(total[key] ?: JInt(0)) + quantity)
        val reward = BattleReport.emptyReward()
        reward["items"] = jarr(jarr(item, granted))
        val packets = ArrayList<Frame>()
        packets.add(grantFrame)
        packets.addAll(before)
        packets.addAll(achievement)
        if (limited) packets.add(S_COUNTS to countsPayload(today, total))
        packets.add(S_BUY_REWARD to BattleReport.encodeReward(reward))
        packets.addAll(after)
        return Plan(jobj("commodity" to commodity, "quantity" to quantity, "shop_type" to record["type"], "item" to item,
            "granted" to granted, "currency" to currency, "cost" to cost, "limited" to limited, "shop_state_after" to counts,
            "reward" to reward, "evidence_class" to "capture_observed_catalog_price_x_qty"), packets)
    }

    // --- roulette ---------------------------------------------------------------------------------------------------------

    /** C641: the served wheel's coupons, `times` labeled-policy draws over the slots with an evidenced quantity, S704. */
    fun planSpin(request: JObj, owned: Owned, catalog: JObj, seed: BigInteger, now: Long, forcedSlots: List<Int>? = null): Plan {
        val activities = owned.state.obj("subsystems").obj("game_activities")
        if (PyDocs.get(activities, "roulette") == null || PyDocs.get(activities, "roulette_items") == null) {
            throw Acquisition.Rejected("This character has no roulette event in its activity list", Acquisition.ERROR_INVALID)
        }
        val roulette = activities.obj("roulette")
        val items = activities.obj("roulette_items").arr("entries").map { (PyDocs.at(it.asObj, "wire_values") as JArr)[0] }
        val wire = PyDocs.at(roulette, "wire_values") as JArr
        val classByte = wire[0]
        val start = wire[1]
        val end = wire[2]
        if (PyDocs.at(request, "class_byte") != classByte) throw Acquisition.Rejected("Roulette class differs from the served wheel")
        val times = PyDocs.long(PyDocs.at(request, "times"))
        if (times !in ROULETTE_TIMES) throw Acquisition.Rejected("Roulette times must be 1/10/100")
        if (!(PyDocs.compare(start, JInt(now)) <= 0 && PyDocs.compare(JInt(now), end) < 0)) {
            throw Acquisition.Rejected("The roulette event is outside its window", Acquisition.ERROR_INVALID)
        }
        val classIndex = PyDocs.int(classByte).subtract(BigInteger.ONE)
        // a served wheel of class 0 or above 3 is a labelled refusal (it was an internal error)
        val (coupon, perSpin) = (if (classIndex.bitLength() < 63) ROULETTE_COUPON[classIndex.toLong()] else null)
            ?: throw Acquisition.Rejected("The served wheel's class byte has no coupon (1..3)", Acquisition.ERROR_INVALID)
        val quantities = LinkedHashMap<BigInteger, JValue>()
        for ((k, v) in catalog.obj("roulette").obj("slot_quantities")) quantities[PyValues.parseInt(k)] = v
        val slotItems = catalog.obj("roulette").arr("slot_items")
        val drawable = items.indices.filter { i -> Py.truthy(quantities[BigInteger.valueOf(i.toLong())]) && slotItems[i] == items[i] }
        if (drawable.isEmpty()) throw Acquisition.Rejected("No roulette slot with an evidenced quantity")
        val packets = ArrayList<Frame>(owned.consumeTemplate(coupon, perSpin * times))
        val rng = PyRandom.seeded(seed)
        val slots = ArrayList<Int>()
        val merged = LinkedHashMap<BigInteger, BigInteger>()
        for (index in 0 until times.toInt()) {
            val slot = if (forcedSlots != null) forcedSlots[index] else rng.choice(drawable)
            slots.add(slot)
            val template = PyDocs.int(items[slot])
            val quantity = PyDocs.int(quantities.getValue(BigInteger.valueOf(slot.toLong())))
            packets.add(owned.grantItem(template.longValueExact(), quantity.longValueExact()))
            merged[template] = (merged[template] ?: BigInteger.ZERO) + quantity
        }
        val score = ROULETTE_SCORE[classIndex.toInt()] * times
        wire[3] = JInt(PyDocs.int(wire[3]) + BigInteger.valueOf(score))
        wire[4] = JInt(PyDocs.int(wire[4]) + BigInteger.valueOf(score))
        val reward = BattleReport.emptyReward()
        reward["items"] = JArr(merged.keys.sorted().mapTo(ArrayList<JValue>()) { jarr(it, merged.getValue(it)) })
        packets.add(S_ROULETTE to (WireWriter().number('B', classByte).number('I', slots.size.toLong()).bytes() +
            PyDocs.bytes(slots.map { it.toLong() }) + BattleReport.encodeReward(reward)))
        return Plan(jobj("class_byte" to classByte, "times" to times, "slots" to slots, "reward" to reward, "score" to score,
            "seed" to seed, "evidence_class" to "preservation_policy_roulette_draw"), packets)
    }

    /** `ReleaseData.catalog()`: the shop catalog with its record texts resolved from the APK text table. */
    fun releaseCatalog(data: ReleaseData): JObj {
        val document = data.document("shop-catalog.json")
        if (document.strOrNull("profile") != "acquisition_catalog_v1") throw ReleaseDataError("Unsupported shop catalog")
        val lists = document.obj("shops").values.map { (it as JObj).arr("records") } + listOf(JArr(document.obj("commodities").values.toMutableList()))
        for (records in lists) for (record in records) {
            val r = record.asObj
            r["name"] = data.resolveText(r["name"]!!)
            r["desc"] = data.resolveText(r["desc"]!!)
        }
        return document
    }
}
