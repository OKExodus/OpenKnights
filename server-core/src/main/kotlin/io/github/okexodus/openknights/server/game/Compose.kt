package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
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
import io.github.okexodus.openknights.protocol.TypedValues
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.StateStore
import java.math.BigInteger

/**
 * Fortify-screen Combine / Refine (`compose.py`, docs/ACQUISITION_CONTRACT.md): hero fuse C1249, gear / jewelry / item
 * refine C2051 / C2633 / C3137, gear / jewelry Combine C2055 / C2631 and the fuse luck list S1572.
 *
 * - C1249 `u32 target hero, u8 n, n x u32 material hero`; C2051 / C2633 `u8 n, n x u32 uid`; C3137 `u32 n, n x (u32 item
 *   uid, u32 count)`; C2055 / C2631 `u32 target uid, u8 n (>= 1), n x u32 material uid`.
 * - Replies: S1568 `u8 result`, [`u32 uid`, hero map], Reward, C string ("size=N, rate=R"); S1572 `u8 n, n x (u8 star,
 *   u8 luck)`; S1574 / S3088 / S3340 bare Reward; S1576 / S3086 `u32 uid, u8 value, u32 N` (Combine, built from the client
 *   code: the rate gate >= 10000, the stones, the super flag 0 → 1; never captured).
 */
object Compose {
    const val HERO_FUSE = 1249
    const val GEAR_REFINE = 2051
    const val JEWEL_REFINE = 2633
    const val ITEM_REFINE = 3137
    const val GEAR_COMPOSE = 2055
    const val JEWEL_COMPOSE = 2631
    const val S_FUSE = 1568
    const val S_LUCK = 1572
    const val S_GEAR_REFINE = 1574
    const val S_JEWEL_REFINE = 3088
    const val S_ITEM_REFINE = 3340
    const val S_GEAR_COMPOSE = 1576
    const val S_JEWEL_COMPOSE = 3086
    const val S_EQUIP_BAG_REMOVE = 102
    const val S_EQUIP_REMOVE = 98
    const val S_JEWEL_LIST_REMOVE = 3074
    const val S_BENCH_REMOVE = 40
    const val S_HERO_REMOVE = 34
    /** Client GetProbabilityNumForHero: x4 for a super material. */
    const val SUPER_MULTIPLIER_PROPERTY = 902L
    /** GetProbabilityNumForEquip / ...Jewelry (both 4). */
    val COMPOSE_SUPER_PROPERTY = mapOf("equip" to 913L, "jewelry" to 957L)
    /** getMinLuckNum for combine types 2 / 4 (the button shows 100 %). */
    const val COMPOSE_MIN_RATE = 10000L
    // Combine refusals on the client's own texts; the check -> code mapping is a labeled local choice.
    const val ERROR_GEAR_MISSING = 6004
    const val ERROR_JEWEL_TARGET = 69501
    const val ERROR_JEWEL_MISSING = 69505
    const val ERROR_RATE = 1032
    const val LUCK_PROFILE = "fuse_luck_v1"

    // --- compose-row values (the reference's `compose_row` dict: int cells, raw text cells, blank cells absent) ---

    /** `row.get(key)` truthiness. */
    private fun has(row: JObj?, key: String): Boolean = row != null && Py.truthy(row[key])

    /** `row[key]` used as an integer (a text cell is a local defect, as the reference's TypeError). */
    private fun cell(row: JObj, key: String): Long = (row[key] ?: throw NoSuchElementException(key)).long

    /** `int(value or default)` of a compose-row cell (text cells parsed by `int()`). */
    private fun intOr(value: JValue?, default: Long): Long = when {
        !Py.truthy(value) -> default
        value is JStr -> PyValues.parseLong(value.value)
        else -> value!!.long
    }

    /** `inputs.compose_row(table, star, super)` where the star may be None (no row then matches). */
    private fun composeRow(inputs: AcquisitionInputs, table: String, star: Long?, superClass: Long): JObj? =
        if (star == null) null else inputs.composeRow(table, star, superClass)

