package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.sha256Hex
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.SqlConnection
import java.math.BigInteger

/**
 * Equipment, jewelry, runes and main-formation transactions (`equip_formation.py`; docs/EQUIP_FORMATION_CONTRACT.md):
 * request codecs, the stored unequipped-jewelry list, planners and the reply frames in the live order.
 *
 * Requests (all after the initialization queries):
 *  C67 `u8 slot, u32 hero` (bench -> main slot 0..5), C35 `u8 slot, u8 position` (swap with the holder), C65 `u8 slot`
 *  (captain), C79 `u8 slot, u8 pos, u32 uid` (gear; uid 0 = unequip; pos = equip field 104 - 1), C2625 the same for
 *  jewelry from the S3072 list, C1217 / C1219 `u8 slot, u8 group, u32 id` (rune to / from the gem bag), C1221
 *  `u8 mode, u32 id` (combine: 1 = once, 2 = all).
 *
 * The planners take deep copies of the owned view and return the mutated view plus the exact reply frames. Labeled
 * local policies: the bonus-rune strip on substitution ([BONUS_RUNE_POLICY]), the position of an empty-slot placement
 * ([EMPTY_SLOT_POSITION_POLICY]), the attribute bytes of a never-equipped ring ([DEFAULT_JEWEL_TAIL]).
 */
object EquipFormation {
    const val LINEUP_OPCODE = 67
    const val POSITION_OPCODE = 35
    const val CAPTAIN_OPCODE = 65
    const val EQUIP_OPCODE = 79
    const val JEWEL_OPCODE = 2625
    const val RUNE_EQUIP_OPCODE = 1217
    const val RUNE_UNEQUIP_OPCODE = 1219
    const val RUNE_COMBINE_OPCODE = 1221
    val REQUEST_OPCODES = listOf(LINEUP_OPCODE, POSITION_OPCODE, CAPTAIN_OPCODE, EQUIP_OPCODE, JEWEL_OPCODE,
        RUNE_EQUIP_OPCODE, RUNE_UNEQUIP_OPCODE, RUNE_COMBINE_OPCODE)

    const val S_BENCH_ADD = 38
    const val S_BENCH_REMOVE = 40
    const val S_SET_LINEUP = 42
    const val S_POSITION = 12
    const val S_CAPTAIN = 44
    const val S_EQUIP_BAG_ADD = 100
    const val S_EQUIP_BAG_REMOVE = 102
    const val S_SET_EQUIP = 104
    const val S_JEWEL_LIST_REMOVE = 3074
    const val S_JEWEL_LIST_ADD = 3076
    const val S_SET_JEWEL = 3082
    const val S_JEWEL_LIST = 3072
    const val S_GEM_BAG = 1536
    const val S_GEM_GROUP = 1538
    const val S_GEM_COMBINE = 1540

    val MAIN_SLOTS = 0L until 6L
    val POSITIONS = 0L until 6L
    val BONUS_RUNE_TYPES = listOf(25L, 26L)

    /** Block bytes 17..39 of a ring equipped from the unequipped list for the first time (one live observation). */
    val DEFAULT_JEWEL_TAIL: ByteArray = byteArrayOf(0x00, 0x00, 0xFF.toByte()) + ByteArray(20)
    const val BONUS_RUNE_POLICY = "keep bonus runes (GemConfig types 25/26) on a substitution only when the incoming hero's " +
        "rebirth level (hero field 19) is >= 1; otherwise return them to the gem bag"
    const val EMPTY_SLOT_POSITION_POLICY = "a hero placed into an empty slot takes the lowest free position above the highest " +
        "occupied one, else the lowest free position (Server 17: occupied {1} -> 2)"

    const val ERROR_INVALID = 102
    const val ERROR_HERO = 1000            // "Can't find the selected Hero"
    const val ERROR_GEAR = 6004            // "Can't find the selected Gear"
    const val ERROR_GEAR_POSITION = 6001   // "Invalid Equipment"
    const val ERROR_JEWEL = 69505          // "You do not have the specific Jewelry"
    const val ERROR_ITEMS = 69503          // "Not enough items"

