package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.PyRandom
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import java.math.BigInteger
import java.security.MessageDigest
import java.util.WeakHashMap

/**
 * The login parts of `rebirth_shop.py` (the Event Hall Shop — Sprite / Fame / Jewel): the lists of a new local day
 * (or the first ones, from the seed S3904) rolled from shop_position / shop_refresh with a draw seeded per character,
 * day and shop, and the S3904 list payload.
 */
object RebirthShop {
    const val S_LIST = 3904
    val SHOPS = listOf(13L, 14L, 15L)
    const val POSITIONS = 6
    const val ROLE_VIP = 27L
    const val KIND_ITEM = 1L
    const val KIND_HERO = 2L
    const val KIND_GEAR = 3L
    const val KIND_JEWEL = 9L
    /** POLICY: the chance (per 10,000) that a drawn good is priced in Diamonds, per goods kind. */
    val DIAMOND_RATE = mapOf(KIND_ITEM to 2229L, KIND_HERO to 9722L, KIND_GEAR to 4318L, KIND_JEWEL to 1500L)
    const val PROFILE = "rebirth_shop_v1"

    private fun n(value: String?, default: Long = 0): Long = PyValues.digitInt(value, default)

    /** `random.Random` seeded with the first 8 bytes (little-endian) of SHA-256 of the parts joined by `|`. */
    fun rng(vararg parts: Any): PyRandom {
        val text = parts.joinToString("|") { it.toString() }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return PyRandom.seeded(BigInteger(1, digest.copyOfRange(0, 8).reversedArray()))
    }

    /** {position: [(threshold, slot key), …]} of one shop. */
    private fun positions(inputs: DailyInputs, shop: Long): Map<Long, List<Pair<Long, Long>>> {
        val out = LinkedHashMap<Long, MutableList<Pair<Long, Long>>>()
        for (f in inputs.tableRows("shop_position")) {
            if (n(f.field("102")) == shop) out.getOrPut(n(f.field("103"))) { ArrayList() }.add(n(f.field("202")) to n(f.field("201")))
        }
        return out
    }

    private val goodsCache = WeakHashMap<DailyInputs, LinkedHashMap<Long, JObj>>()

    private fun goods(inputs: DailyInputs): LinkedHashMap<Long, JObj> = synchronized(goodsCache) {
        goodsCache.getOrPut(inputs) {
            val cache = LinkedHashMap<Long, JObj>()
            for (f in inputs.tableRows("shop_refresh")) {
                cache[n(f.field("101"))] = jobj("id" to n(f.field("101")), "kind" to n(f.field("103")), "goods" to n(f.field("104")),
                    "slot" to n(f.field("105")), "count" to n(f.field("106")), "currency" to n(f.field("108")), "price" to n(f.field("109")),
                    "diamond_currency" to n(f.field("111")), "diamond_price" to n(f.field("112")), "weight" to n(f.field("113")))
            }
            cache
        }
    }

    fun good(inputs: DailyInputs, rowId: Long): JObj? = goods(inputs)[rowId]

    private fun slotGoods(inputs: DailyInputs, slot: Long): List<JObj> = goods(inputs).values.filter { it.long("slot") == slot }

    fun vipLevel(state: JObj): JValue {
        for (f in state.arr("role_properties")) {
            val field = f.asObj
            if (field["id"] == JInt(ROLE_VIP)) {
                val bits = field.obj("value")["bits"] ?: JInt(0)
                return if (PyDocs.truthy(bits)) bits else JInt(0)
            }
        }
        return JInt(0)
    }

    private val PAIR_ORDER = compareBy<Pair<Long, Long>> { it.first }.thenBy { it.second }

