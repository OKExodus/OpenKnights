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
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.store.StateStore

/**
 * The group-1 parts of `sweep_features.py`: the startup-burst album (S548) and totem (S2880) frames with their
 * documents, the after-query S3904 (today's Event Hall shop lists, else the stop-gap), the temporary VIP4 view
 * (S1824) and the Warehouse capacity login repair. The state-changing sweep requests (C77, C2529, C513, C25, C577,
 * C1649, the Event Hall shop) and the stateless replies belong to the sweep group; the store already lists this
 * module's actions and documents (`register_store_documents` is a no-op there).
 */
object SweepFeatures {
    const val C_ALBUM_INFO = 515
    const val S_ALBUM = 548
    const val C_REBIRTH_SHOP_TIMER = 3941
    const val S_REBIRTH_SHOP = 3904
    const val C_WAREHOUSE_SLOT = 77
    const val C_TOTEM_LINEUP = 2529
    const val S_TOTEM_INIT = 2880
    const val S_TOTEM_LINEUP = 2884
    const val S_EVENT_UPDATE = 1760
    const val S_ROLE = 128
    const val C_TMP_VIP = 25
    const val S_TMP_VIP = 1824
    const val TOTEM_PROFILE = "totem_state_v1"
    const val ALBUM_PROFILE = "album_state_v1"
    const val REBIRTH_SHOP_WINDOW = 600007

    /** castle.py: the Warehouse building id and the S72 capacity reply. */
    const val WAREHOUSE = 6L
    const val S_ITEM_CAPACITY = 72

    /** The request changes nothing: reply with these frames and write no revision. */
    class Unchanged(packets: List<Frame>, fields: JObj? = null) : Exception("unchanged") {
        val packets: List<Frame> = ArrayList(packets)
        val fields: JObj = fields ?: JObj()
    }

    /** `_prop(inputs, key, default)`: a property through the daily inputs' number rule (null inputs → default). */
    fun prop(inputs: AcquisitionInputs?, key: Long, default: Long): Long {
        if (inputs == null) return default
        if (inputs is DailyInputs) return inputs.prop(key, default)
        return PyValues.digitInt(inputs.property(key) ?: "", default)
    }

    fun empty(payload: ByteArray, opcode: Int) {
        if (payload.isNotEmpty()) throw Acquisition.Rejected("C$opcode carries no payload")
    }

    private fun documentOf(current: StateStore.Current?, table: String): JObj? = current?.document(table) as? JObj

    // === Album ======================================================================================================

    /** S548 `u32 n, n × u32 family id`. */
    fun decodeAlbum(payload: ByteArray): List<Long> {
        if (payload.size < 4) throw PyValues.ValueError("S548 too short")
        val reader = WireReader(payload)
        val n = reader.u32()
        if (payload.size.toLong() != 4 + 4 * n) throw PyValues.ValueError("S548 length does not match its count")
        return (0 until n).map { reader.u32() }
    }

    fun albumPayload(families: List<JValue>): ByteArray {
        val w = WireWriter().u32(families.size.toLong())
        families.forEach { w.number('I', it) }
        return w.bytes()
    }

    /**
     * `album_frame(seeds, current)`: (S548 payload, provenance) of the activated album families: the stored
     * `album_state` first, else the seed frame served as it is (after a layout check), else the empty set.
     */
    fun albumFrame(seeds: SystemSeeds.SeedFrames?, current: StateStore.Current? = null): Pair<ByteArray, JObj> {
        val stored = documentOf(current, "album_state")
        if (stored != null) return albumPayload(stored.arr("families")) to jobj("source" to "album_state")
        val payload = seeds?.first(S_ALBUM) ?: return albumPayload(emptyList()) to jobj("source" to "empty_set_policy")
        albumPayload(decodeAlbum(payload).map { JInt(it) })          // layout check; the seed bytes are served as they are
        return payload to seeds.provenance(S_ALBUM)
    }