    open class FormationRejected(message: String, val code: Int = ERROR_INVALID) : IllegalArgumentException(message)

    private fun u(value: Long, width: Int, label: String): Long {
        if (value < 0 || value >= (1L shl width)) throw PyValues.ValueError("$label must fit uint$width")
        return value
    }

    // --- request codecs ------------------------------------------------------------------------------------------------

    private val LAYOUTS = mapOf(LINEUP_OPCODE to "BI", POSITION_OPCODE to "BB", CAPTAIN_OPCODE to "B", EQUIP_OPCODE to "BBI",
        JEWEL_OPCODE to "BBI", RUNE_EQUIP_OPCODE to "BBI", RUNE_UNEQUIP_OPCODE to "BBI", RUNE_COMBINE_OPCODE to "BI")
    private val NAMES = mapOf(LINEUP_OPCODE to listOf("slot", "hero_uid"), POSITION_OPCODE to listOf("slot", "position"),
        CAPTAIN_OPCODE to listOf("slot"), EQUIP_OPCODE to listOf("slot", "position", "uid"),
        JEWEL_OPCODE to listOf("slot", "position", "uid"), RUNE_EQUIP_OPCODE to listOf("slot", "group", "gem_id"),
        RUNE_UNEQUIP_OPCODE to listOf("slot", "group", "gem_id"), RUNE_COMBINE_OPCODE to listOf("mode", "gem_id"))

    private fun layoutSize(layout: String): Int = layout.sumOf { if (it == 'I') 4 else 1 }

    /** `decode_request(opcode, payload)`: the fixed layout of the opcode, or the wrong-length refusal. */
    fun decodeRequest(opcode: Int, payload: ByteArray): JObj {
        val layout = LAYOUTS.getValue(opcode)
        if (payload.size != layoutSize(layout)) throw FormationRejected("Opcode $opcode request has the wrong length")
        val r = WireReader(payload)
        val result = JObj()
        NAMES.getValue(opcode).forEachIndexed { i, name -> result[name] = JInt(if (layout[i] == 'I') r.u32() else r.u8().toLong()) }
        return result
    }

    /** `encode_request(opcode, value)`. */
    fun encodeRequest(opcode: Int, value: JObj): ByteArray {
        val w = WireWriter()
        val layout = LAYOUTS.getValue(opcode)
        NAMES.getValue(opcode).forEachIndexed { i, name -> w.number(layout[i], value.getValue(name)) }
        return w.bytes()
    }

    // --- reply frames --------------------------------------------------------------------------------------------------

    /** `counted_uids(uids)`: `<B n` + n x `<I`. */
    fun countedUids(uids: List<Long>): ByteArray {
        val w = WireWriter().number('B', u(uids.size.toLong(), 8, "Count"))
        for (uid in uids) w.number('I', u(uid, 32, "Uid"))
        return w.bytes()
    }

    /** `record_list(records)`: `<B n` + n x 22-byte records. */
    fun recordList(records: List<JArr>): ByteArray {
        val w = WireWriter().number('B', u(records.size.toLong(), 8, "Count"))
        records.forEach { w.values("IIIIBBI", it) }
        return w.bytes()
    }

    fun gemBagFrame(gemId: Long, count: Long): Frame =
        S_GEM_BAG to WireWriter().number('I', u(gemId, 32, "Gem id")).number('I', u(count, 32, "Gem count")).bytes()

    fun gemGroupFrame(slot: Long, group: Long, ids: List<Long>): Frame {
        val size = u(ids.size.toLong(), 8, "Group size")
        val w = WireWriter().number('B', slot).number('B', group).number('B', size)
        ids.forEach { w.number('I', it) }
        return S_GEM_GROUP to w.bytes()
    }

    /** S1540: Reward v14 whose only content is gems `[[id, n]]` (live frames). */
    fun combineRewardFrame(producedId: Long, producedCount: Long): Frame {
        val reward = HeroFortify.emptyReward(14)
        reward["gems"] = jarr(jarr(producedId, producedCount))
        return S_GEM_COMBINE to BattleReport.encodeReward(reward)
    }