    // --- request codecs ---

    /** `decode_uid_list(payload, label)`: `u8 n (>= 1), n x u32`. */
    fun decodeUidList(payload: ByteArray, label: String): List<Long> {
        val n = if (payload.isEmpty()) 0 else payload[0].toInt() and 0xFF
        if (payload.isEmpty() || payload.size != 1 + 4 * n || n == 0) throw Acquisition.Rejected("$label is u8 n (>= 1), n x u32")
        val r = WireReader(payload.copyOfRange(1, payload.size))
        return (0 until n).map { r.u32() }
    }

    /** `decode_fuse_request(payload)`: `u32 target, u8 n, n x u32`. */
    fun decodeFuseRequest(payload: ByteArray): JObj {
        if (payload.size < 5 || payload.size != 5 + 4 * (payload[4].toInt() and 0xFF)) throw Acquisition.Rejected("C1249 is u32 target, u8 n, n x u32")
        val r = WireReader(payload)
        val target = r.u32()
        val n = r.u8()
        return jobj("target" to target, "materials" to (0 until n).map { r.u32() })
    }

    /** `decode_item_refine_request(payload)`: `u32 n (>= 1), n x (u32 uid, u32 count)`. */
    fun decodeItemRefineRequest(payload: ByteArray): JObj {
        if (payload.size < 4) throw Acquisition.Rejected("C3137 is u32 n, n x (u32 uid, u32 count)")
        val r = WireReader(payload)
        val n = r.u32()
        if (n == 0L || payload.size.toLong() != 4 + 8 * n) throw Acquisition.Rejected("C3137 is u32 n (>= 1), n x (u32 uid, u32 count)")
        return jobj("entries" to (0 until n).map { jarr(r.u32(), r.u32()) })
    }

    /** `decode_compose_request(payload, label)`: `u32 target, u8 n (>= 1), n x u32 material`. */
    fun decodeComposeRequest(payload: ByteArray, label: String): JObj {
        if (payload.size < 5 || payload[4].toInt() == 0 || payload.size != 5 + 4 * (payload[4].toInt() and 0xFF)) {
            throw Acquisition.Rejected("$label is u32 target, u8 n (>= 1), n x u32", Acquisition.ERROR_INVALID)
        }
        val r = WireReader(payload)
        val target = r.u32()
        val n = r.u8()
        return jobj("target" to target, "materials" to (0 until n).map { r.u32() })
    }

    /** S1576 / S3086 `u32 uid, u8 value, u32 N`. */
    fun composeResultPayload(uid: Long, value: Long, attribute: Long): ByteArray =
        WireWriter().number('I', uid).number('B', value).number('I', attribute and 0xFFFFFFFFL).bytes()

    /** `initial_luck_document(current)`: fresh characters start with an empty luck list (policy). */
    fun initialLuckDocument(current: StateStore.Current): JObj =
        if (current.characterProfile != null) jobj("profile" to LUCK_PROFILE, "luck" to JObj(), "seed_rule" to "fresh: empty list (policy)")
        else jobj("profile" to LUCK_PROFILE, "luck" to jobj("6" to 0), "seed_rule" to "derived: live S1572 reply (star 6, luck 0)")

    /** S1572 `u8 n, n x (u8 star, u8 luck)`, ascending by star. */
    fun luckPayload(luck: JObj): ByteArray {
        val items = luck.entries.map { PyValues.parseInt(it.key) to it.value }.sortedWith(compareBy<Pair<BigInteger, JValue>> { it.first }.thenComparator { a, b -> PyDocs.compare(a.second, b.second) })
        val w = WireWriter().number('B', items.size.toLong())
        for ((s, v) in items) w.number('B', s).number('B', PyDocs.int(v))
        return w.bytes()
    }

    fun superOf(template: Long): Long = Math.floorMod(Math.floorMod(Math.floorDiv(template, 100L), 10L), 3L)

    // --- refines ---