    fun albumDocument(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?): JObj {
        documentOf(current, "album_state")?.let { return it.deepCopy() }
        val (payload, provenance) = albumFrame(seeds)
        return jobj("profile" to ALBUM_PROFILE, "families" to decodeAlbum(payload).toSortedSet().toList(), "seed" to provenance)
    }

    // === Totems =====================================================================================================

    /** S2880 `u8 n; n × {u32 id, u8 grade, u32 level, u64 exp}; u32 lineup id` → {"totems", "lineup"}. */
    fun decodeTotems(payload: ByteArray): JObj {
        if (payload.isEmpty()) throw PyValues.ValueError("S2880 is empty")
        val n = payload[0].toInt() and 0xFF
        if (payload.size != 1 + 17 * n + 4) throw PyValues.ValueError("S2880 length does not match its count")
        val reader = WireReader(payload).also { it.offset = 1 }
        val totems = JArr()
        repeat(n) { totems.add(jarr(reader.u32(), reader.u8(), reader.u32(), reader.u64())) }
        return jobj("totems" to totems, "lineup" to reader.u32())
    }

    fun totemPayload(document: JObj): ByteArray {
        val totems = document.arr("totems")
        if (totems.size > 255) throw PyValues.ValueError("At most 255 totems (u8 count)")
        val w = WireWriter().u8(totems.size)
        for (t in totems) w.values("IBIQ", t.asArr)
        w.number('I', document.getValue("lineup"))
        return w.bytes()
    }

    /** `totem_document(current, seeds)`: the stored `totem_state`, else seeded from the seed S2880, else empty. */
    fun totemDocument(current: StateStore.Current?, seeds: SystemSeeds.SeedFrames?): JObj {
        documentOf(current, "totem_state")?.let { return it.deepCopy() }
        val payload = seeds?.first(S_TOTEM_INIT)
            ?: return jobj("profile" to TOTEM_PROFILE, "totems" to JArr(), "lineup" to 0, "seed" to jobj("source" to "empty_set_policy"))
        val out = jobj("profile" to TOTEM_PROFILE)
        out.putAll(decodeTotems(payload))
        out["seed"] = seeds.provenance(S_TOTEM_INIT)
        return out
    }

    // === Login frames ===============================================================================================

    /** The startup-burst pair of [startupFrames] and its provenance. */
    class StartupFrames(val totems: ByteArray, val album: ByteArray, val provenance: JObj)

    /** `startup_frames(current, seeds)`: (S2880, S548) for the startup burst. */
    fun startupFrames(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?): StartupFrames {
        val (album, albumSource) = albumFrame(seeds, current)
        val totems = totemDocument(current, seeds)
        return StartupFrames(totemPayload(totems), album, jobj("album" to albumSource, "totems" to (totems["seed"] ?: JNull),
            "totem_state" to (documentOf(current, "totem_state") != null)))
    }

    /** Insert the two frames where live has them: around S2848; without S2848, before S2976 in the same order. */
    fun placeStartupFrames(packets: List<Frame>, totems: ByteArray, album: ByteArray): List<Frame> {
        val out = ArrayList(packets)
        val godIndex = out.indexOfFirst { it.first == 2848 }
        if (godIndex >= 0) {
            val god = out[godIndex]
            out.removeAt(godIndex)
            out.addAll(godIndex, listOf(S_TOTEM_INIT to totems, god, S_ALBUM to album))
            return out
        }
        val index = out.indexOfFirst { it.first == 2976 }.let { if (it < 0) out.size else it }
        out.addAll(index, listOf(S_TOTEM_INIT to totems, S_ALBUM to album))
        return out
    }

    /** S3904 `u8 n = 0, u32 refresh_cd` with cd = property 600007 (a fresh character's first window), the stop-gap. */
    fun rebirthShopStopgap(inputs: AcquisitionInputs?): ByteArray =
        WireWriter().u8(0).number('I', prop(inputs, REBIRTH_SHOP_WINDOW.toLong(), 86400)).bytes()