    /** One 22-byte record `<IIIIBBI`: uid, config, level, exp, grade, flag, extra. */
    fun record(w: WireWriter, r: JArr) {
        w.u32(r[0].long).u32(r[1].long).u32(r[2].long).u32(r[3].long).u8(r[4].long.toInt()).u8(r[5].long.toInt()).u32(r[6].long)
    }

    /** S3072 (`jewel_list_payload`): u32 n, n x 22-byte records. */
    fun jewelListPayload(records: List<JArr>): ByteArray {
        val w = WireWriter().u32(records.size.toLong())
        records.forEach { record(w, it) }
        return w.bytes()
    }

    /** `decode_jewel_list`: u32 n, n x 22-byte records. */
    fun decodeJewelList(payload: ByteArray): List<JArr> {
        require(payload.size >= 4) { "Jewelry list is truncated" }
        val r = WireReader(payload)
        val n = r.u32()
        require(payload.size.toLong() == 4 + 22 * n) { "Jewelry list length does not match its count" }
        return (0 until n).map { JArr(mutableListOf(JInt(r.u32()), JInt(r.u32()), JInt(r.u32()), JInt(r.u32()), JInt(r.u8()), JInt(r.u8()), JInt(r.u32()))) }
    }

    // --- stored unequipped-jewelry list (character DB table `jewelry_list`) ---------------------------------------------
    // The S3072 list is not part of opcode 18. It is served from the profile payload until the first jewelry transaction,
    // which seeds this checksum-bound document from exactly the payload the session served (provenance recorded) inside
    // the same atomic transaction. Entries keep the 22-byte record plus, for rings unequipped locally, block bytes 17..39.

    const val JEWEL_LIST_PROFILE = "jewelry_unequipped_list_v1"

    /** `write_jewelry_list`: sorted compact JSON, its SHA-256 over the stored text. */
    fun writeJewelryList(db: SqlConnection, document: JObj): String {
        val text = Json.dumps(document, sortKeys = true, itemSeparator = ",", keySeparator = ":")
        val checksum = sha256Hex(text.toByteArray(Charsets.UTF_8))
        db.execute("CREATE TABLE IF NOT EXISTS jewelry_list(id INTEGER PRIMARY KEY CHECK(id=1), document_json TEXT NOT NULL, document_sha256 TEXT NOT NULL)")
        db.execute("INSERT INTO jewelry_list VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET document_json=excluded.document_json, document_sha256=excluded.document_sha256", text, checksum)
        return checksum
    }

    /** The stored list seeded from exactly the payload the session served (`seed_jewelry_document`). */
    fun seedJewelryDocument(served: ByteArray, source: String): JObj {
        val records = decodeJewelList(served)
        if (records.map { it[0].long }.toSet().size != records.size) throw FormationRejected("Served jewelry list has duplicate uids")
        return jobj("profile" to JEWEL_LIST_PROFILE, "seeded_from" to jobj("source" to source, "payload_sha256" to sha256Hex(served), "records" to records.size),
            "entries" to records.sortedBy { it[0].long }.map { jobj("record" to it, "tail" to null) })
    }

    fun jewelryListPayloadOf(document: JObj): ByteArray = jewelListPayload(document.arr("entries").map { it.asObj.arr("record") })

    // --- state helpers -------------------------------------------------------------------------------------------------

    private fun slot(formation: JArr, slotId: Long): JObj {
        val matches = formation.filter { it.asObj["slot_id"] == JInt(slotId) }
        if (matches.size != 1) throw FormationRejected("Unknown formation slot")
        return matches[0].asObj
    }

    /** `_sorted_insert`: before the first larger uid (the live bench and gear bag stay ascending by uid). */
    private fun sortedInsert(values: JArr, value: Long) {
        if (JInt(value) in values) throw FormationRejected("Duplicate reference")
        val big = BigInteger.valueOf(value)
        val index = values.indexOfFirst { it.big > big }.let { if (it < 0) values.size else it }
        values.add(index, JInt(value))
    }