    /** `plan_gear_refine(uids, owned, inputs)`: C2051. */
    fun planGearRefine(uids: List<Long>, owned: Owned, inputs: AcquisitionInputs): Plan {
        if (uids.toSet().size != uids.size) throw Acquisition.Rejected("Duplicate gear in the refine request")
        val records = LinkedHashMap<Long, JArr>()
        for (r in owned.state.arr("equipment")) { val w = r.asObj.arr("wire_values"); records[w[0].long] = w }
        val bag = owned.state.arr("bag_equipment_uids").map { it.long }.toSet()
        var total = 0L
        var item: JValue? = null
        val rows = JArr()
        for (uid in uids) {
            val record = records[uid] ?: throw Acquisition.Rejected("Gear is not owned", 6004)
            if (uid !in bag) throw Acquisition.Rejected("Only unequipped gear can be refined", 6004)
            val template = record[1].long
            val flag = record[5].long
            val row = composeRow(inputs, "equiprh", inputs.equipStar(template), if (flag != 0L) 1 else 0)
            if (row == null || !has(row, "105")) throw Acquisition.Rejected("No refine row for this gear", Acquisition.ERROR_WRONG_TYPE)
            if (item != null && row.getValue("104") != item) throw Acquisition.Rejected("Mixed refine outputs are not supported")
            item = row.getValue("104")
            total += cell(row, "105")
            rows.add(jobj("uid" to uid, "template" to template, "row" to row["101"], "amount" to row["105"]))
        }
        val removed = uids.toSet()
        owned.state["equipment"] = JArr(owned.state.arr("equipment").filter { it.asObj.arr("wire_values")[0].long !in removed }.toMutableList())
        owned.state["bag_equipment_uids"] = JArr(owned.state.arr("bag_equipment_uids").filter { it.long !in removed }.toMutableList())
        val grant = owned.grantItem(item!!.long, total)
        val reward = Acquisition.emptyReward()
        reward["items"] = jarr(jarr(item, total))
        val packets = listOf(grant, S_GEAR_REFINE to BattleReport.encodeReward(reward), S_EQUIP_BAG_REMOVE to Acquisition.uidListPayload(uids),
            S_EQUIP_REMOVE to Acquisition.uidListPayload(uids))
        return Plan(jobj("uids" to uids, "refined" to rows, "item" to item, "amount" to total, "reward" to reward,
            "evidence_class" to "config_deterministic_capture_confirmed"), packets)
    }

    /** `plan_jewel_refine(uids, owned, inputs, jewel_entries)`: C2633 on the stored unequipped-jewelry list entries. */
    fun planJewelRefine(uids: List<Long>, owned: Owned, inputs: AcquisitionInputs, jewelEntries: JArr): Plan {
        if (uids.toSet().size != uids.size) throw Acquisition.Rejected("Duplicate jewel in the refine request")
        val byUid = LinkedHashMap<Long, JObj>()
        for (e in jewelEntries) byUid[e.asObj.arr("record")[0].long] = e.asObj
        var total = 0L
        var item: JValue? = null
        val rows = JArr()
        for (uid in uids) {
            val entry = byUid[uid] ?: throw Acquisition.Rejected("Jewel is not in the unequipped list", 69505)
            val record = entry.arr("record")
            val template = record[1].long
            val superFlag = record[5].long
            val row = composeRow(inputs, "jewelry_ronghe", inputs.jewelStar(template), if (superFlag != 0L) 1 else 0)
            if (row == null || !has(row, "105")) throw Acquisition.Rejected("No refine row for this jewel", Acquisition.ERROR_WRONG_TYPE)
            if (item != null && row.getValue("104") != item) throw Acquisition.Rejected("Mixed refine outputs are not supported")
            item = row.getValue("104")
            total += cell(row, "105")
            rows.add(jobj("uid" to uid, "template" to template, "row" to row["101"], "amount" to row["105"]))
        }
        val removed = uids.toSet()
        val remaining = jewelEntries.filter { it.asObj.arr("record")[0].long !in removed }
        val grant = owned.grantItem(item!!.long, total)
        val reward = Acquisition.emptyReward()
        reward["items"] = jarr(jarr(item, total))
        val packets = listOf(grant, S_JEWEL_REFINE to BattleReport.encodeReward(reward), S_JEWEL_LIST_REMOVE to Acquisition.uidListPayload(uids))
        return Plan(jobj("uids" to uids, "refined" to rows, "item" to item, "amount" to total, "reward" to reward,
            "jewel_entries_after" to remaining, "evidence_class" to "config_deterministic_capture_confirmed"), packets)
    }

