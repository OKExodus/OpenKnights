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
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.jvalue
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.gamedata.GameTable
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.TypedValues
import io.github.okexodus.openknights.protocol.Utf8
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
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

    // --- state-changing / stateless sweep requests (group 8) --------------------------------------------------------
    const val C_YKHD_OLD_CARD = 1671
    const val C_LEVEL_GIFT = 1633
    const val C_SIGNATURE = 577
    const val C_GREAT_OFFER = 1649
    const val C_REBIRTH_SHOP_REFRESH = 3939
    const val C_REBIRTH_SHOP_BUY = 3937
    const val C_ALBUM_ACTIVATE = 513
    const val S_ALBUM_REWARD = 550
    const val ERROR_NOT_ACTIVATED = 53000
    const val ROLE_SIGNATURE = 21L
    const val TEXT_TAG = 0x61
    const val SIGNATURE_MAX_BYTES = 64

    // Roulette rank (C643): its lists were always empty; the request moved to another module, but `stateless_reply`
    // keeps its constants + branch. Not in STATELESS, so unreachable through the sweep route (dead branch here).
    const val C_ROULETTE_RANK = 643
    const val S_ROULETTE_RANK = 706

    // Album activation (C513) refusal codes + the collection section per tujian kind.
    const val ERROR_NO_ALBUM = 59001
    const val ERROR_ALBUM_CLAIMED = 17001
    const val ERROR_ALBUM_INCOMPLETE = 17000
    val ALBUM_SECTIONS: Map<Long, String> = mapOf(1L to "hero_collection", 2L to "equip_collection", 3L to "jewelry_collection")

    /**
     * The closed special-event S1760 frames served instead of S6 102 (none reachable with today's closed state): DLLJ
     * log-in gift (type 3), YXJJ Hero Pool buy / daily claim (type 6), CZFL Rebate / refresh (type 8).
     */
    val CLOSED_EVENT_FRAMES: Map<Int, ByteArray> = linkedMapOf(
        1639 to byteArrayOf(3, 0, 0, 0, 0, 2),
        1645 to byteArrayOf(6, 0), 1647 to byteArrayOf(6, 0),
        1651 to byteArrayOf(8, 0), 1667 to byteArrayOf(8, 0))

    /** Requests that read and change nothing (the reply is the event's closed frame). */
    val STATELESS: Set<Int> = linkedSetOf(C_YKHD_OLD_CARD, C_LEVEL_GIFT) + CLOSED_EVENT_FRAMES.keys

    /** Opcode → action of the state-changing sweep requests (`ACTIONS`, reference order). */
    val ACTIONS: Map<Int, String> = linkedMapOf(
        C_SIGNATURE to "set_signature", C_WAREHOUSE_SLOT to "warehouse_capacity", C_TOTEM_LINEUP to "totem_lineup",
        C_TMP_VIP to "tmp_vip_claim", C_GREAT_OFFER to "event_great_offer", C_REBIRTH_SHOP_TIMER to "rebirth_shop_roll",
        C_REBIRTH_SHOP_REFRESH to "rebirth_shop_refresh", C_REBIRTH_SHOP_BUY to "rebirth_shop_buy",
        C_ALBUM_ACTIVATE to "album_activate")

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
        if (document != null && now != null && document["day"] == JStr(Shops.dayOf(now))) {
            return listOf(S_REBIRTH_SHOP to RebirthShop.listPayload(document, now))
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
        if (!Py.truthy(document["claimed"])) return TMP_VIP_UNCLAIMED to 0L
        val left = ((document["expires_at"] as? JInt)?.value?.toLong()?.takeIf { it != 0L } ?: 0L) - now
        if (left > 0) {
            // `document.get("duration_seconds", TMP_VIP_SECONDS)`: absent → the default; a stored null fails like the reference's min()
            val duration = document["duration_seconds"]
            val seconds = when (duration) {
                null -> TMP_VIP_SECONDS
                is JInt -> duration.value.toLong()
                else -> throw IllegalStateException("'<' not supported between instances of 'NoneType' and 'int'")
            }
            return TMP_VIP_ACTIVE to minOf(left, seconds)
        }
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
        val (state, _) = tmpVipView(tmpVipDocument(current, seeds), now ?: Summon.nowEpoch())
        return if (state == TMP_VIP_ACTIVE) maxOf(vipLevel, TMP_VIP_LEVEL) else vipLevel
    }

    /** A purchase that lifts the real VIP level to 4 or more ends a running temporary VIP4 at once. */
    fun tmpVipEndFrames(current: StateStore.Current?, levelBefore: Long, levelAfter: Long): List<Frame> {
        if (levelBefore >= TMP_VIP_LEVEL || levelAfter < TMP_VIP_LEVEL) return emptyList()
        return listOf(S_TMP_VIP to tmpVipPayload(TMP_VIP_EXPIRED, 0))
    }

    // === Warehouse ==================================================================================================

    fun warehouseMaxLevel(inputs: AcquisitionInputs): Long {
        val row = if (inputs is DailyInputs) inputs.building(Castle.WAREHOUSE) else null
        return (row?.get("max_level") as? JInt)?.value?.toLong()?.takeIf { it != 0L } ?: 200L
    }

    /** The capacities to open: always the maximum Warehouse level's limit; null when the save has no Warehouse. */
    fun openedLimit(state: JObj, inputs: AcquisitionInputs): List<Long>? {
        if (Castle.WAREHOUSE !in Castle.buildingLevels(state)) return null
        return Castle.warehouseCapacity(warehouseMaxLevel(inputs), inputs as DailyInputs)
    }

    /** Warehouse building → its maximum level; (before, after) or null when already there. */
    fun raiseWarehouse(state: JObj, inputs: AcquisitionInputs): Pair<Long, Long>? {
        val level = Castle.buildingLevels(state)[Castle.WAREHOUSE]?.long
        val top = warehouseMaxLevel(inputs)
        if (level == null || level >= top) return null
        Castle.setBuilding(state, Castle.WAREHOUSE, JInt(top))
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
        val level = Castle.buildingLevels(state)[Castle.WAREHOUSE]?.long
        val limit = openedLimit(state, inputs)
        val after = if (limit == null) before else before.zip(limit).map { (b, cap) -> maxOf(b, cap) }
        val raised = raiseWarehouse(state, inputs)
        val reply = ArrayList<Frame>()
        reply.add(Castle.S_ITEM_CAPACITY to Castle.itemCapacityPayload(after.map { JInt(it) }))
        if (raised != null) reply.add(0, 640 to WireWriter().u8(Castle.WAREHOUSE.toInt()).number('H', raised.second).bytes())
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
        return before.zip(limit).any { (b, cap) -> b < cap } || (Castle.buildingLevels(state)[Castle.WAREHOUSE]?.long ?: 0L) < warehouseMaxLevel(inputs)
    }

    /** The same raise as C77, without a frame (the login S18 carries the triple); [Unchanged] when nothing is below. */
    fun planCapacityRepair(owned: Owned, inputs: AcquisitionInputs): Plan {
        val plan = planWarehouseSlot(ByteArray(0), owned, inputs)
        plan.packets = emptyList()
        plan["evidence_class"] = "native_use_formula_login_repair_policy"
        return plan
    }

    // === state-changing / stateless sweep requests (group 8, owned by the sweep-features slice) ======================
    // Lead-written dispatcher + stubs with fixed signatures so `Session.sweepRoute` wires in without conflicts; the
    // sweep-features slice replaces each stub body with the port of the matching `sweep_features.py` / `rebirth_shop.py`
    // function. Until then a NotPorted keeps the step waiting in the harness (the request's first cause moves here).

    /** (action, planner) of a state-changing sweep request; the returned pair mirrors `sweep_features.planner_for`. */
    class SweepRouted(val action: String, val planner: (Owned, StateStore.Current) -> Plan)

    /** `planner_for(opcode, payload, inputs, seeds, owner_key)`: (action, planner) of a state-changing sweep request. */
    fun plannerFor(opcode: Int, payload: ByteArray, inputs: DailyInputs, seeds: SystemSeeds.SeedFrames?,
                   ownerKey: String = "char"): SweepRouted {
        val action = ACTIONS[opcode] ?: throw Acquisition.Rejected("Not a state-changing sweep request")
        val planner: (Owned, StateStore.Current) -> Plan = when (opcode) {
            C_ALBUM_ACTIVATE -> { owned, current -> planAlbumActivate(payload, owned, current, inputs, seeds) }
            C_REBIRTH_SHOP_TIMER, C_REBIRTH_SHOP_REFRESH, C_REBIRTH_SHOP_BUY ->
                { owned, current -> planRebirthShop(opcode, payload, owned, current, inputs, seeds, Summon.nowEpoch(), ownerKey) }
            C_SIGNATURE -> { owned, _ -> planSignature(payload, owned, inputs) }
            C_WAREHOUSE_SLOT -> { owned, _ -> planWarehouseSlot(payload, owned, inputs) }
            C_TOTEM_LINEUP -> { _, current -> planTotemLineup(payload, current, seeds) }
            C_TMP_VIP -> { _, current -> planTmpVipClaim(payload, current, seeds, Summon.nowEpoch()) }
            C_GREAT_OFFER -> { owned, current -> planGreatOfferSpin(payload, owned, current, inputs, Summon.nowEpoch()) }
            else -> throw Acquisition.Rejected("Not a state-changing sweep request")
        }
        return SweepRouted(action, planner)
    }

    /** `level_gift_frame(seeds)`: the served S1760 type-1 frame (the first type-1 seed frame, else `01 00000000`). */
    fun levelGiftFrame(seeds: SystemSeeds.SeedFrames?): ByteArray {
        for (payload in seeds?.all(S_EVENT_UPDATE) ?: emptyList()) {
            if (payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == 1) return payload
        }
        return byteArrayOf(1, 0, 0, 0, 0)
    }

    /** `stateless_reply(opcode, payload, inputs, seeds)`: (packets, log fields) of the requests that read / change nothing. */
    fun statelessReply(opcode: Int, payload: ByteArray, inputs: AcquisitionInputs?, seeds: SystemSeeds.SeedFrames?): Pair<List<Frame>, JObj> {
        if (opcode == C_ROULETTE_RANK) {
            // Dead here (C643 not in STATELESS); kept for parity with the reference. POLICY: no ranking offline.
            val tab = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else -1
            if (payload.size != 1 || tab !in listOf(1, 2, 3)) throw Acquisition.Rejected("C643 carries one tab byte 1-3")
            return listOf(S_ROULETTE_RANK to byteArrayOf(tab.toByte(), 0)) to jobj("tab" to tab, "policy" to "empty_rank_lists")
        }
        if (opcode == C_YKHD_OLD_CARD) throw Acquisition.Rejected("The old event card is not active", ERROR_NOT_ACTIVATED)
        if (opcode == C_LEVEL_GIFT) return listOf(S_EVENT_UPDATE to levelGiftFrame(seeds)) to jobj("event_type" to 1, "policy" to "closed_event_frame")
        if (opcode in CLOSED_EVENT_FRAMES) {
            val body = CLOSED_EVENT_FRAMES.getValue(opcode)
            return listOf(S_EVENT_UPDATE to body) to jobj("event_type" to (body[0].toInt() and 0xFF), "policy" to "closed_event_frame")
        }
        throw Acquisition.Rejected("Not a stateless sweep request")
    }

    /** `_album_row(inputs, family)`: the tujian.csv row whose column 101 equals `family` (else null). */
    private fun albumRow(inputs: DailyInputs, family: Long): GameTable.Row? {
        for (fields in inputs.tableRows("tujian")) {
            if (PyValues.strip(fields.field("101") ?: "") == family.toString()) return fields
        }
        return null
    }

    /** `plan_album_activate(payload, owned, current, inputs, seeds)`: C513 → tujian.csv reward (once), S550 + S548. */
    fun planAlbumActivate(payload: ByteArray, owned: Owned, current: StateStore.Current, inputs: DailyInputs, seeds: SystemSeeds.SeedFrames?): Plan {
        if (payload.size != 4) throw Acquisition.Rejected("C513 carries one u32 family id")
        val family = WireReader(payload).u32()
        val row = albumRow(inputs, family) ?: throw Acquisition.Rejected("Cannot find the Album", ERROR_NO_ALBUM)
        val document = albumDocument(current, seeds)
        if (document.arr("families").any { it.long == family }) throw Acquisition.Rejected("Album reward has been claimed", ERROR_ALBUM_CLAIMED)
        fun num(key: String): Long = PyValues.digitInt(row.field(key), 0)
        val kind = num("102")
        val members = (104 until 112).map { num(it.toString()) }.filter { it != 0L }
        val subsystems = owned.state["subsystems"] as? JObj
        val section = subsystems?.get(ALBUM_SECTIONS[kind] ?: "") as? JObj
        val held = HashSet<Long>()
        for (e in (section?.get("entries") as? JArr) ?: JArr()) {
            val v0 = e.asObj.arr("wire_values")[0].long
            held.add(if (kind == 1L) Math.floorDiv(v0, 1000L) else v0)
        }
        if (members.isEmpty() || members.any { it !in held }) throw Acquisition.Rejected("Not enough cards and failed to receive the album", ERROR_ALBUM_INCOMPLETE)
        val frames = ArrayList<Frame>()
        val reward = Acquisition.emptyReward()
        if (num("120") == 1L && num("121") != 0L && num("122") != 0L) {
            frames.add(owned.grantItem(num("121"), num("122")))
            reward.arr("items").add(jarr(num("121"), num("122")))
        }
        for ((field, amount, name) in listOf(Triple(Acquisition.GOLD, num("123"), "gold"), Triple(Acquisition.DIAMOND, num("124"), "diamond"))) {
            if (amount != 0L) {
                frames.add(owned.roleAdd(field, amount))
                reward[name] = JInt(amount)
            }
        }
        val fams = document.arr("families").map { it.long }.toSortedSet()
        fams.add(family)
        document["families"] = jvalue(fams.toList())
        frames.add(S_ALBUM_REWARD to BattleReport.encodeReward(reward))
        frames.add(S_ALBUM to albumPayload(listOf(JInt(family))))
        return Plan(jobj("family" to family, "album_state_after" to document,
            "evidence_class" to "native_use_table_policy_order"), frames)
    }

    /** `plan_totem_lineup(payload, current, seeds)`: C2529 `u32 target` → S2884 (the same lineup again is [Unchanged]). */
    fun planTotemLineup(payload: ByteArray, current: StateStore.Current, seeds: SystemSeeds.SeedFrames?): Plan {
        if (payload.size != 4) throw Acquisition.Rejected("C2529 carries one u32")
        val target = WireReader(payload).u32()
        val document = totemDocument(current, seeds)
        if (document.arr("totems").none { it.asArr[0].long == target }) throw Acquisition.Rejected("That Mastery is not owned")
        val reply = listOf(S_TOTEM_LINEUP to WireWriter().u32(target).bytes())
        if (document.long("lineup") == target) throw Unchanged(reply, jobj("lineup" to target))
        val before = document.long("lineup")
        document["lineup"] = JInt(target)
        return Plan(jobj("lineup_before" to before, "lineup_after" to target, "totem_state_after" to document,
            "evidence_class" to "native_use_layout_policy_refusal"), reply)
    }

    /** `decode_signature(payload)`: `text bytes + NUL`, strict UTF-8, no embedded NUL. */
    fun decodeSignature(payload: ByteArray): Pair<ByteArray, String> {
        if (payload.isEmpty() || payload[payload.size - 1].toInt() != 0 || payload.copyOfRange(0, payload.size - 1).any { it.toInt() == 0 }) {
            throw Acquisition.Rejected("C577 carries one NUL-terminated text")
        }
        val raw = payload.copyOfRange(0, payload.size - 1)
        val text = Utf8.decodeStrict(raw) ?: throw Acquisition.Rejected("Signature is not UTF-8")
        return raw to text
    }

    /** `_signature_value(state)`: the single text (tag 0x61) role-property-21 value (mutated in place by [planSignature]). */
    private fun signatureValue(state: JObj): JObj {
        val matches = state.arr("role_properties").map { it.asObj }.filter { it.long("id") == ROLE_SIGNATURE }.map { it.obj("value") }
        if (matches.size != 1 || (matches[0]["tag"] as? JInt)?.value?.toInt() != TEXT_TAG) throw Acquisition.Rejected("Expected one text role property 21")
        return matches[0]
    }

    /** `signature_payload(raw)`: S128 with role property 21 = the raw bytes as a tag-0x61 string. */
    fun signaturePayload(raw: ByteArray): ByteArray =
        TypedValues.encodeFieldsBytes(JArr(mutableListOf(jobj("id" to ROLE_SIGNATURE, "value" to jobj("tag" to TEXT_TAG, "raw_hex" to raw.toHexString())))))

    /** `plan_signature(payload, owned, inputs)`: C577 → role property 21 + S128 (same text again is [Unchanged]). */
    fun planSignature(payload: ByteArray, owned: Owned, inputs: AcquisitionInputs): Plan {
        val (raw, text) = decodeSignature(payload)
        val limit = prop(inputs, 703, 20)
        if (text.codePointCount(0, text.length).toLong() > limit || raw.size > SIGNATURE_MAX_BYTES) {
            throw Acquisition.Rejected("Signature longer than $limit characters / $SIGNATURE_MAX_BYTES bytes")
        }
        val value = signatureValue(owned.state)
        val reply = listOf(S_ROLE to signaturePayload(raw))
        val before = value.str("raw_hex").hexBytes()
        if (before.contentEquals(raw)) throw Unchanged(reply, jobj("signature_bytes" to raw.size))
        value["raw_hex"] = JStr(raw.toHexString())
        value["text"] = JStr(text)
        return Plan(jobj("signature_bytes_before" to before.size, "signature_bytes_after" to raw.size,
            "evidence_class" to "native_use_reply_candidate_policy_validation"), reply)
    }

    /** `plan_tmp_vip_claim(payload, current, seeds, now)`: C25 → S1824 `01 <4 days>` (already claimed → [Unchanged]). */
    fun planTmpVipClaim(payload: ByteArray, current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, now: Long): Plan {
        empty(payload, C_TMP_VIP)
        val document = tmpVipDocument(current, seeds)
        val (state, seconds) = tmpVipView(document, now, realVipLevel(current))
        if (state != TMP_VIP_UNCLAIMED) throw Unchanged(listOf(S_TMP_VIP to tmpVipPayload(state, seconds)), jobj("tmp_vip_state" to state, "tmp_vip_left" to seconds))
        document["claimed"] = JBool(true)
        document["claimed_at"] = JInt(now)
        document["expires_at"] = JInt(now + TMP_VIP_SECONDS)
        document["duration_seconds"] = JInt(TMP_VIP_SECONDS)
        document["clock"] = JStr("device_clock_epoch")
        return Plan(jobj("tmp_vip_after" to document, "tmp_vip_state_after" to TMP_VIP_ACTIVE, "expires_at" to document["expires_at"],
            "duration_seconds" to TMP_VIP_SECONDS, "evidence_class" to "native_use_layout_capture_states_policy_duration"),
            listOf(S_TMP_VIP to tmpVipPayload(TMP_VIP_ACTIVE, TMP_VIP_SECONDS)))
    }

    /** `plan_great_offer_spin(payload, owned, current, inputs, now)`: C1649 → one Great Offer spin, closed / no attempt → [Unchanged]. */
    fun planGreatOfferSpin(payload: ByteArray, owned: Owned, current: StateStore.Current, inputs: DailyInputs, now: Long): Plan {
        empty(payload, C_GREAT_OFFER)
        val document = current.document("sgxj_state")
        val view = EventHall.greatOfferView(document, inputs, now)
            ?: throw Unchanged(listOf(EventHall.s1760(EventHall.T_GREAT_OFFER, byteArrayOf(0)), 6 to TransactionPackets.errorPayload(EventHall.ERROR_OFFER)),
                jobj("event_type" to EventHall.T_GREAT_OFFER, "policy" to "great_offer_closed"))
        if (view.long("remaining") == 0L) {
            throw Unchanged(listOf(EventHall.s1760(EventHall.T_GREAT_OFFER, EventHall.greatOfferBody(document, inputs, now)), 6 to TransactionPackets.errorPayload(EventHall.ERROR_OFFER)),
                jobj("event_type" to EventHall.T_GREAT_OFFER, "policy" to "great_offer_no_attempts"))
        }
        val salt = "sgxj:${view.str("window")}:${PyDocs.str(view["spins"])}:${current.payloadSha256}"
        val seed = Acquisition.seedFor(payload, current.revision, salt)
        return EventHall.planGreatOffer(owned, document, inputs, now, seed)
    }

    /** `plan_rebirth_shop(opcode, payload, owned, current, inputs, seeds, now, owner_key)`: C3941 timer / C3939 refresh / C3937 buy. */
    fun planRebirthShop(opcode: Int, payload: ByteArray, owned: Owned, current: StateStore.Current, inputs: DailyInputs,
                        seeds: SystemSeeds.SeedFrames?, now: Long, ownerKey: String): Plan {
        val seed = seeds?.first(RebirthShop.S_LIST)
        val stored = (current.document("rebirth_shop") as? JObj)?.deepCopy()
        val (document, rolled) = RebirthShop.view(stored, inputs, owned.state, now, ownerKey, seed,
            if (seed != null) seeds!!.provenance(RebirthShop.S_LIST) else null)
        if (opcode == C_REBIRTH_SHOP_TIMER) {
            empty(payload, opcode)
            val reply = listOf(S_REBIRTH_SHOP to RebirthShop.listPayload(document, now))
            if (!rolled) throw Unchanged(reply, jobj("policy" to "rebirth_shop_today"))
            return Plan(jobj("rebirth_shop_after" to document, "evidence_class" to "native_use_table_policy_draw"), reply)
        }
        val request = RebirthShop.decodeRequest(opcode, payload)
        if (opcode == C_REBIRTH_SHOP_REFRESH) {
            val used = document.obj("shops").obj(request.long("shop").toString()).long("used")
            val rng = PyRandom.seeded(Acquisition.seedFor(payload, current.revision, "rebirth_shop:${document.str("day")}:$used"))
            return RebirthShop.planRefresh(request, owned, document, inputs, now, rng)
        }
        val jewels = current.jewelEntriesView
        return RebirthShop.planBuy(request, owned, document, inputs, now, if (jewels != null) JArr(ArrayList(jewels.items)) else null)
    }
}
