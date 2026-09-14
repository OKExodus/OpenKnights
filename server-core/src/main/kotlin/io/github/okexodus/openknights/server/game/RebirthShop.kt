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
import io.github.okexodus.openknights.protocol.BattleReport
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
    const val C_BUY = 3937
    const val C_REFRESH = 3939
    const val C_TIMER = 3941
    const val S_LIST = 3904
    const val S_REWARD = 3906
    val SHOPS = listOf(13L, 14L, 15L)
    const val POSITIONS = 6
    val SOUL_ROLE = mapOf(91010L to 32L, 91011L to 33L, 91012L to 34L)
    const val DIAMOND_CURRENCY = 90003L
    val FREE_REFRESH = mapOf(13L to 600001L, 14L to 600002L, 15L to 600003L)
    val REFRESH_PRICE = mapOf(13L to 600008L, 14L to 600009L, 15L to 600010L)
    val VIP_REFRESH_COLUMN = mapOf(13L to "307", 14L to "308", 15L to "309")
    const val ROLE_VIP = 27L
    const val KIND_ITEM = 1L
    const val KIND_HERO = 2L
    const val KIND_GEAR = 3L
    const val KIND_JEWEL = 9L
    /** POLICY: the chance (per 10,000) that a drawn good is priced in Diamonds, per goods kind. */
    val DIAMOND_RATE = mapOf(KIND_ITEM to 2229L, KIND_HERO to 9722L, KIND_GEAR to 4318L, KIND_JEWEL to 1500L)
    const val PROFILE = "rebirth_shop_v1"
    const val ERROR_RESOURCES = 4000
    const val ERROR_SOLD = 102          // POLICY: no native text found for "already bought"

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
                return if (Py.truthy(bits)) bits else JInt(0)
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
            entries.size == POSITIONS && entries.all { e -> Py.truthy(good(inputs, PyDocs.long(e.asArr[0]))) }
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

    // --- requests ---------------------------------------------------------------------------------------------------------

    /** `refresh_limit(inputs, shop, vip)`: the viplv row's per-day refresh count for the shop (0 when no matching row). */
    fun refreshLimit(inputs: DailyInputs, shop: Long, vip: JValue): Long {
        for (f in inputs.tableRows("viplv")) {
            if (PyDocs.compare(JInt(n(f.field("102"), -1)), vip) == 0) return n(f.field(VIP_REFRESH_COLUMN.getValue(shop)))
        }
        return 0
    }

    /** `decode_request(opcode, payload)`: C3939 `u32 shop` / C3937 `u32 shop, u32 position` (1-based). */
    fun decodeRequest(opcode: Int, payload: ByteArray): JObj {
        val size = if (opcode == C_BUY) 8 else 4
        if (payload.size != size) throw Acquisition.Rejected("C$opcode carries ${size / 4} u32")
        val r = WireReader(payload)
        val values = (0 until size / 4).map { r.u32() }
        if (values[0] !in SHOPS) throw Acquisition.Rejected("No such shop")
        if (opcode == C_BUY && !(values[1] in 1L..POSITIONS.toLong())) throw Acquisition.Rejected("No such shop position")
        return if (opcode == C_BUY) jobj("shop" to values[0], "position" to values[1]) else jobj("shop" to values[0])
    }

    /**
     * `plan_refresh(request, owned, document, inputs, now, rng)`: C3939 re-rolls one shop, `used` + 1; the first five a
     * day free, then 20 Diamonds; at the VIP maximum refused. Reply: [S128 Diamond + Diamond-spend ladders] + S3904.
     */
    fun planRefresh(request: JObj, owned: Owned, document: JObj, inputs: DailyInputs, now: Long, rng: PyRandom): Plan {
        val shop = request.long("shop")
        val entry = document.obj("shops").obj(shop.toString())
        val vip = vipLevel(owned.state)
        if (entry.long("used") >= refreshLimit(inputs, shop, vip)) throw Acquisition.Rejected("All refresh has been used", 102)
        val frames = ArrayList<Frame>()
        var after: List<Frame> = emptyList()
        var price = 0L
        if (entry.long("used") >= inputs.prop(FREE_REFRESH.getValue(shop), 5)) {
            price = inputs.prop(REFRESH_PRICE.getValue(shop), 20)
            frames.add(owned.roleAdd(Acquisition.DIAMOND, -price))
            after = Shops.diamondAchievement(owned, price, null)
        }
        entry["entries"] = rollShop(inputs, shop, vip, rng)
        entry["used"] = JInt(entry.long("used") + 1)
        document["vip"] = vip
        return Plan(jobj("shop" to shop, "refreshes_used" to entry.long("used"), "diamond_price" to price,
            "rebirth_shop_after" to document, "evidence_class" to "native_use_table_policy_draw"),
            frames + listOf(S_LIST to listPayload(document, now)) + after)
    }

    /**
     * `plan_buy(request, owned, document, inputs, now, jewels)`: C3937 buys the good at the position, paid in soul
     * currency or (Diamond-flagged) Diamonds; granted by kind; marked sold. Reply: cost S128, grants, S3906, S3904.
     */
    fun planBuy(request: JObj, owned: Owned, document: JObj, inputs: DailyInputs, now: Long, jewels: JArr?): Plan {
        val shop = request.long("shop")
        val position = request.long("position")
        val entry = document.obj("shops").obj(shop.toString())
        if (position > entry.arr("entries").size) throw Acquisition.Rejected("No such shop position")
        val e = entry.arr("entries")[(position - 1).toInt()].asArr
        val rowId = e[0].long
        val sold = e[1].long
        val diamond = e[2].long
        if (sold != 0L) throw Acquisition.Rejected("Already bought", ERROR_SOLD)
        val item = good(inputs, rowId) ?: throw Acquisition.Rejected("Unknown shop good")
        val frames = ArrayList<Frame>()
        var after: List<Frame> = emptyList()
        if (diamond != 0L) {
            frames.add(owned.roleAdd(Acquisition.DIAMOND, -item.long("diamond_price")))
            after = Shops.diamondAchievement(owned, item.long("diamond_price"), null)
        } else {
            val role = SOUL_ROLE[item.long("currency")] ?: throw Acquisition.Rejected("Unknown shop currency")
            frames.add(owned.roleAdd(role, -item.long("price")))
        }
        val reward = Acquisition.emptyReward()
        val kind = item.long("kind")
        val goods = item.long("goods")
        val count = maxOf(1L, item.long("count"))
        val planExtra = LinkedHashMap<String, JValue>()
        when (kind) {
            KIND_ITEM -> {
                frames.add(owned.grantItem(goods, count))
                reward.arr("items").add(jarr(goods, count))
            }
            KIND_HERO -> {
                for (k in 0 until count) {
                    val groups = owned.grantHero(goods).second
                    frames += groups.getValue("add") + groups.getValue("book") + groups.getValue("god") + groups.getValue("activity")
                }
                for (k in 0 until count) reward.arr("heroes").add(jarr(goods))
            }
            KIND_GEAR -> {
                for (k in 0 until count) {
                    val groups = owned.grantEquipment(goods).second
                    frames += groups.getValue("add") + groups.getValue("book")
                }
                reward.arr("equips").add(jarr(goods))
            }
            KIND_JEWEL -> {
                if (jewels == null) throw Acquisition.Rejected("No unequipped-jewelry list for this purchase")
                frames += EventHall.grantJewels(owned, goods, count, jewels)
                for (k in 0 until count) reward.arr("jewels").add(jarr(goods))
                planExtra["jewel_entries_after"] = JArr(jewels.sortedBy { it.asObj.arr("record")[0].long }.toMutableList())
            }
            else -> throw Acquisition.Rejected("This good's kind is not served")
        }
        e[1] = JInt(1)
        val out = frames + listOf(S_REWARD to BattleReport.encodeReward(reward), S_LIST to listPayload(document, now)) + after
        val data = jobj("shop" to shop, "position" to position, "good" to rowId, "kind" to kind, "goods" to goods, "count" to count,
            "paid_in_diamonds" to (diamond != 0L), "rebirth_shop_after" to document, "evidence_class" to "native_use_table_policy_order")
        for ((k, v) in planExtra) data[k] = v
        return Plan(data, out)
    }
}