    /** `plan_item_refine(entries, owned, inputs)`: consumed stacks one S68 / S66 each, then one S68 of every product stack, S3340. */
    fun planItemRefine(entries: JArr, owned: Owned, inputs: AcquisitionInputs): Plan {
        val seen = HashSet<Long>()
        val products = LinkedHashMap<Long, Long>()
        val consumeFrames = ArrayList<Frame>()
        val rows = JArr()
        for (e in entries) {
            val uid = e.asArr[0].long
            val count = e.asArr[1].long
            if (uid in seen || count <= 0 || count > 1000) throw Acquisition.Rejected("Item refine entries must be distinct uids with 1..1000 each")
            seen.add(uid)
            val entry = owned.item(uid)
            val refine = inputs.itemRefine(entry.template) ?: throw Acquisition.Rejected("This item cannot be refined", Acquisition.ERROR_WRONG_TYPE)
            consumeFrames.add(owned.consume(uid, count))
            val product = refine.long("product")
            products[product] = (products[product] ?: 0L) + refine.long("per_unit") * count
            rows.add(jobj("uid" to uid, "template" to entry.template, "count" to count).also { it.putAll(refine) })
        }
        val pairs = ArrayList<Pair<Long, Long>>()
        val newFrames = ArrayList<Frame>()
        for ((template, amount) in products) {
            val frame = owned.grantItem(template, amount)
            if (frame.first == 68) {
                val r = WireReader(frame.second.copyOfRange(1, frame.second.size))
                pairs.add(r.u32() to r.u32())
            } else newFrames.add(frame)
        }
        val grantFrames = (if (pairs.isNotEmpty()) {
            val w = WireWriter().number('B', pairs.size.toLong())
            for ((u, c) in pairs) w.number('I', u).number('I', c)
            listOf<Frame>(68 to w.bytes())
        } else emptyList()) + newFrames
        val reward = Acquisition.emptyReward()
        reward["items"] = JArr(products.entries.mapTo(ArrayList()) { jarr(it.key, it.value) })
        val packets = consumeFrames + grantFrames + listOf(S_ITEM_REFINE to BattleReport.encodeReward(reward))
        val productsJson = JObj()
        for ((k, v) in products) productsJson[k.toString()] = JInt(v)
        return Plan(jobj("entries" to entries, "refined" to rows, "products" to productsJson, "reward" to reward,
            "evidence_class" to "config_candidate_capture_confirmed"), packets)
    }

    // --- gear / jewelry Combine (C2055 / C2631); records are the 7-value shape (uid, config, level, exp, grade, flag, extra) ---

    /** `compose_target(table, target, inputs)`: the target config and its compose row (star = config 106, target super flag) with f106 != 0. */
    fun composeTarget(table: String, target: List<JValue>, inputs: AcquisitionInputs): Pair<JObj, JObj> {
        val composeTable = if (table == "equip") "equiprh" else "jewelry_ronghe"
        val config = inputs.composeFields(table, target[1].long) ?: throw Acquisition.Rejected("Unknown target template", Acquisition.ERROR_NOT_MERGEABLE)
        val row = inputs.composeRow(composeTable, config.long("star_106"), target[5].long)
        if (row == null || !has(row, "106") || !has(row, "107") || !has(row, "108")) {
            throw Acquisition.Rejected("This item cannot be combined (row f106 = 0)", Acquisition.ERROR_NOT_MERGEABLE)
        }
        return config to row
    }