    private fun group0(slot: JObj, create: Boolean = false): JObj? {
        val groups = slot.arr("groups")
        val found = groups.filter { it.asObj["id"] == JInt(0) }
        if (found.size > 1) throw FormationRejected("Slot has more than one rune group 0")
        if (found.isNotEmpty()) return found[0].asObj
        if (!create) return null
        val group = jobj("id" to 0, "values" to JArr())
        groups.add(group)
        val sorted = groups.sortedBy { it.asObj["id"]!!.big }
        groups.clear()
        groups.addAll(sorted)
        return group
    }

    /** `gem_counts(gems_section)`: {id: count} in entry order; a duplicate id is refused. */
    fun gemCounts(gemsSection: JObj): LinkedHashMap<Long, Long> {
        val counts = LinkedHashMap<Long, Long>()
        for (entry in gemsSection.arr("entries")) {
            val values = entry.asObj.arr("wire_values")
            if (values.size > 2) throw PyValues.ValueError("too many values to unpack (expected 2)")
            if (values.size < 2) throw PyValues.ValueError("not enough values to unpack (expected 2, got ${values.size})")
            val gemId = values[0].long
            if (gemId in counts) throw FormationRejected("Duplicate gem bag entry")
            counts[gemId] = values[1].long
        }
        return counts
    }

    /** `gems_section_with(counts)`: the gems subsystem sorted by id with zero counts dropped (live S18 after combines). */
    fun gemsSectionWith(counts: Map<Long, Long>): JObj {
        val entries = JArr(counts.entries.sortedWith(compareBy({ it.key }, { it.value })).filter { it.value > 0 }
            .mapTo(ArrayList()) { jobj("wire_values" to jarr(it.key, it.value)) })
        if (entries.size > 255) throw FormationRejected("Gem bag exceeds its u8 count")
        return jobj("count" to entries.size, "entries" to entries)
    }

    /** `jewel_block(record, tail)`: the 40 formation block bytes of a ring (exp before level on the wire). */
    fun jewelBlock(record: JArr, tail: ByteArray): ByteArray {
        if (record.size != 7) throw PyValues.ValueError(if (record.size > 7) "too many values to unpack (expected 7)" else "not enough values to unpack (expected 7, got ${record.size})")
        val grade = record[4].long
        if (tail.size != 23) throw PyValues.ValueError("Jewel tail must be 23 bytes")
        val head = WireWriter().number('I', record[0]).number('I', record[1]).number('I', record[3]).number('I', record[2]).bytes()
        if (grade !in 0..255) throw PyValues.ValueError("bytes must be in range(0, 256)")
        return head + byteArrayOf(grade.toByte()) + tail
    }

    /** `jewel_block_record(raw)`: the list record (flag and extra 0) and the 23 tail bytes. */
    fun jewelBlockRecord(raw: ByteArray): Pair<JArr, ByteArray> {
        val r = WireReader(raw)
        val uid = r.u32()
        val config = r.u32()
        val exp = r.u32()
        val level = r.u32()
        if (raw.size <= 16) throw IndexOutOfBoundsException("index out of range")
        return jarr(uid, config, level, exp, raw[16].toInt() and 0xFF, 0, 0) to raw.copyOfRange(17, minOf(40, raw.size))
    }

    // --- planners ------------------------------------------------------------------------------------------------------

    /**
     * The owned view a planner reads (`view` keys: formation, captain_slot, offline_hero_uids, bag_equipment_uids,
     * equipment {uid: record}, heroes {uid: {field: bits}}, gems {count, entries}, gem_types, jewel_list). [deepCopy]
     * copies the parts a planner changes (the reference deep-copies all; the others are only read).
     */
    class View(
        val formation: JArr,
        var captainSlot: JValue,
        val offlineHeroUids: JArr,
        val bagEquipmentUids: JArr,
        val equipment: Map<Long, JArr>,
        val heroes: Map<Long, Map<Long, JValue?>>,
        var gems: JObj,
        val gemTypes: Map<Long, Long>,
        var jewelList: JArr,
    ) {
        fun deepCopy(): View = View(formation.deepCopy(), captainSlot, offlineHeroUids.deepCopy(), bagEquipmentUids.deepCopy(),
            equipment, heroes, gems.deepCopy(), gemTypes, jewelList.deepCopy())
    }