    /**
     * `after_query_frames(inputs, current, now)`: the S3904 of the login burst — the character's Event Hall Shop lists
     * of today (stored by the login refresh), else the empty stop-gap. Today is the device clock's local day.
     */
    fun afterQueryFrames(inputs: AcquisitionInputs?, current: StateStore.Current? = null, now: Long? = null): List<Frame> {
        val document = documentOf(current, "rebirth_shop")
        if (document != null && now != null && document["day"] == JStr(dayOf(now))) {
            return listOf(S_REBIRTH_SHOP to rebirthListPayload(document, now))
        }
        return listOf(S_REBIRTH_SHOP to rebirthShopStopgap(inputs))
    }

    // === Temporary VIP4 =============================================================================================

    const val TMP_VIP_UNCLAIMED = 0
    const val TMP_VIP_ACTIVE = 1
    const val TMP_VIP_EXPIRED = 2
    const val TMP_VIP_LEVEL = 4L
    const val TMP_VIP_SECONDS = 4L * 86400
    const val TMP_VIP_PROFILE = "tmp_vip_v1"
    const val ROLE_VIP_LEVEL = 27L

    /** S1824 `u8 state, u32 seconds` (seconds clamped to 0..0x7fffffff: StartCD takes an int). */
    fun tmpVipPayload(state: Int, seconds: Long = 0): ByteArray =
        WireWriter().u8(state).u32(maxOf(0L, minOf(seconds, 0x7fffffffL))).bytes()

    fun decodeTmpVip(payload: ByteArray): Pair<Int, Long> {
        if (payload.size != 5) throw PyValues.ValueError("S1824 is u8 state, u32 seconds")
        val r = WireReader(payload)
        return r.u8() to r.u32()
    }

    /**
     * `tmp_vip_document(current, seeds)`: the stored `tmp_vip`, else seeded from the seed S1824 (a claimed seed counts as
     * claimed and expired), else unclaimed.
     */
    fun tmpVipDocument(current: StateStore.Current?, seeds: SystemSeeds.SeedFrames?): JObj {
        documentOf(current, "tmp_vip")?.let { return it.deepCopy() }
        val payload = seeds?.first(S_TMP_VIP)
        val document = jobj("profile" to TMP_VIP_PROFILE, "claimed" to false, "claimed_at" to null, "expires_at" to null)
        if (payload == null) return document.also { it["seed"] = jobj("source" to "unclaimed_default_policy") }
        val (state, _) = decodeTmpVip(payload)
        if (state == TMP_VIP_ACTIVE || state == TMP_VIP_EXPIRED) {
            document["claimed"] = JBool(true)
            document["expires_at"] = JInt(0)
        }
        val seed = seeds.provenance(S_TMP_VIP)
        seed["state"] = JInt(state)
        document["seed"] = seed
        return document
    }

    /** The character's real VIP level (role 27). */
    fun realVipLevel(current: StateStore.Current?): Long {
        val props = current?.state?.get("role_properties") as? JArr ?: return 0
        for (f in props) {
            val field = f.asObj
            if ((field["id"] as? JInt)?.value?.toLong() == ROLE_VIP_LEVEL) {
                return (field.obj("value")["bits"] as? JInt)?.value?.toLong() ?: 0L
            }
        }
        return 0
    }

    /**
     * `tmp_vip_view(document, now, vip_level)`: (state, seconds) on the device clock: VIP 4+ → (2, 0); unclaimed →
     * (0, 0); claimed and before its end → (1, seconds left, at most the duration); else (2, 0).
     */
    fun tmpVipView(document: JObj, now: Long, vipLevel: Long = 0): Pair<Int, Long> {
        if (vipLevel >= TMP_VIP_LEVEL) return TMP_VIP_EXPIRED to 0L
        if (!HeroStats.truthy(document["claimed"])) return TMP_VIP_UNCLAIMED to 0L
        val left = ((document["expires_at"] as? JInt)?.value?.toLong()?.takeIf { it != 0L } ?: 0L) - now
        if (left > 0) return TMP_VIP_ACTIVE to minOf(left, (document["duration_seconds"] as? JInt)?.value?.toLong() ?: TMP_VIP_SECONDS)
        return TMP_VIP_EXPIRED to 0L
    }

