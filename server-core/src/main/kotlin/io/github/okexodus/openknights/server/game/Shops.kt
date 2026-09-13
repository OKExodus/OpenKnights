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
import io.github.okexodus.openknights.exact.jobj
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
 * The parts of `shops.py` the login uses: the day rules of the device clock (`day_of`, `local_datetime`,
 * `next_day_start`), the plain per-character document tables (`read_state` / `write_state`) and the Lucky Shop view
 * (S3170). The Lucky Shop cycle is a UTC day (`now // period` from the catalog anchor); every other daily reset is the
 * device's local midnight.
 */
object Shops {
    const val LUCKY_INFO_OPCODE = 2725
    const val LUCKY_EXCHANGE_OPCODE = 2723
    const val S_LUCKY_INFO = 3170
    const val S_LUCKY_REWARD = 3172

    val LUCKY_RECORD = listOf("id" to 'I', "type" to 'B', "pool" to 'I', "c6" to 'B', "bought" to 'B', "item" to 'I',
        "qty" to 'I', "kind" to 'B', "currency" to 'I', "price" to 'I')

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
        val doc = (if (PyDocs.truthy(document)) document!! else jobj("profile" to "lucky_state_v1", "cycle" to cycle, "bought" to JArr())).deepCopy() as JObj
        if (PyDocs.at(doc, "cycle") != JInt(cycle)) {
            doc["cycle"] = JInt(cycle)
            doc["bought"] = JArr()
            doc.remove("pools")
        }
        val deadline = anchor + (cycle + 1) * period
        val remaining = maxOf(0L, deadline - now)
        val observed = lucky["observed_cycles"] as? JObj ?: JObj()
        val base: List<JValue> = when {
            PyDocs.truthy(doc["pools"]) -> poolEntries(lucky, doc.arr("pools"))
            cycle.toString() in observed -> poolEntries(lucky, observed.arr(cycle.toString()))
            poolPolicy && cycle >= 0 && PyDocs.truthy(lucky["pools"]) -> poolEntries(lucky, drawLuckyPools(lucky, cycleSeed(cycle)).map { JInt(it) })
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