    /** A planner's result: the mutated [view], the recorded members in the reference's order ([data]) and the frames. */
    class FormationPlan(val view: View, val data: JObj, val packets: List<Frame>) {
        operator fun get(key: String): JValue? = data[key]
    }

    private fun truthy(v: JValue?) = Py.truthy(v)

    private fun hasHero(slot: JObj) = truthy(slot["hero_uid"])

    fun planLineup(request: JObj, view: View, excludedUids: Collection<Long> = emptyList(), leaderUids: Collection<Long> = emptyList()): FormationPlan {
        val v = view.deepCopy()
        val slotId = request.long("slot")
        val hero = request.long("hero_uid")
        if (slotId !in MAIN_SLOTS) throw FormationRejected("Only the main slots 0..5 are supported")
        if (hero !in v.heroes) throw FormationRejected("Hero is not owned", ERROR_HERO)
        if (JInt(hero) !in v.offlineHeroUids) throw FormationRejected("Hero is not on the bench (lineup, alternate team or unowned)", ERROR_HERO)
        if (hero in excludedUids.toSet()) throw FormationRejected("Hero is on exploration or mining", ERROR_HERO)
        val slot = slot(v.formation, slotId)
        val oldValue = slot["hero_uid"]
        val old = if (truthy(oldValue)) oldValue!!.long else null
        if (old != null && old in leaderUids.toSet()) throw FormationRejected("The leader/guiding hero's slot cannot be substituted")
        val packets = mutableListOf<Frame>(S_BENCH_REMOVE to countedUids(listOf(hero)))
        v.offlineHeroUids.remove(JInt(hero))
        val stripped = ArrayList<Long>()
        var positionAssigned: Long? = null
        if (old != null) {
            val rebirthValue = v.heroes.getValue(hero)[19L]
            val incomingRebirth = if (truthy(rebirthValue)) rebirthValue!!.big else BigInteger.ZERO
            val group = group0(slot)
            if (group != null && incomingRebirth < BigInteger.ONE) {
                val counts = gemCounts(v.gems)
                // Live order: the bonus Atk rune (type 25) first, then bonus Def (type 26), whatever their list order.
                val values = group.arr("values")
                val toStrip = BONUS_RUNE_TYPES.flatMap { t -> values.filter { v.gemTypes[it.long] == t } }.map { it.long }
                for (gemId in toStrip) {
                    values.remove(JInt(gemId))
                    counts[gemId] = (counts[gemId] ?: 0L) + 1
                    packets.add(gemGroupFrame(slotId, 0, values.map { it.long }))
                    packets.add(gemBagFrame(gemId, counts.getValue(gemId)))
                    stripped.add(gemId)
                }
                v.gems = gemsSectionWith(counts)
            }
        }
        slot["hero_uid"] = JInt(hero)
        packets.add(S_SET_LINEUP to WireWriter().number('B', slotId).number('I', hero).bytes())
        if (old != null) {
            sortedInsert(v.offlineHeroUids, old)
            packets.add(S_BENCH_ADD to countedUids(listOf(old)))
        } else {
            val used = LinkedHashSet<BigInteger>()
            for (s in v.formation) {
                val o = s.asObj
                if (o["slot_id"]!!.big.let { it >= BigInteger.ZERO && it < BigInteger.valueOf(6) } && hasHero(o) && o !== slot) used.add(o["flag"]!!.big)
            }
            val highest = used.maxOrNull() ?: BigInteger.valueOf(-1)
            val higher = POSITIONS.filter { BigInteger.valueOf(it) !in used && BigInteger.valueOf(it) > highest }
            val free = higher.ifEmpty { POSITIONS.filter { BigInteger.valueOf(it) !in used } }
            if (free.isEmpty()) throw FormationRejected("No free battle position")
            positionAssigned = free[0]
            slot["flag"] = JInt(positionAssigned)
            packets.add(S_POSITION to WireWriter().number('B', slotId).number('B', positionAssigned).bytes())
        }
        return FormationPlan(v, jobj("slot" to slotId, "hero_in" to hero, "hero_out" to old, "stripped_runes" to stripped,
            "position_assigned" to positionAssigned, "policies" to listOf(if (old != null) BONUS_RUNE_POLICY else EMPTY_SLOT_POSITION_POLICY)), packets)
    }