    /**
     * `compose_gate(table, target, materials, inputs, equipped)`: every check the client makes before it sends C2055 /
     * C2631, in client order; the compose row, the rate, the stones and the contributions.
     */
    fun composeGate(table: String, target: List<JValue>, materials: List<List<JValue>>, inputs: AcquisitionInputs,
                    equipped: Collection<Long> = emptyList()): JObj {
        val (config, row) = composeTarget(table, target, inputs)
        val star = config.long("star_106")
        val flag = target[5].long
        val uids = materials.map { it[0].long }
        if (uids.toSet().size != uids.size || target[0].long in uids) {
            throw Acquisition.Rejected("Materials must be distinct and differ from the target", Acquisition.ERROR_NOT_MERGEABLE)
        }
        val base = inputs.composeRow("herorh", star, flag)
        var rate = intOr(base?.get("110"), 0)
        val contributions = JArr()
        val multiplier = Acquisition.propertyInt(inputs, COMPOSE_SUPER_PROPERTY.getValue(table), 1)
        val hidden = equipped.toSet()
        for (record in materials) {
            val fields = inputs.composeFields(table, record[1].long)
            if (record[0].long in hidden) throw Acquisition.Rejected("Only unequipped items can be Combine materials", Acquisition.ERROR_NOT_MERGEABLE)
            if (fields == null || fields.long("star_106") != star || record[5].long < flag) {
                throw Acquisition.Rejected("Combine materials need the target's star and a super flag >= its flag", Acquisition.ERROR_NOT_MERGEABLE)
            }
            if (config.long("same_only_499") == 1L && record[1].long != target[1].long) {
                throw Acquisition.Rejected("This target accepts only materials of its own template", Acquisition.ERROR_NOT_MERGEABLE)
            }
            var value = if (record[1].long == target[1].long) fields.long("same_template") else fields.long("other_template")
            value *= if (record[5].long == 1L) multiplier else 1
            contributions.add(jobj("uid" to record[0], "template" to record[1], "flag" to record[5], "value" to value))
            rate += value
        }
        if (rate < COMPOSE_MIN_RATE) throw Acquisition.Rejected("Merge rate below 100 %", ERROR_RATE)
        return jobj("row" to row["101"], "star" to star, "rate" to rate, "stones" to jarr(row["107"], row["108"]), "contributions" to contributions)
    }

    /**
     * `compose_attribute(kind, record, inputs)`: S1576 / S3086 `N` (display only) — the displayed value with the super flag
     * minus without it (`Formula::GetEquipPropertyValue` / `GetJewelPropertyValue`); 0 when the inputs cannot be formed.
     */
    fun composeAttribute(kind: String, record: List<JValue>, inputs: AcquisitionInputs): Pair<Long, String> {
        val values = inputs.propertyValueInputs(kind, record[1].long, record[4].long) ?: return 0L to "policy_zero_without_formula_inputs"
        val level = record[2].long
        val extra = record[6].long
        val base108 = values.long("base_108")
        val potential = values.long("potential_before")
        return try {
            val value: (Long) -> Long = if (kind == "gear") { flag ->
                EquipEvolve.equipPropertyValue(level, flag, extra, base108, values.long("growth_109"), potential, values.long("property_912"))
            } else { flag ->
                EquipEvolve.jewelPropertyValue(level, flag, extra, base108, values.long("ratio_110"), potential, values.long("property_956"))
            }
            ((value(1) - value(0)) and 0xFFFFFFFFL) to "structural_candidate_property_formula"
        } catch (e: IllegalArgumentException) {      // the reference's TypeError / ValueError
            0L to "policy_zero_without_formula_inputs"
        }
    }

    private fun gateInto(plan: JObj, gate: JObj) { for ((k, v) in gate) plan[k] = v }