    /** The login S1824. */
    fun tmpVipFrame(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, now: Long): Frame {
        val (state, seconds) = tmpVipView(tmpVipDocument(current, seeds), now, realVipLevel(current))
        return S_TMP_VIP to tmpVipPayload(state, seconds)
    }

    /**
     * `tmp_vip_level(current, vip_level, now, seeds)`: max(vip, 4) while the temporary VIP is active (the reference
     * reads the view without the real VIP level here). `now` defaults to the device clock.
     */
    fun tmpVipLevel(current: StateStore.Current?, vipLevel: Long, now: Long? = null, seeds: SystemSeeds.SeedFrames? = null): Long {
        val (state, _) = tmpVipView(tmpVipDocument(current, seeds), now ?: deviceClock().now())
        return if (state == TMP_VIP_ACTIVE) maxOf(vipLevel, TMP_VIP_LEVEL) else vipLevel
    }

    /** A purchase that lifts the real VIP level to 4 or more ends a running temporary VIP4 at once. */
    fun tmpVipEndFrames(current: StateStore.Current?, levelBefore: Long, levelAfter: Long): List<Frame> {
        if (levelBefore >= TMP_VIP_LEVEL || levelAfter < TMP_VIP_LEVEL) return emptyList()
        return listOf(S_TMP_VIP to tmpVipPayload(TMP_VIP_EXPIRED, 0))
    }

    // === Warehouse ==================================================================================================

    fun warehouseMaxLevel(inputs: AcquisitionInputs): Long {
        val row = if (inputs is DailyInputs) inputs.building(WAREHOUSE) else null
        return (row?.get("max_level") as? JInt)?.value?.toLong()?.takeIf { it != 0L } ?: 200L
    }

    /** The capacities to open: always the maximum Warehouse level's limit; null when the save has no Warehouse. */
    fun openedLimit(state: JObj, inputs: AcquisitionInputs): List<Long>? {
        if (WAREHOUSE !in buildingLevels(state)) return null
        return warehouseCapacity(warehouseMaxLevel(inputs), inputs)
    }

    /** Warehouse building → its maximum level; (before, after) or null when already there. */
    fun raiseWarehouse(state: JObj, inputs: AcquisitionInputs): Pair<Long, Long>? {
        val level = buildingLevels(state)[WAREHOUSE]
        val top = warehouseMaxLevel(inputs)
        if (level == null || level >= top) return null
        setBuilding(state, WAREHOUSE, top)
        return level to top
    }

    /**
     * `plan_warehouse_slot(payload, owned, inputs)`: capacities below the Warehouse limit raised to it (never
     * lowered) and the Warehouse building raised to its maximum level; S640 (when raised) + S72. Nothing to change →
     * [Unchanged] with the S72.
     */
    fun planWarehouseSlot(payload: ByteArray, owned: Owned, inputs: AcquisitionInputs): Plan {
        empty(payload, C_WAREHOUSE_SLOT)
        val state = owned.state
        val before = ((state["item_capacity_values"] as? JArr) ?: JArr()).map { it.long }
        if (before.size != 3) throw Acquisition.Rejected("The save carries no bag capacities")
        val level = buildingLevels(state)[WAREHOUSE]
        val limit = openedLimit(state, inputs)
        val after = if (limit == null) before else before.zip(limit).map { (b, cap) -> maxOf(b, cap) }
        val raised = raiseWarehouse(state, inputs)
        val reply = ArrayList<Frame>()
        reply.add(S_ITEM_CAPACITY to itemCapacityPayload(after))
        if (raised != null) reply.add(0, 640 to WireWriter().u8(WAREHOUSE.toInt()).number('H', raised.second).bytes())
        if (after == before && raised == null) {
            throw Unchanged(reply, jobj("capacities" to after, "warehouse_level" to level))
        }
        state["item_capacity_values"] = JArr(after.mapTo(ArrayList()) { JInt(it) })
        return Plan(jobj("warehouse_level" to level, "warehouse_level_after" to (raised?.second ?: level),
            "item_capacity_before" to before, "item_capacity_after" to after,
            "evidence_class" to "native_use_formula_capture_calculation_policy_cost"), reply)
    }