    /** Six entries `[row id, sold 0, diamond flag]` in position order. */
    fun rollShop(inputs: DailyInputs, shop: Long, vip: JValue, rng: PyRandom): JArr {
        val entries = JArr()
        for ((_, variants) in positions(inputs, shop).entries.sortedBy { it.key }) {
            val eligible = variants.filter { PyDocs.compare(JInt(it.first), vip) <= 0 }.ifEmpty { listOf(variants.minWith(PAIR_ORDER)) }
            val slot = eligible.maxWith(PAIR_ORDER).second
            val goods = slotGoods(inputs, slot).filter { it.long("weight") > 0 }
            if (goods.isEmpty()) throw Acquisition.Rejected("No goods for shop $shop slot $slot")
            val picked = rng.choices(goods, goods.map { it.long("weight") })[0]
            val diamond = if (rng.randrange(10000) < (DIAMOND_RATE[picked.long("kind")] ?: 0L)) 1 else 0
            entries.add(jarr(picked.long("id"), 0, diamond))
        }
        return entries
    }

    /** S3904 decode: `u8 n; n × (u32 shop, u8 m, m × (u32 id, u8 sold, u8 diamond), u32 used); u32 cd` (exact length). */
    fun decodeList(payload: ByteArray): Pair<JObj, Long> {
        val n = payload[0].toInt() and 0xFF
        val r = WireReader(payload).also { it.offset = 1 }
        val shops = JObj()
        repeat(n) {
            val shop = r.u32()
            val m = r.u8()
            val entries = JArr()
            repeat(m) { entries.add(r.values("IBB")) }
            val used = r.number('I')
            shops[shop.toString()] = jobj("entries" to entries, "used" to used)
        }
        val at = r.offset
        val cd = r.u32()
        if (at + 4 != payload.size) throw PyValues.ValueError("S3904 has trailing bytes")
        return shops to cd
    }

    /** S3904: `u8 3`, per shop 13 / 14 / 15 `u32 shop, u8 n, n × (u32, u8, u8), u32 used`, then the countdown to the next
     * local midnight. */
    fun listPayload(document: JObj, now: Long): ByteArray {
        val w = WireWriter().raw(PyDocs.bytes(listOf(SHOPS.size.toLong())))
        for (shop in SHOPS) {
            val entry = PyDocs.at(document.obj("shops"), shop.toString()) as JObj
            val entries = entry.arr("entries")
            w.number('I', shop).number('B', entries.size.toLong())
            for (e in entries) w.values("IBB", e.asArr)
            w.number('I', PyDocs.at(entry, "used"))
        }
        return w.number('I', maxOf(0L, Shops.nextDayStart(now) - now)).bytes()
    }

    private fun validSeed(shops: JObj, inputs: DailyInputs): Boolean =
        shops.keys == SHOPS.map { it.toString() }.toSet() && shops.values.all { v ->
            val entries = (v as JObj).arr("entries")
            entries.size == POSITIONS && entries.all { e -> PyDocs.truthy(good(inputs, PyDocs.long(e.asArr[0]))) }
        }

    /**
     * (document, changed): the stored lists of today; a new local day (or no document) rolls all three shops; the first
     * document of a character starts from its seed S3904 when that list is valid.
     */
    fun view(document: JObj?, inputs: DailyInputs, state: JObj, now: Long, ownerKey: String, seedPayload: ByteArray? = null,
             seedProvenance: JObj? = null): Pair<JObj, Boolean> {
        val today = Shops.dayOf(now)
        if (document != null && PyDocs.get(document, "day") == JStr(today)) return document to false
        val vip = vipLevel(state)
        var shops: JObj? = null
        if (document == null && seedPayload != null) {
            try {
                val (decoded, _) = decodeList(seedPayload)
                val out = JObj()
                for ((k, v) in decoded) out[k] = jobj("entries" to (v as JObj)["entries"], "used" to 0)
                shops = if (validSeed(out, inputs)) out else null
            } catch (e: IllegalArgumentException) {
                shops = null
            } catch (e: IndexOutOfBoundsException) {
                shops = null
            }
        }
        val source = if (shops != null) JObj().also { s -> s["source"] = JStr("seed"); seedProvenance?.forEach { (k, v) -> s[k] = v } }
            else jobj("source" to "table_roll")
        if (shops == null) {
            shops = JObj()
            for (shop in SHOPS) shops[shop.toString()] = jobj("entries" to rollShop(inputs, shop, vip, rng(ownerKey, today, shop, "day")), "used" to 0)
        }
        return jobj("profile" to PROFILE, "day" to today, "vip" to vip, "shops" to shops, "list_source" to source) to true
    }
}