    /**
     * `plan_gear_compose(request, owned, inputs)`: C2055 — stones consumed, materials deleted, target super flag 0 → 1.
     * Frames (structural candidate; policy order): stones S68 / S66, S1576 (target, 1, N), S102 + S98 (materials).
     */
    fun planGearCompose(request: JObj, owned: Owned, inputs: AcquisitionInputs): Plan {
        val target = request.long("target")
        val materials = request.arr("materials").map { it.long }
        val records = LinkedHashMap<Long, JArr>()
        for (r in owned.state.arr("equipment")) { val w = r.asObj.arr("wire_values"); records[w[0].long] = w }
        val targetRecord = records[target] ?: throw Acquisition.Rejected("Target gear is not owned", ERROR_GEAR_MISSING)
        composeTarget("equip", targetRecord, inputs)
        for (uid in materials) if (uid !in records) throw Acquisition.Rejected("Material gear is not owned", ERROR_GEAR_MISSING)
        val bag = owned.state.arr("bag_equipment_uids").map { it.long }.toSet()
        val gate = composeGate("equip", targetRecord, materials.map { records.getValue(it) }, inputs, materials.filter { it !in bag })
        val stones = gate.arr("stones")
        val stoneFrames = owned.consumeTemplate(stones[0].long, stones[1].long)
        val flagBefore = targetRecord[5]
        targetRecord[5] = JInt(1)
        val removed = materials.toSet()
        owned.state["equipment"] = JArr(owned.state.arr("equipment").filter { it.asObj.arr("wire_values")[0].long !in removed }.toMutableList())
        owned.state["bag_equipment_uids"] = JArr(owned.state.arr("bag_equipment_uids").filter { it.long !in removed }.toMutableList())
        val (attribute, attributeClass) = composeAttribute("gear", targetRecord, inputs)
        val packets = stoneFrames + listOf(S_GEAR_COMPOSE to composeResultPayload(target, 1, attribute),
            S_EQUIP_BAG_REMOVE to Acquisition.uidListPayload(materials), S_EQUIP_REMOVE to Acquisition.uidListPayload(materials))
        val plan = jobj("operation" to "gear_compose", "target" to target, "template" to targetRecord[1], "target_equipped" to (target !in bag),
            "materials" to request["materials"])
        gateInto(plan, gate)
        plan["flag_before"] = flagBefore
        plan["flag_after"] = JInt(1)
        plan["attribute"] = JInt(attribute)
        plan["attribute_class"] = JStr(attributeClass)
        plan["evidence_class"] = JStr("native_use_structural_candidate_frames")
        return Plan(plan, packets)
    }

    /**
     * `plan_jewel_compose(request, owned, inputs, jewel_entries)`: C2631 on the stored unequipped-jewelry list; an
     * equipped jewel target is refused (labeled policy). Frames (candidate, policy order): stones, S3086 (target, 1, N), S3074.
     */
    fun planJewelCompose(request: JObj, owned: Owned, inputs: AcquisitionInputs, jewelEntries: JArr): Plan {
        val target = request.long("target")
        val materials = request.arr("materials").map { it.long }
        val entries = jewelEntries.map { e -> JObj(LinkedHashMap(e.asObj.map)).also { it["record"] = JArr(ArrayList(e.asObj.arr("record"))) } }
        val byUid = LinkedHashMap<Long, JObj>()
        for (e in entries) byUid[e.arr("record")[0].long] = e
        val worn = ItemFortify.jewelryView(owned.state.arr("formation"))
        if (target !in byUid) {
            if (target in worn) throw Acquisition.Rejected("Equipped jewelry cannot be the Combine target (block flag byte not decoded)", ERROR_JEWEL_TARGET)
            throw Acquisition.Rejected("Target jewelry is not owned", ERROR_JEWEL_TARGET)
        }
        composeTarget("jewelry", byUid.getValue(target).arr("record"), inputs)
        val records = ArrayList<List<JValue>>()
        for (uid in materials) {
            val listed = byUid[uid]
            val wornEntry = worn[uid]
            when {
                listed != null -> records.add(listed.arr("record"))
                wornEntry != null -> records.add(wornEntry.record.map { JInt(it) })
                else -> throw Acquisition.Rejected("Material jewelry is not owned", ERROR_JEWEL_MISSING)
            }
        }
        val gate = composeGate("jewelry", byUid.getValue(target).arr("record"), records, inputs, materials.filter { it !in byUid })
        val stones = gate.arr("stones")
        val stoneFrames = owned.consumeTemplate(stones[0].long, stones[1].long)
        val record = byUid.getValue(target).arr("record")
        val flagBefore = record[5]
        record[5] = JInt(1)
        val removed = materials.toSet()
        val remaining = entries.filter { it.arr("record")[0].long !in removed }
        val (attribute, attributeClass) = composeAttribute("jewelry", record, inputs)
        val packets = stoneFrames + listOf(S_JEWEL_COMPOSE to composeResultPayload(target, 1, attribute),
            S_JEWEL_LIST_REMOVE to Acquisition.uidListPayload(materials))
        val plan = jobj("operation" to "jewelry_compose", "target" to target, "template" to record[1], "materials" to request["materials"])
        gateInto(plan, gate)
        plan["flag_before"] = flagBefore
        plan["flag_after"] = JInt(1)
        plan["attribute"] = JInt(attribute)
        plan["attribute_class"] = JStr(attributeClass)
        plan["jewel_entries_after"] = JArr(remaining.toMutableList<JValue>())
        plan["evidence_class"] = JStr("native_use_structural_candidate_frames")
        return Plan(plan, packets)
    }