    fun planPosition(request: JObj, view: View): FormationPlan {
        val v = view.deepCopy()
        val slotId = request.long("slot")
        val position = request.long("position")
        if (slotId !in MAIN_SLOTS || position !in POSITIONS) throw FormationRejected("Slot or position out of range")
        val slot = slot(v.formation, slotId)
        if (!hasHero(slot)) throw FormationRejected("Slot is empty", ERROR_HERO)
        val oldPosition = slot["flag"]!!
        if (oldPosition == JInt(position)) throw FormationRejected("Hero already holds that position")
        val partner = v.formation.map { it.asObj }.filter {
            it !== slot && it["slot_id"]!!.big.let { id -> id >= BigInteger.ZERO && id < BigInteger.valueOf(6) } && hasHero(it) && it["flag"] == JInt(position)
        }
        val packets = ArrayList<Frame>()
        if (partner.isNotEmpty()) {
            partner[0]["flag"] = oldPosition
            packets.add(S_POSITION to WireWriter().number('B', partner[0]["slot_id"]!!).number('B', oldPosition).bytes())
        }
        slot["flag"] = JInt(position)
        packets.add(S_POSITION to WireWriter().number('B', slotId).number('B', position).bytes())
        return FormationPlan(v, jobj("slot" to slotId, "position" to position, "partner" to partner.firstOrNull()?.get("slot_id")), packets)
    }

    fun planCaptain(request: JObj, view: View): FormationPlan {
        val v = view.deepCopy()
        val slotId = request.long("slot")
        if (slotId !in MAIN_SLOTS || !hasHero(slot(v.formation, slotId))) throw FormationRejected("Captain slot must hold a hero", ERROR_HERO)
        if (v.captainSlot == JInt(slotId)) throw FormationRejected("Already captain")
        v.captainSlot = JInt(slotId)
        return FormationPlan(v, jobj("slot" to slotId), listOf(S_CAPTAIN to WireWriter().number('B', slotId).bytes()))
    }

    /** Gear; `equipPosition(config)` -> position (equip field 104 - 1) or null. */
    fun planEquip(request: JObj, view: View, equipPosition: (Long) -> Long?): FormationPlan {
        val v = view.deepCopy()
        val slotId = request.long("slot")
        val position = request.long("position")
        val uid = request.long("uid")
        if (slotId !in MAIN_SLOTS || position !in POSITIONS) throw FormationRejected("Slot or position out of range")
        val slot = slot(v.formation, slotId)
        if (!hasHero(slot)) throw FormationRejected("Slot is empty", ERROR_HERO)
        val current = LinkedHashMap<Long, Long>()
        for (pair in slot.arr("assignments")) current[pair.asArr[0].long] = pair.asArr[1].long
        val old = current[position]
        val packets = ArrayList<Frame>()
        if (uid != 0L) {
            if (uid !in v.equipment || JInt(uid) !in v.bagEquipmentUids) throw FormationRejected("Gear is not in the bag", ERROR_GEAR)
            if (equipPosition(v.equipment.getValue(uid)[1].long) != position) throw FormationRejected("Gear does not fit that position", ERROR_GEAR_POSITION)
        } else if (old == null) {
            throw FormationRejected("Nothing to unequip", ERROR_GEAR)
        }
        if (old != null) {
            sortedInsert(v.bagEquipmentUids, old)
            packets.add(S_EQUIP_BAG_ADD to countedUids(listOf(old)))
            packets.add(S_SET_EQUIP to WireWriter().number('B', slotId).number('B', position).number('I', 0L).bytes())
            current.remove(position)
        }
        if (uid != 0L) {
            v.bagEquipmentUids.remove(JInt(uid))
            packets.add(S_EQUIP_BAG_REMOVE to countedUids(listOf(uid)))
            packets.add(S_SET_EQUIP to WireWriter().number('B', slotId).number('B', position).number('I', uid).bytes())
            current[position] = uid
        }
        slot["assignments"] = JArr(current.keys.sorted().mapTo(ArrayList()) { jarr(it, current.getValue(it)) })
        return FormationPlan(v, jobj("slot" to slotId, "position" to position, "uid_in" to (if (uid != 0L) uid else null), "uid_out" to old), packets)
    }