    /** Login repair: saves whose bags are below the Warehouse limit, or whose Warehouse is below its maximum level. */
    fun capacityRepairNeeded(state: JObj, inputs: AcquisitionInputs): Boolean {
        val before = ((state["item_capacity_values"] as? JArr) ?: JArr()).map { it.long }
        val limit = openedLimit(state, inputs)
        if (before.size != 3 || limit == null) return false
        return before.zip(limit).any { (b, cap) -> b < cap } || (buildingLevels(state)[WAREHOUSE] ?: 0L) < warehouseMaxLevel(inputs)
    }

    /** The same raise as C77, without a frame (the login S18 carries the triple); [Unchanged] when nothing is below. */
    fun planCapacityRepair(owned: Owned, inputs: AcquisitionInputs): Plan {
        val plan = planWarehouseSlot(ByteArray(0), owned, inputs)
        plan.packets = emptyList()
        plan["evidence_class"] = "native_use_formula_login_repair_policy"
        return plan
    }

    // --- helpers of other modules this one calls (castle.py, rebirth_shop.py / shops.py) ---------------------------

    /** `castle.building_levels(state)`: {building id: level} of the S18 buildings (a later entry wins). */
    fun buildingLevels(state: JObj): Map<Long, Long> {
        val out = LinkedHashMap<Long, Long>()
        for (e in state.obj("subsystems").obj("buildings").arr("entries")) {
            val wire = e.asObj.arr("wire_values")
            out[wire[0].long] = wire[1].long
        }
        return out
    }

    /** `castle._set_building(state, ident, level)`. */
    fun setBuilding(state: JObj, ident: Long, level: Long) {
        val section = state.obj("subsystems").obj("buildings")
        for (e in section.arr("entries")) {
            val wire = e.asObj.arr("wire_values")
            if (wire[0].long == ident) { wire[1] = JInt(level); return }
        }
        val entries = section.arr("entries")
        entries.add(jobj("wire_values" to jarr(ident, level)))
        val sorted = entries.sortedBy { it.asObj.arr("wire_values")[0].long }
        entries.clear(); entries.addAll(sorted)
        section["count"] = JInt(entries.size)
    }

    /** `castle.warehouse_capacity(level, inputs)`: [P3 + L, P3 + L // 2, P3 + L // 2] with P3 = property 3 (default 50). */
    fun warehouseCapacity(level: Long, inputs: AcquisitionInputs): List<Long> {
        val base = (inputs as DailyInputs).prop(3, 50)
        return listOf(base + level, base + Math.floorDiv(level, 2L), base + Math.floorDiv(level, 2L))
    }

    /** `castle.item_capacity_payload(values)`: S72 `u16 items, u16 fragments, u16 blueprints`. */
    fun itemCapacityPayload(values: List<Long>): ByteArray {
        if (values.size != 3) throw io.github.okexodus.openknights.protocol.ProtocolException("pack expected 3 items for packing (got ${values.size})")
        val w = WireWriter()
        values.forEach { w.number('H', it) }
        return w.bytes()
    }

    private fun deviceClock(): DeviceClock = DeviceClock.active ?: throw IllegalStateException("The device clock is not installed")

    /** `shops.day_of(now)` of the release service: the device clock's local day. */
    private fun dayOf(now: Long): String = deviceClock().localDay(now)

    /** `rebirth_shop.list_payload(document, now)`: the three shops' lists and the countdown to the next local day. */
    private fun rebirthListPayload(document: JObj, now: Long): ByteArray {
        val shops = listOf(13L, 14L, 15L)
        val w = WireWriter().u8(shops.size)
        for (shop in shops) {
            val entry = document.obj("shops").obj(shop.toString())
            val entries = entry.arr("entries")
            w.u32(shop).u8(entries.size)
            for (e in entries) w.values("IBB", e.asArr)
            w.number('I', entry.getValue("used"))
        }
        w.u32(maxOf(0L, deviceClock().nextDayStart(now) - now))
        return w.bytes()
    }
}