    // --- hero fuse (combine) ---

    /**
     * `fuse_rate(target, materials, inputs)`: client GetProbabilityNumForHero — herorh f110 + per material (hero f145 of
     * the target for the same base, else the material's f146), x property 902 for a super material whose class is at
     * least the target's.
     */
    fun fuseRate(target: Long, materials: List<Long>, inputs: AcquisitionInputs): Pair<Long, JObj> {
        val row = composeRow(inputs, "herorh", inputs.heroStar(target), superOf(target)) ?: throw NoSuchElementException("herorh row")
        var rate = cell(row, "110")
        val targetFields = inputs.heroFields(target)
        val multiplier = Acquisition.propertyInt(inputs, SUPER_MULTIPLIER_PROPERTY, 1)
        for (material in materials) {
            val fields = inputs.heroFields(material)
            val same = Math.floorDiv(material, 1000L) == Math.floorDiv(target, 1000L)
            val raw = (if (same) targetFields else fields)!![if (same) "145" else "146"]
            var value = if (raw.isNullOrEmpty()) 0L else PyValues.parseLong(raw)
            if (superOf(material) != 0L && superOf(target) <= superOf(material)) value *= multiplier
            rate += value
        }
        return rate to row
    }

    /** `plan_fuse(request, owned, inputs, luck, seed, deployed_uids, excluded_uids)`: C1249 (displayed rate + 100 x luck, seeded roll). */
    fun planFuse(request: JObj, owned: Owned, inputs: AcquisitionInputs, luck: JObj, seed: BigInteger,
                 deployedUids: Collection<Long> = emptyList(), excludedUids: Collection<Long> = emptyList()): Plan {
        val targetUid = request.long("target")
        val materials = request.arr("materials").map { it.long }
        val heroes = LinkedHashMap<Long, JArr>()
        for (f in owned.state.arr("heroes")) heroes[Acquisition.heroValues(f.asArr)[0L]!!.long] = f.asArr
        if (targetUid !in heroes || materials.isEmpty()) throw Acquisition.Rejected("Target hero is not owned", 1000)
        if (materials.toSet().size != materials.size || targetUid in materials) throw Acquisition.Rejected("Materials must be distinct and differ from the target")
        val bench = owned.state.arr("offline_hero_uids").map { it.long }.toSet()
        for (uid in materials) {
            val fields = heroes[uid] ?: throw Acquisition.Rejected("Material hero is not owned", 1024)
            if (uid !in bench || uid in deployedUids || uid in excludedUids || Py.truthy(Acquisition.heroValues(fields)[14L])) {
                throw Acquisition.Rejected("Material heroes must be on the bench and unassigned", 1002)
            }
        }
        val template = Acquisition.heroValues(heroes.getValue(targetUid))[1L]!!.long
        val star = inputs.heroStar(template)
        val superClass = superOf(template)
        val checkRow = if (star != null && star != 0L) inputs.composeRow("herorh", star, superClass) else null
        if (checkRow == null || !has(checkRow, "106") || !has(checkRow, "107") || !has(checkRow, "110")) {
            throw Acquisition.Rejected("This hero cannot be fused", Acquisition.ERROR_WRONG_TYPE)
        }
        val (rate, row) = fuseRate(template, materials.map { Acquisition.heroValues(heroes.getValue(it))[1L]!!.long }, inputs)
        val newTemplate = template + 100
        if (!inputs.heroExists(newTemplate) || superOf(newTemplate) != superClass + 1) throw Acquisition.Rejected("No next super class for this hero", Acquisition.ERROR_WRONG_TYPE)
        val currentLuck = intOr(luck[star.toString()], 0)
        val threshold = intOr(row["119"], 10000)
        if (rate + currentLuck * 100 < threshold) throw Acquisition.Rejected("Success rate below the client's minimum", Acquisition.ERROR_INVALID)
        val stoneFrames = owned.consumeTemplate(cell(row, "107"), cell(row, "109"))
        val rng = PyRandom.seeded(seed)
        val success = rng.randrange(10000) < rate + currentLuck * 100
        val luckAfter = JObj(LinkedHashMap(luck.map))
        val reward = Acquisition.emptyReward()
        val packets = ArrayList<Frame>(stoneFrames)
        val consumed: List<Long>
        var body: ByteArray
        if (success) {
            val s32 = inputs.freshHeroFields(targetUid, newTemplate)
            val stored = Acquisition.storedHeroFields(s32)
            owned.state["heroes"] = JArr(owned.state.arr("heroes").mapTo(ArrayList()) { f ->
                if (Acquisition.heroValues(f.asArr)[0L]!!.long == targetUid) stored else f
            })
            val newValues = LinkedHashMap<Long, JValue>()
            for (f in stored) newValues[f.asObj.long("id")] = f.asObj.obj("value").getValue("bits")
            reward["hero_grow"] = jarr(jarr(targetUid, 0, 0, newValues[5L], newValues[7L], newValues[9L], newValues[11L], 0, 0, 0, 0))
            consumed = ArrayList(materials)
            luckAfter[star.toString()] = JInt(0)
            body = WireWriter().number('B', 1).number('I', targetUid).bytes() + TypedValues.encodeFieldsBytes(stored)
        } else {
            consumed = if (materials.size <= 3) materials else rng.sample(materials, 3)
            val c0 = row["121"]
            val c1 = row["122"]
            if (Py.truthy(c0) && Py.truthy(c1)) {
                packets.add(owned.grantItem(c0!!.long, c1!!.long))
                reward["items"] = jarr(jarr(c0, c1))
            }
            luckAfter[star.toString()] = JInt(minOf(255L, currentLuck + intOr(row["116"], 0)))
            body = byteArrayOf(0)
        }
        for (uid in consumed) owned.removeHero(uid)
        body += BattleReport.encodeReward(reward) + "size=${materials.size}, rate=$rate".toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        packets.add(S_FUSE to body)
        packets.add(S_BENCH_REMOVE to Acquisition.uidListPayload(consumed))
        packets.add(S_HERO_REMOVE to Acquisition.uidListPayload(consumed))
        packets.add(S_LUCK to luckPayload(luckAfter))
        return Plan(jobj("target" to targetUid, "materials" to request["materials"], "template_before" to template,
            "template_after" to (if (success) newTemplate else template), "rate" to rate, "luck_before" to currentLuck,
            "success" to JBool(success), "consumed" to consumed, "luck_after" to luckAfter, "seed" to seed, "reward" to reward,
            "evidence_class" to "preservation_policy_fuse_roll"), packets)
    }
}