    /** Jewelry between the unequipped list and the slot's formation blocks_40. */
    fun planJewel(request: JObj, view: View, jewelPosition: (Long) -> Long?): FormationPlan {
        val v = view.deepCopy()
        val slotId = request.long("slot")
        val position = request.long("position")
        val uid = request.long("uid")
        if (slotId !in MAIN_SLOTS || position !in POSITIONS) throw FormationRejected("Slot or position out of range")
        val slot = slot(v.formation, slotId)
        if (!hasHero(slot)) throw FormationRejected("Slot is empty", ERROR_HERO)
        val blocks = LinkedHashMap<Long, ByteArray>()
        for (b in slot.arr("blocks_40")) blocks[b.asObj.long("id")] = b.asObj.str("raw_hex").hexBytes()
        val listed = LinkedHashMap<Long, JObj>()
        for (e in v.jewelList) listed[e.asObj.arr("record")[0].long] = e.asObj
        val packets = ArrayList<Frame>()
        if (uid != 0L) {
            if (uid !in listed) throw FormationRejected("Jewelry is not in the unequipped list", ERROR_JEWEL)
            if (jewelPosition(listed.getValue(uid).arr("record")[1].long) != position) throw FormationRejected("Jewelry does not fit that position", ERROR_GEAR_POSITION)
        } else if (position !in blocks) {
            throw FormationRejected("Nothing to unequip", ERROR_JEWEL)
        }
        var out: Long? = null
        if (position in blocks) {
            val (record, tail) = jewelBlockRecord(blocks.remove(position)!!)
            out = record[0].long
            v.jewelList.add(jobj("record" to record, "tail" to tail.toHexString()))
            val sorted = v.jewelList.sortedBy { it.asObj.arr("record")[0].big }
            v.jewelList.clear()
            v.jewelList.addAll(sorted)
            packets.add(S_JEWEL_LIST_ADD to recordList(listOf(record)))
            packets.add(S_SET_JEWEL to WireWriter().number('B', slotId).number('B', position).number('I', 0L).bytes())
        }
        if (uid != 0L) {
            val entry = listed.getValue(uid)
            v.jewelList = JArr(v.jewelList.filterTo(ArrayList()) { it.asObj.arr("record")[0].long != uid })
            val tailValue = entry["tail"]
            val tail = if (truthy(tailValue)) (tailValue as JStr).value.hexBytes() else DEFAULT_JEWEL_TAIL
            blocks[position] = jewelBlock(entry.arr("record"), tail)
            packets.add(S_SET_JEWEL to WireWriter().number('B', slotId).number('B', position).number('I', uid).bytes())
            packets.add(S_JEWEL_LIST_REMOVE to countedUids(listOf(uid)))
        }
        slot["blocks_40"] = JArr(blocks.keys.sorted().mapTo(ArrayList()) { jobj("id" to it, "raw_hex" to blocks.getValue(it).toHexString()) })
        return FormationPlan(v, jobj("slot" to slotId, "position" to position, "uid_in" to (if (uid != 0L) uid else null), "uid_out" to out,
            "default_tail_used" to (uid != 0L && !truthy(listed.getValue(uid)["tail"]))), packets)
    }

    fun planRuneEquip(request: JObj, view: View): FormationPlan {
        val v = view.deepCopy()
        val slotId = request.long("slot")
        val groupId = request.long("group")
        val gemId = request.long("gem_id")
        if (slotId !in MAIN_SLOTS || groupId != 0L) throw FormationRejected("Only rune group 0 of a main slot is supported")
        val slot = slot(v.formation, slotId)
        if (!hasHero(slot)) throw FormationRejected("Slot is empty", ERROR_HERO)
        val gemType = v.gemTypes[gemId] ?: throw FormationRejected("Unknown rune", ERROR_ITEMS)
        val counts = gemCounts(v.gems)
        if ((counts[gemId] ?: 0L) < 1) throw FormationRejected("Rune is not in the bag", ERROR_ITEMS)
        val group = group0(slot, create = true)!!
        val values = group.arr("values")
        val same = values.indices.filter { v.gemTypes[values[it].long] == gemType }
        val packets = ArrayList<Frame>()
        var replaced: Long? = null
        if (same.isNotEmpty()) {
            val index = same[0]
            val r = values[index].long
            replaced = r
            counts[r] = (counts[r] ?: 0L) + 1
            packets.add(gemBagFrame(r, counts.getValue(r)))
            values[index] = JInt(gemId)
        } else {
            if (values.size >= 6) throw FormationRejected("Rune group is full")
            values.add(JInt(gemId))
        }
        counts[gemId] = counts.getValue(gemId) - 1
        packets.add(gemBagFrame(gemId, counts.getValue(gemId)))
        packets.add(gemGroupFrame(slotId, 0, values.map { it.long }))
        v.gems = gemsSectionWith(counts)
        return FormationPlan(v, jobj("slot" to slotId, "gem_in" to gemId, "gem_out" to replaced), packets)
    }

    fun planRuneUnequip(request: JObj, view: View): FormationPlan {
        val v = view.deepCopy()
        val slotId = request.long("slot")
        val groupId = request.long("group")
        val gemId = request.long("gem_id")
        if (slotId !in MAIN_SLOTS || groupId != 0L) throw FormationRejected("Only rune group 0 of a main slot is supported")
        val group = group0(slot(v.formation, slotId))
        if (group == null || JInt(gemId) !in group.arr("values")) throw FormationRejected("Rune is not equipped there", ERROR_ITEMS)
        val values = group.arr("values")
        values.remove(JInt(gemId))
        val counts = gemCounts(v.gems)
        counts[gemId] = (counts[gemId] ?: 0L) + 1
        v.gems = gemsSectionWith(counts)
        return FormationPlan(v, jobj("slot" to slotId, "gem_out" to gemId),
            listOf(gemGroupFrame(slotId, 0, values.map { it.long }), gemBagFrame(gemId, counts.getValue(gemId))))
    }

    /** Rune combine; `gemRows[id]` = next (field 105) and cost (field 106). */
    fun planRuneCombine(request: JObj, view: View, gemRows: Map<Long, FormationInputs.GemRow>): FormationPlan {
        val v = view.deepCopy()
        val mode = request.long("mode")
        val gemId = request.long("gem_id")
        if (mode != 1L && mode != 2L) throw FormationRejected("Unknown combine mode")
        val row = gemRows[gemId]
        if (row == null || row.next == 0L || row.cost < 2 || row.next !in gemRows) throw FormationRejected("This rune cannot be combined", ERROR_ITEMS)
        val counts = gemCounts(v.gems)
        val have = counts[gemId] ?: 0L
        val times = if (mode == 1L) 1L else Math.floorDiv(have, row.cost)
        if (times < 1 || have < times * row.cost) throw FormationRejected("Not enough runes to combine", ERROR_ITEMS)
        counts[gemId] = have - times * row.cost
        counts[row.next] = (counts[row.next] ?: 0L) + times
        v.gems = gemsSectionWith(counts)
        val packets = listOf(gemBagFrame(gemId, counts.getValue(gemId)), gemBagFrame(row.next, counts.getValue(row.next)),
            combineRewardFrame(row.next, times))
        return FormationPlan(v, jobj("gem_id" to gemId, "produced" to row.next, "times" to times, "consumed" to times * row.cost), packets)
    }
}
