package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.PyRandom
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.Inventory
import io.github.okexodus.openknights.protocol.PlayerSections
import io.github.okexodus.openknights.protocol.TypedValues
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.StateStore
import java.math.BigInteger

/**
 * The shared owned view and frame encoders of `acquisition.py` (the item-use / box / merge planners belong to the
 * acquisition group). Every transaction plans on an [Owned] view of one committed revision; the store applies what it
 * recorded.
 */
object Acquisition {
    const val S_ITEM_ADD = 64
    const val S_ITEM_REMOVE = 66
    const val S_ITEM_UPDATE = 68
    const val S_ITEM_USE = 70
    const val S_ROLE = 128
    const val S_HERO_ADD = 32
    const val S_BENCH_ADD = 38
    const val S_EQUIP_ADD = 96
    const val S_EQUIP_BAG_ADD = 100
    const val S_COLLECTION = 546
    const val S_ACHIEVEMENT = 578
    const val S_GOD_SKILLS = 2850
    const val S_ACTIVITY = 1184

    const val ERROR_INVALID = 102
    const val ERROR_ITEM_MISSING = 2000
    const val ERROR_WRONG_TYPE = 2001
    const val ERROR_NOT_ENOUGH = 2002
    const val ERROR_LEVEL = 2003
    const val ERROR_TOO_MANY = 2004
    const val ERROR_NOT_MERGEABLE = 2005
    const val ERROR_VIP = 2007
    const val ERROR_RESOURCES = 4000
    const val ERROR_HEROES_FULL = 1016
    val BAG_FULL = mapOf(1L to 2013, 2L to 2014, 3L to 2015)

    const val GOLD = 6L
    const val DIAMOND = 8L
    const val STAMINA = 9L
    const val ENERGY = 10L
    /** Currency placeholder items → role property (paid into the currency, not stored as items). */
    val CURRENCY_ITEM_ROLE = linkedMapOf(20001L to GOLD, 20002L to 7L, 20003L to DIAMOND, 20004L to 11L, 20005L to 12L,
        20009L to STAMINA, 20010L to ENERGY, 20016L to 32L, 20017L to 33L, 20018L to 34L)
    const val ROLE_LEVEL = 3L
    const val VIP_LEVEL = 27L
    const val HERO_BOOK = 16L
    const val EQUIP_BOOK = 17L
    val BOOK_ACHIEVEMENT = mapOf(HERO_BOOK to 2L, EQUIP_BOOK to 3L)
    val COLLECTION_KIND = mapOf("hero_collection" to 1, "equip_collection" to 2)
    const val HERO_WIRE_LIMIT = 255
    const val EQUIP_WIRE_LIMIT = 255
    val FRESH_EQUIPMENT = listOf(1L, 0L, 1L, 0L, 0L)

    /** A refusal with the native client error-text code (answered S6 `code`). */
    open class Rejected(message: String, val code: Int = ERROR_INVALID) : IllegalArgumentException(message)

    /**
     * `seed_for(request_bytes, revision, salt)`: the deterministic, recorded seed of a labeled local draw — the first 8
     * bytes (little-endian) of SHA-256 of `"<revision>:<salt>:"` + the request bytes.
     */
    fun seedFor(requestBytes: ByteArray, revision: Long, salt: String = ""): BigInteger {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("$revision:$salt:".toByteArray(Charsets.UTF_8))
        digest.update(requestBytes)
        return BigInteger(1, digest.digest().copyOfRange(0, 8).reversedArray())
    }

    fun u(value: Long, width: Int, label: String): Long {
        if (value < 0 || (width < 64 && value >= (1L shl width))) throw Rejected("$label must fit uint$width")
        return value
    }

    // --- frame encoders ---

    fun itemUpdatePayload(pairs: List<Pair<Long, Long>>): ByteArray {
        val w = WireWriter().u8(pairs.size)
        pairs.forEach { (uid, count) -> w.u32(u(uid, 32, "uid")).u32(u(count, 32, "count")) }
        return w.bytes()
    }

    fun uidListPayload(uids: List<Long>): ByteArray {
        val w = WireWriter().u8(uids.size)
        uids.forEach { w.u32(u(it, 32, "uid")) }
        return w.bytes()
    }

    /** S128: a typed map of (field id, tag, bits), the tag as stored in opcode 18. */
    fun roleUpdatePayload(fields: List<Triple<Long, Long, BigInteger>>): ByteArray =
        TypedValues.encodeFieldsBytes(JArr(fields.mapTo(ArrayList()) { (id, tag, bits) -> jobj("id" to id, "value" to jobj("tag" to tag, "bits" to bits)) }))

    fun heroAddPayload(fieldsList: List<JArr>): ByteArray {
        val w = WireWriter().u8(fieldsList.size)
        fieldsList.forEach { TypedValues.encodeFields(it, w) }
        return w.bytes()
    }

    fun equipmentAddPayload(records: List<List<Long>>): ByteArray {
        val w = WireWriter().u8(records.size)
        records.forEach { r -> w.u32(r[0]).u32(r[1]).u32(r[2]).u32(r[3]).u8(r[4].toInt()).u8(r[5].toInt()).u32(r[6]) }
        return w.bytes()
    }

    fun collectionPayload(kind: Int, template: Long): ByteArray = WireWriter().u8(kind).u32(template).bytes()

    fun achievementPayload(entry: JArr): ByteArray = WireWriter().u8(entry[0].long.toInt()).u8(entry[1].long.toInt()).u32(entry[2].long).bytes()

    fun heroValues(fields: JArr): Map<Long, JValue?> =
        LinkedHashMap<Long, JValue?>().also { m -> fields.forEach { f -> m[f.asObj.long("id")] = f.asObj.obj("value")["bits"] } }

    /** The opcode-18 form of a new hero: the S32 map plus 19 / 20 / 21 / 24 as tag-5 zero, ascending by id. */
    fun storedHeroFields(s32: JArr): JArr {
        val byId = java.util.TreeMap<Long, JValue>()
        s32.forEach { byId[it.asObj.long("id")] = it }
        for (fid in listOf(19L, 20L, 21L, 24L)) if (fid !in byId) byId[fid] = jobj("id" to fid, "value" to jobj("tag" to 5, "bits" to 0))
        return JArr(byId.values.toMutableList())
    }

    // --- buffs ---

    /** [(buff, seconds left)] of the running buffs, ascending by buff id. */
    fun buffView(document: JValue?, now: Long): List<Pair<BigInteger, BigInteger>> {
        val doc = if (Py.truthy(document)) document as JObj else JObj()
        val ends = (doc["ends"] ?: JObj()) as JObj
        return ends.entries.map { PyValues.parseInt(it.key) to it.value }.sortedBy { it.first }
            .filter { PyDocs.compare(it.second, JInt(now)) > 0 }.map { it.first to (PyDocs.int(it.second) - BigInteger.valueOf(now)) }
    }

    /** The S18 `buffs` section. */
    fun buffsSection(document: JValue?, now: Long): JObj {
        val entries = buffView(document, now).map { (b, left) -> jobj("wire_values" to listOf(b, left)) }
        return jobj("count" to entries.size, "entries" to entries)
    }

    /** S1056: `u8 n, n x (u32 buff, u32 seconds left)` (the seconds capped at u32). */
    fun buffsPayload(document: JValue?, now: Long): ByteArray {
        val view = buffView(document, now)
        val w = WireWriter().u8(view.size)
        val cap = BigInteger.valueOf(0xFFFFFFFFL)
        for ((b, left) in view) w.number('I', b).number('I', left.min(cap))
        return w.bytes()
    }

    // --- item use, choose box, merge (C73 / C4099 / C803 / C801) -------------------------------------------------

    const val USE_OPCODE = 73
    const val CHOOSE_OPCODE = 4099
    const val MERGE_OPCODE = 803
    const val MERGE_SINGLE_OPCODE = 801
    const val S_CHOOSE = 4130
    const val S_MERGE = 820
    const val S_SINGLE_MERGE = 818
    /** Resource codes whose Reward member and role property are both evidenced. */
    val RESOURCES = linkedMapOf(90001L to ("gold" to GOLD), 91001L to ("stamina" to STAMINA), 91002L to ("energy" to ENERGY))
    /** Boost Scrolls: item 106 = 90014, 107 = the buff, 207 = its duration (S1056 replaces the buff list). */
    const val BUFF_USE = 90014L
    const val S_BUFFS = 1056
    const val BUFF_PROFILE = "buff_state_v1"
    val BOX_CONTENT = mapOf("f111" to "item", "f113" to "equipment", "f114" to "hero")
    val BOX_UNRESOLVED_COLUMNS = listOf("f108", "f109", "f110", "f201", "f202", "f203")
    /** An empty draw (item column with count 0). */
    const val NOTHING = "nothing"
    /** Operator decision 2026-09-12: the Magic Bead boxes' next-tier chance per 10,000 (box template → (bead, chance)). */
    val BEAD_UPGRADE_ODDS = mapOf(370439L to (370440L to 3300L), 370440L to (370441L to 2500L), 370441L to (370442L to 2000L),
        370442L to (370443L to 1500L), 370443L to (370444L to 1000L), 370444L to (370445L to 600L), 370445L to (370446L to 300L))
    val BOX_ROLE_COLUMNS = mapOf("f105" to "gold", "f106" to "exploit", "f107" to "courage")
    const val ROLE_EXPLOIT_ID = 7L
    const val ROLE_COURAGE = 29L
    /** Leader class items per class (Warrior, Mage, Hunter): a box gives the item of the player's own leader class. */
    val CLASS_ITEM_SETS = listOf(listOf(30225L, 30226L, 30227L), listOf(30228L, 30229L, 30230L))
    val CHOOSE_KIND = mapOf(81000L to "item", 81001L to "hero", 81002L to "equipment")
    val MERGE_KIND = mapOf(81000L to "item", 81001L to "hero", 81002L to "equipment")
    /** The 11 material ids the client treats as currencies → role property (null = not implemented, refused). */
    val MERGE_CURRENCIES: Map<Long, Long?> = linkedMapOf(90001L to GOLD, 90002L to 7L, 90003L to DIAMOND, 90004L to 11L, 90005L to 12L,
        90006L to null, 90013L to null, 91001L to STAMINA, 91002L to ENERGY, 91003L to null, 91004L to null)
    const val POLICY_PROFILE = "acquisition_rng_policy_v1"
    /** Draws added after the first policy document; a route needing one refuses when its policy lacks it. */
    val OPTIONAL_POLICY_DRAWS = mapOf("lucky_refresh" to "uniform_over_observed_pools", "fuse_roll" to "displayed_rate_plus_luck_v1")

    /** `policy_allows(loaded, key)` on the policy document as the service holds it. */
    fun policyAllows(policy: JObj?, key: String): Boolean =
        policy != null && policy.isNotEmpty() && policy[key] == io.github.okexodus.openknights.exact.JStr(OPTIONAL_POLICY_DRAWS.getValue(key))

    fun decodeUseRequest(payload: ByteArray): JObj {
        if (payload.size != 8) throw Rejected("C73 is u32 uid, u32 count")
        val r = WireReader(payload)
        return jobj("uid" to r.u32(), "count" to r.u32())
    }

    fun decodeChooseRequest(payload: ByteArray): JObj {
        if (payload.size != 9) throw Rejected("C4099 is u32 uid, u32 count, u8 option")
        val r = WireReader(payload)
        return jobj("uid" to r.u32(), "count" to r.u32(), "option" to r.u8())
    }

    fun decodeMergeRequest(payload: ByteArray): JObj {
        if (payload.size != 6) throw Rejected("C803 is u32 uid, u16 count")
        val r = WireReader(payload)
        return jobj("uid" to r.u32(), "count" to r.number('H').long)
    }

    /** C801 `u32 uid`: sent instead of C803 when the selected stack holds one piece. */
    fun decodeSingleMergeRequest(payload: ByteArray): JObj {
        if (payload.size != 4) throw Rejected("C801 is u32 uid")
        return jobj("uid" to WireReader(payload).u32(), "count" to 1, "single" to true)
    }

    /** S818 `u8 status (0), Reward, u8 kind`. */
    fun singleMergeResultPayload(reward: JObj): ByteArray = byteArrayOf(0) + BattleReport.encodeReward(reward) + byteArrayOf(0)

    /** S820 `u16 times, Reward, u16 x3`. */
    fun mergeResultPayload(times: Long, reward: JObj, bonuses: List<Long> = listOf(0, 0, 0)): ByteArray =
        WireWriter().number('H', times).raw(BattleReport.encodeReward(reward)).number('H', bonuses[0]).number('H', bonuses[1]).number('H', bonuses[2]).bytes()

    fun emptyReward(): JObj = BattleReport.emptyReward(14)

    /** `int(inputs.property(key) or default)`. */
    fun propertyInt(inputs: AcquisitionInputs, key: Long, default: Long): Long {
        val raw = inputs.property(key)
        return if (raw.isNullOrEmpty()) default else PyValues.parseLong(raw)
    }

    /** `classify_use(row, inputs)`: how item use resolves for a template (a dict with "family"). */
    fun classifyUse(row: JObj?, inputs: AcquisitionInputs): JObj {
        if (row == null) return jobj("family" to "unknown")
        if (row.long("type_104") == 4L) return jobj("family" to "merge_only")
        if (row.long("type_104") != 1L) return jobj("family" to "not_usable")
        val use = row.long("use_106")
        val value = row.long("value_107")
        if (use == 80003L) return jobj("family" to "choose_box")
        if (use in RESOURCES) return jobj("family" to "resource", "code" to use, "amount" to value)
        if (use == 81000L) return jobj("family" to "grant", "kind" to "item", "id" to value)
        if (use == 81001L) return jobj("family" to "grant", "kind" to "hero", "id" to value)
        if (use == 81002L) return jobj("family" to "grant", "kind" to "equipment", "id" to value)
        if (use == 80001L) {
            val rows = inputs.boxGroup(value)
            if (rows.isEmpty()) return jobj("family" to "box_unresolved", "group" to value)
            return jobj("family" to "box", "group" to value).also { it.putAll(boxProfile(rows)) }
        }
        if (use == BUFF_USE) {
            val seconds = buffSeconds(row, inputs)
            if (seconds != 0L && inputs.single("buff", value) != null) return jobj("family" to "buff", "buff" to value, "seconds" to seconds)
        }
        return jobj("family" to "unsupported_effect", "code" to use)
    }

    /** `buff_seconds(row, inputs)`: item column 207 when it is all digits, else 0. */
    fun buffSeconds(row: JObj, inputs: AcquisitionInputs): Long {
        val raw = PyValues.strip(inputs.single("item", row.long("template"))?.field("207") ?: "")
        return if (PyValues.isDigit(raw)) PyValues.parseLong(raw) else 0L
    }

    /** One box outcome `(kind, id, count)`. */
    data class Outcome(val kind: String, val id: Long, val count: Long)

    private fun f(row: JObj, column: String): Long = row.long(column)

    /** `bead_rows(template, rows)`: the operator's upgrade chance on the next-tier bead slot (copies; other boxes unchanged). */
    fun beadRows(template: Long, rows: List<JObj>): List<JObj> {
        val (bead, chance) = BEAD_UPGRADE_ODDS[template] ?: return rows
        return rows.map { row ->
            if (row["f111"] == JInt(bead)) JObj(LinkedHashMap(row.map)).also {
                it["weight_104"] = JInt(if ((row.longOrNull("f112") ?: 0L) > 0) chance else 10000 - chance)
            } else row
        }
    }

    /** `box_outcome(row)`: one box row → one grant, or null when its columns are outside the supported set. */
    fun boxOutcome(row: JObj): Outcome? {
        if (BOX_UNRESOLVED_COLUMNS.any { f(row, it) != 0L }) return null
        val kinds = listOf("f105", "f106", "f107", "f111", "f113", "f114", "f115", "f117").filter { f(row, it) != 0L }
        if (kinds.size != 1) return null
        val k = kinds[0]
        if (k == "f117") return Outcome("jewelry", f(row, "f117"), 1)
        BOX_ROLE_COLUMNS[k]?.let { name -> return if (f(row, k) > 0) Outcome(name, 0, f(row, k)) else null }
        if (k == "f115") return if (f(row, "f116") > 0) Outcome("gem", f(row, "f115"), f(row, "f116")) else Outcome(NOTHING, 0, 0)
        if (k == "f111") return if (f(row, "f112") > 0) Outcome("item", f(row, "f111"), f(row, "f112")) else Outcome(NOTHING, 0, 0)
        return Outcome(BOX_CONTENT.getValue(k), f(row, k), 1)
    }

    private fun positiveWeight(row: JObj): Boolean = row["slot_103"] != JNull && row["slot_103"] != null &&
        (row["weight_104"] as? JInt)?.value?.signum() == 1

    /** `box_profile(rows)`: slots → candidate rows with positive weight; supported / fixed / excluded rows. */
    fun boxProfile(rows: List<JObj>): JObj {
        val slots = LinkedHashMap<Long, MutableList<JObj>>()
        for (row in rows) {
            if (!positiveWeight(row)) continue
            slots.getOrPut(row.long("slot_103")) { ArrayList() }.add(row)
        }
        val outcomes = slots.mapValues { (_, c) -> c.map { boxOutcome(it) } }
        val supported = slots.isNotEmpty() && outcomes.values.all { v -> v.any { it != null } }
        val complete = outcomes.values.all { v -> v.all { it != null } }
        val fixed = supported && complete && outcomes.values.all { v -> v.toSet().size == 1 }
        val excluded = slots.flatMap { (s, c) -> c.zip(outcomes.getValue(s)).filter { it.second == null }.map { it.first.long("row") } }.sorted()
        val slotRows = JObj(intKeys = true)
        for (s in slots.keys.sorted()) slotRows[s.toString()] = JArr(slots.getValue(s).mapTo(ArrayList()) { it.getValue("row") })
        return jobj("slots" to slotRows, "supported" to supported, "fixed" to fixed, "excluded_rows" to excluded)
    }

    /** `draw_box(rows, rng)`: one opening — per slot (ascending) one positive-weight drawable row; fixed slots have one outcome. */
    fun drawBox(rows: List<JObj>, rng: PyRandom?): List<Triple<Long, Long, Outcome>> {
        val slots = LinkedHashMap<Long, MutableList<JObj>>()
        for (row in rows) {
            if (positiveWeight(row) && boxOutcome(row) != null) slots.getOrPut(row.long("slot_103")) { ArrayList() }.add(row)
        }
        val picked = ArrayList<Triple<Long, Long, Outcome>>()
        for (slot in slots.keys.sorted()) {
            val candidates = slots.getValue(slot)
            if (candidates.map { boxOutcome(it) }.toSet().size == 1) {
                picked.add(Triple(slot, candidates[0].long("row"), boxOutcome(candidates[0])!!))
                continue
            }
            val total = candidates.sumOf { it.long("weight_104") }
            var roll = rng!!.randrange(total)
            for (row in candidates) {
                roll -= row.long("weight_104")
                if (roll < 0) {
                    picked.add(Triple(slot, row.long("row"), boxOutcome(row)!!))
                    break
                }
            }
        }
        return picked
    }

    /** `leader_class(owned, inputs)`: hero.csv col 103 (1 Warrior, 2 Mage, 3 Hunter) of the owned leader-class hero, else null. */
    fun leaderClass(owned: Owned, inputs: AcquisitionInputs): Long? {
        fun number(row: io.github.okexodus.openknights.gamedata.GameTable.Row, key: String): Long {
            val raw = PyValues.strip(row.field(key) ?: "")
            return if (PyValues.isDigit(raw.trimStart('-'))) PyValues.parseLong(raw) else 0L
        }
        for (fields in (owned.state["heroes"] as? JArr) ?: JArr()) {
            val template = heroValues(fields.asArr)[1L]
            val row = if (Py.truthy(template)) inputs.single("hero", Math.floorDiv(template!!.long, 1000L)) else null
            if (row != null && number(row, "143") != 0L) return number(row, "103").takeIf { it != 0L }
        }
        return null
    }

    /** `class_item(template, owned, inputs)`: the player's own class variant of a leader class item. */
    fun classItem(template: Long, owned: Owned, inputs: AcquisitionInputs): Long {
        for (group in CLASS_ITEM_SETS) {
            if (template in group) {
                val cls = leaderClass(owned, inputs)
                return if (cls != null && cls in 1L..3L) group[(cls - 1).toInt()] else template
            }
        }
        return template
    }

    /** `_apply_grant(owned, kind, ident, count, reward, frames, hero_frames)`. */
    fun applyGrant(owned: Owned, kind: String, ident: Long, count: Long, reward: JObj, frames: MutableList<Frame>,
                   heroFrames: MutableList<Map<String, List<Frame>>>) {
        when (kind) {
            "item" -> {
                frames.add(owned.grantItem(ident, count))
                reward.arr("items").add(jarr(ident, count))
            }
            "hero" -> for (i in 0 until count) {
                heroFrames.add(owned.grantHero(ident).second)
                reward.arr("heroes").add(jarr(ident))
            }
            "equipment" -> for (i in 0 until count) {
                heroFrames.add(owned.grantEquipment(ident).second)
                reward.arr("equips").add(jarr(ident))
            }
            else -> throw Rejected("Unsupported grant kind $kind", ERROR_WRONG_TYPE)
        }
    }

    /** `_flatten(groups_list, keys)`. */
    fun flatten(groupsList: List<Map<String, List<Frame>>>, keys: List<String>): List<Frame> =
        groupsList.flatMap { groups -> keys.flatMap { groups[it] ?: emptyList() } }

    private val GROUP_KEYS = listOf("add", "book", "god", "activity")

    /**
     * `plan_use(request, owned, inputs, box_policy, seed, role_level, vip_level, now)`: C73. Live orders: resource S128,
     * S68, S70; box S68 (grant), S68 (consume), S70.
     */
    fun planUse(request: JObj, owned: Owned, inputs: AcquisitionInputs, boxPolicy: JObj? = null, seed: BigInteger? = null,
                roleLevel: Long? = null, vipLevel: Long? = null, now: Long? = null): Plan {
        val uid = request.long("uid")
        val count = request.long("count")
        var buffAfter: JObj? = null
        var jewelAfter: List<JValue>? = null
        if (count <= 0) throw Rejected("Use count must be positive")
        val entry = owned.item(uid)
        if (entry.timed != 0L) throw Rejected("Timed items are outside the supported profile", ERROR_WRONG_TYPE)
        if (count > entry.count) throw Rejected("Use count exceeds the stack", ERROR_NOT_ENOUGH)
        val cap = propertyInt(inputs, 970, 0)
        if (cap != 0L && count > cap) throw Rejected("Use count exceeds the client cap (property 970)")
        val row = inputs.item(entry.template)
        val resolved = classifyUse(row, inputs)
        val family = resolved.str("family")
        if (family in listOf("unknown", "not_usable", "merge_only", "choose_box")) throw Rejected("Item cannot be used this way ($family)", ERROR_WRONG_TYPE)
        if (family in listOf("box_unresolved", "unsupported_effect") || (family == "box" && !resolved.bool("supported"))) {
            throw Rejected("Item use not implemented ($family)", ERROR_WRONG_TYPE)
        }
        row!!
        if (row.long("vip_204") != 0L && (vipLevel == null || vipLevel < row.long("vip_204"))) throw Rejected("VIP level too low", ERROR_VIP)
        if (row.long("level_105") > 1 && (roleLevel == null || roleLevel < row.long("level_105"))) {
            throw Rejected("You are not a high enough level to use this item", ERROR_LEVEL)
        }
        var keyFrames: List<Frame> = emptyList()
        if (row.long("key_205") != 0L && row.long("key_count_206") != 0L) keyFrames = owned.consumeTemplate(row.long("key_205"), row.long("key_count_206") * count)
        val reward = emptyReward()
        val frames = ArrayList<Frame>()
        val heroFrames = ArrayList<Map<String, List<Frame>>>()
        val draws = JArr()
        var evidence = "capture_observed_or_config_fixed"
        when (family) {
            "resource" -> {
                val (name, field) = RESOURCES.getValue(resolved.long("code"))
                val amount = resolved.long("amount") * count
                frames.add(owned.roleAdd(field, amount))
                reward[name] = JInt(amount)
            }
            "grant" -> {
                val kind = resolved.str("kind")
                val ident = if (kind == "item") classItem(resolved.long("id"), owned, inputs) else resolved.long("id")
                applyGrant(owned, kind, ident, count, reward, frames, heroFrames)
            }
            "buff" -> {
                if (now == null) throw Rejected("A buff needs the device clock", ERROR_WRONG_TYPE)
                val stored = owned.current.document("buff_state")
                val document = (if (Py.truthy(stored)) stored!! else jobj("profile" to BUFF_PROFILE, "ends" to JObj())).deepCopy() as JObj
                val key = resolved.long("buff").toString()
                val seconds = resolved.long("seconds") * count
                val ends = document.obj("ends")
                val old = ends[key] ?: JInt(0)
                ends[key] = JInt((if (PyDocs.compare(JInt(now), old) >= 0) BigInteger.valueOf(now) else PyDocs.int(old)) + BigInteger.valueOf(seconds))
                val kept = JObj()
                for ((k, v) in ends) if (PyDocs.compare(v, JInt(now)) > 0) kept[k] = v
                document["ends"] = kept
                owned.state.obj("subsystems")["buffs"] = buffsSection(document, now)
                frames.add(S_BUFFS to buffsPayload(document, now))
                reward.arr("buffs").add(jarr(resolved.long("buff"), seconds))
                buffAfter = document
            }
            else -> {  // box
                val rows = beadRows(entry.template, inputs.boxGroup(resolved.long("group")))
                val rng: PyRandom?
                if (resolved.bool("fixed")) rng = null
                else {
                    if (boxPolicy == null) throw Rejected("Random box without the labeled local box policy", ERROR_WRONG_TYPE)
                    evidence = "preservation_policy_box_draw"
                    rng = PyRandom.seeded(seed!!)
                }
                val items = LinkedHashMap<Long, Long>()
                var gold = 0L
                val roles = LinkedHashMap<String, Long>()
                val gems = LinkedHashMap<Long, Long>()
                val jewels = ArrayList<Long>()
                for (i in 0 until count) {
                    for ((slot, boxRow, outcome) in drawBox(rows, rng)) {
                        val (kind, drawn, qty) = outcome
                        draws.add(jobj("slot" to slot, "row" to boxRow, "kind" to kind, "id" to drawn, "count" to qty))
                        if (kind == NOTHING) continue
                        when (kind) {
                            "jewelry" -> repeat(qty.toInt()) { jewels.add(drawn) }
                            "item" -> {
                                val ident = classItem(drawn, owned, inputs)
                                items[ident] = (items[ident] ?: 0L) + qty
                            }
                            "gold" -> gold += qty
                            "gem" -> gems[drawn] = (gems[drawn] ?: 0L) + qty
                            "exploit", "courage" -> roles[kind] = (roles[kind] ?: 0L) + qty
                            else -> applyGrant(owned, kind, drawn, qty, reward, frames, heroFrames)
                        }
                    }
                }
                for ((ident, qty) in items) applyGrant(owned, "item", ident, qty, reward, frames, heroFrames)
                if (gold != 0L) {
                    frames.add(owned.roleAdd(GOLD, gold))
                    reward["gold"] = JInt(gold)
                }
                roles["exploit"]?.takeIf { it != 0L }?.let {
                    frames.add(owned.roleAdd(ROLE_EXPLOIT_ID, it))
                    reward["exploit"] = JInt(it)
                }
                roles["courage"]?.takeIf { it != 0L }?.let { frames.add(owned.roleAdd(ROLE_COURAGE, it)) }
                if (gems.isNotEmpty()) {
                    val counts = EquipFormation.gemCounts(owned.state.obj("subsystems").obj("gems"))
                    for ((rune, qty) in gems) {
                        counts[rune] = (counts[rune] ?: 0L) + qty
                        frames.add(EquipFormation.gemBagFrame(rune, counts.getValue(rune)))
                    }
                    owned.state.obj("subsystems")["gems"] = EquipFormation.gemsSectionWith(counts)
                    reward["gems"] = JArr(gems.entries.sortedBy { it.key }.mapTo(ArrayList()) { jarr(it.key, it.value) })
                }
                if (jewels.isNotEmpty()) {
                    val listed = owned.current.jewelEntriesView ?: throw Rejected("The jewelry list was not served this session", ERROR_WRONG_TYPE)
                    val jewelEntries = JArr(listed.mapTo(ArrayList()) { JObj(LinkedHashMap(it.asObj.map)) })
                    for (template in LinkedHashSet(jewels)) frames += EventHall.grantJewels(owned, template, jewels.count { it == template }.toLong(), jewelEntries)
                    reward["jewels"] = JArr(jewels.mapTo(ArrayList()) { jarr(it) })
                    jewelAfter = jewelEntries.sortedBy { it.asObj.arr("record")[0].long }
                }
            }
        }
        val consumeFrame = owned.consume(uid, count)
        val packets = flatten(heroFrames, GROUP_KEYS) + frames + keyFrames + listOf(consumeFrame, S_ITEM_USE to BattleReport.encodeReward(reward))
        val shown = JObj()
        for ((k, v) in resolved) if (k != "slots") shown[k] = v
        val plan = Plan(jobj("uid" to uid, "template" to entry.template, "count" to count, "family" to family, "resolved" to shown,
            "draws" to draws, "reward" to reward, "evidence_class" to evidence,
            "seed" to (if (evidence == "preservation_policy_box_draw") seed else null)), packets)
        if (buffAfter != null) {
            plan["buff_state_after"] = buffAfter
            plan["evidence_class"] = "native_use_table_policy_buff"
        }
        if (jewelAfter != null) plan["jewel_entries_after"] = jewelAfter
        return plan
    }

    /** `plan_choose(request, owned, inputs)`: C4099. Live order (item option): S66/S68 consume, S64/S68 grant, S4130 Reward. */
    fun planChoose(request: JObj, owned: Owned, inputs: AcquisitionInputs): Plan {
        val uid = request.long("uid")
        val count = request.long("count")
        val option = request.long("option")
        if (count <= 0) throw Rejected("Choose count must be positive")
        val entry = owned.item(uid)
        val row = inputs.item(entry.template)
        if (row == null || row.long("type_104") != 1L || row.long("use_106") != 80003L) throw Rejected("Item is not a choose box", ERROR_WRONG_TYPE)
        val options = inputs.chooseBox(row.long("value_107"))
        if (options.isNullOrEmpty() || option >= options.size) throw Rejected("Choose-box option out of range")
        val chosen = options[option.toInt()].asObj
        val kind = CHOOSE_KIND[chosen.long("type")] ?: throw Rejected("Choose-box option type ${chosen.long("type")} not implemented", ERROR_WRONG_TYPE)
        if (count > entry.count) throw Rejected("Choose count exceeds the stack", ERROR_NOT_ENOUGH)
        val reward = emptyReward()
        val frames = ArrayList<Frame>()
        val heroFrames = ArrayList<Map<String, List<Frame>>>()
        val consumeFrame = owned.consume(uid, count)
        val quantity = chosen.long("count") * count
        applyGrant(owned, kind, chosen.long("id"), quantity, reward, frames, heroFrames)
        val packets = listOf(consumeFrame) + flatten(heroFrames, GROUP_KEYS) + frames + listOf(S_CHOOSE to BattleReport.encodeReward(reward))
        return Plan(jobj("uid" to uid, "template" to entry.template, "count" to count, "option" to option, "chosen" to chosen,
            "reward" to reward, "evidence_class" to (if (kind == "item") "capture_observed" else "structural_candidate")), packets)
    }

    /**
     * `plan_merge(request, owned, inputs)`: C803 / C801. Live orders — hero: 32, 38, [546, 578, 128], 2850, 1184, 68/66,
     * 820, 128 (Gold); gear: 96, 100, [546, 578, 128], 66/68, 820, 128; item / plan: 68|64 (output), materials, plan, 820, 128.
     */
    fun planMerge(request: JObj, owned: Owned, inputs: AcquisitionInputs): Plan {
        val uid = request.long("uid")
        val times = request.long("count")
        val cap = propertyInt(inputs, 971, 0)
        if (times <= 0 || (cap != 0L && times > cap)) throw Rejected("Merge count outside 1..property 971")
        val entry = owned.item(uid)
        val row = inputs.item(entry.template)
        if (row == null || row.long("type_104") != 4L) throw Rejected("Item is not mergeable", ERROR_NOT_MERGEABLE)
        val recipe = inputs.recipe(row.long("value_107")) ?: throw Rejected("No merge recipe", ERROR_NOT_MERGEABLE)
        val kind = MERGE_KIND[recipe.long("kind_102")] ?: throw Rejected("Merge target kind ${recipe.long("kind_102")} not implemented", ERROR_NOT_MERGEABLE)
        if (recipe.arr("bonus_201_203").any { it.long != 0L }) throw Rejected("Merge recipes with bonus chances are not implemented", ERROR_NOT_MERGEABLE)
        val materials = recipe.arr("materials").map { it.asArr[0].long to it.asArr[1].long }.filter { (m, q) -> m != 0L && q != 0L }
        if (materials.isEmpty()) throw Rejected("Recipe without materials", ERROR_NOT_MERGEABLE)
        val mode = recipe.long("mode_113")
        if (mode == 1L && materials[0].first != entry.template) throw Rejected("Recipe material 1 is not the merged item", ERROR_NOT_MERGEABLE)
        if (mode != 1L && mode != 2L) throw Rejected("Recipe consumption mode not implemented", ERROR_NOT_MERGEABLE)
        val currencies = materials.filter { it.first in MERGE_CURRENCIES }
        if (currencies.any { MERGE_CURRENCIES[it.first] == null }) throw Rejected("This currency material is not implemented", ERROR_NOT_MERGEABLE)
        val items = materials.filter { it.first !in MERGE_CURRENCIES }
        val substitute = if (mode == 1L && items.isNotEmpty() && items[0].first == entry.template) recipe.long("substitute_115") else 0L
        if (times > entry.count && mode == 1L) throw Rejected("Merge count above the selected stack", ERROR_NOT_ENOUGH)
        val shortfall = LinkedHashMap<Long, Long>()
        for ((index, material) in items.withIndex()) {
            val (template, quantity) = material
            val need = quantity * times
            val have = owned.countOf(template)
            if (have < need && index == 0 && substitute != 0L && have + owned.countOf(substitute) >= need) {
                shortfall[template] = need - have
                continue
            }
            if (have < need) throw Rejected("Not enough materials", ERROR_NOT_ENOUGH)
        }
        for ((template, quantity) in currencies) {
            if (owned.roleBits(MERGE_CURRENCIES.getValue(template)!!) < BigInteger.valueOf(quantity * times)) throw Rejected("Not enough resources", ERROR_RESOURCES)
        }
        if (mode == 2L && entry.count < times) throw Rejected("Not enough plans", ERROR_NOT_ENOUGH)
        val goldCost = recipe.long("gold_104") * times
        val reward = emptyReward()
        val grantFrames = ArrayList<Frame>()
        val heroFrames = ArrayList<Map<String, List<Frame>>>()
        val target = recipe.long("target_103")
        if (kind == "item") applyGrant(owned, "item", target, times, reward, grantFrames, heroFrames)
        else {
            for (i in 0 until times) applyGrant(owned, kind, target, 1, reward, grantFrames, heroFrames)
            reward[if (kind == "hero") "hero_levels" else "equip_levels"] = JArr((0 until times).mapTo(ArrayList()) { jarr(0) })
            if (kind == "equipment") reward["equip_grades"] = JArr((0 until times).mapTo(ArrayList()) { jarr(0) })
        }
        val consumeFrames = ArrayList<Frame>()
        var substituteUsed = 0L
        for ((template, quantity) in items) {
            val need = quantity * times
            if (template == entry.template) {
                // The selected stack first, then other stacks of the piece (ascending uid), then the universal piece.
                val first = minOf(need, owned.item(uid).count)
                consumeFrames.add(owned.consume(uid, first))
                val rest = need - first - (shortfall[template] ?: 0L)
                if (rest != 0L) consumeFrames.addAll(owned.consumeTemplate(template, rest))
                if ((shortfall[template] ?: 0L) != 0L) {
                    substituteUsed = shortfall.getValue(template)
                    consumeFrames.addAll(owned.consumeTemplate(substitute, substituteUsed))
                }
            } else consumeFrames.addAll(owned.consumeTemplate(template, need))
        }
        if (mode == 2L) consumeFrames.add(owned.consume(uid, times))
        val goldFrame = if (goldCost != 0L) owned.roleAdd(GOLD, -goldCost) else null
        val currencyFrames = currencies.map { (t, q) -> owned.roleAdd(MERGE_CURRENCIES.getValue(t)!!, -q * times) }
        val result = if (request["single"] == JBool(true)) S_SINGLE_MERGE to singleMergeResultPayload(reward) else S_MERGE to mergeResultPayload(times, reward)
        val packets = flatten(heroFrames, GROUP_KEYS) + grantFrames + consumeFrames + listOf(result) + listOfNotNull(goldFrame) + currencyFrames
        val evidence = if (recipe.long("key") in listOf(60206L, 61098L, 40301L) && substituteUsed == 0L) "capture_observed"
            else if (substituteUsed != 0L) "native_use_substitute" else "config_deterministic"
        return Plan(jobj("uid" to uid, "template" to entry.template, "times" to times, "recipe" to recipe["key"], "kind" to kind,
            "target" to target, "gold_cost" to goldCost, "substitute" to (if (substitute != 0L) substitute else null),
            "substitute_used" to substituteUsed, "currencies" to currencies.map { jarr(it.first, it.second) }, "reward" to reward,
            "evidence_class" to evidence), packets)
    }
}

/**
 * The mutable owned view of one committed revision (`acquisition.Owned`): items of all three stores, heroes,
 * equipment, role properties; every change is recorded for the store and the audit (`item_changes`, `new_items`,
 * `role_changes`, `log`, …) in the order it happened.
 */
class Owned(val current: StateStore.Current, val inputs: AcquisitionInputs, retiredItemUids: Collection<Long> = emptyList()) {
    class ItemEntry(val template: Long, var count: Long, val timed: Long, val location: String)

    val state: JObj = current.state
    val retiredItemUids: Set<Long> = retiredItemUids.toSet()
    val items = LinkedHashMap<Long, ItemEntry>()
    val itemChanges = LinkedHashMap<Long, Long>()
    val newItems = LinkedHashMap<Long, Pair<Long, Long>>()       // uid -> (template, count)
    val heroesAdded = JArr()
    val equipmentAdded = JArr()
    val heroesRemoved = ArrayList<Long>()
    val roleChanges = LinkedHashMap<Long, Pair<BigInteger, BigInteger>>()   // field -> (before, after)
    var godDocument: JObj? = null
    val log = JArr()
    val granted = LinkedHashMap<Long, Long>()

    init {
        for ((location, records) in listOf("initial" to state.arr("items"), "extra" to current.inventoryItems, "acquired" to current.acquiredItems)) {
            for (record in records) {
                val r = record.asObj
                val wire = r.arr("wire_values")
                val uid = wire[0].long
                if (uid in items) throw Acquisition.Rejected("Duplicate owned item UID")
                items[uid] = ItemEntry(wire[1].long, wire[2].long, r.longOrNull("timed_flag") ?: 0L, location)
            }
        }
    }

    fun item(uid: Long): ItemEntry {
        val entry = items[uid]
        if (entry == null || entry.count <= 0) throw Acquisition.Rejected("Item instance is not owned", Acquisition.ERROR_ITEM_MISSING)
        return entry
    }

    private fun setCount(uid: Long, count: Long) {
        items.getValue(uid).count = count
        val created = newItems[uid]
        if (created != null) newItems[uid] = created.first to count else itemChanges[uid] = count
    }

    /** Absolute-count frame: S68, or S66 when the stack reaches zero. */
    fun consume(uid: Long, quantity: Long): Frame {
        val entry = item(uid)
        if (quantity <= 0 || quantity > entry.count) throw Acquisition.Rejected("Not enough items", Acquisition.ERROR_NOT_ENOUGH)
        val remaining = entry.count - quantity
        setCount(uid, remaining)
        log.add(jobj("op" to "consume", "uid" to uid, "template" to entry.template, "quantity" to quantity, "remaining" to remaining))
        return if (remaining == 0L) Acquisition.S_ITEM_REMOVE to Acquisition.uidListPayload(listOf(uid))
        else Acquisition.S_ITEM_UPDATE to Acquisition.itemUpdatePayload(listOf(uid to remaining))
    }

    fun countOf(template: Long): Long = items.values.filter { it.template == template && it.count > 0 }.sumOf { it.count }

    fun consumeTemplate(template: Long, quantity: Long): List<Frame> {
        var left = quantity
        if (countOf(template) < left) throw Acquisition.Rejected("Not enough items", Acquisition.ERROR_NOT_ENOUGH)
        val frames = ArrayList<Frame>()
        for (uid in items.filter { it.value.template == template && it.value.count > 0 }.keys.sorted()) {
            if (left == 0L) break
            val take = minOf(left, items.getValue(uid).count)
            frames.add(consume(uid, take))
            left -= take
        }
        return frames
    }

    fun bagOf(template: Long): Long {
        val bag = inputs.item(template)?.long("bag_305")
        return if (bag != null && bag in Acquisition.BAG_FULL) bag else 1L
    }

    private fun bagCount(bag: Long): Int = items.values.count { it.count > 0 && bagOf(it.template) == bag }

    fun nextItemUid(): Long {
        val known = items.keys + retiredItemUids
        return (known.maxOrNull() ?: 0L) + 1
    }

    /** Add to the existing untimed stack (S68) or create a new stack (S64); currency placeholders go to the role (S128). */
    fun grantItem(template: Long, count: Long): Frame {
        Acquisition.CURRENCY_ITEM_ROLE[template]?.let { role ->
            Acquisition.u(count, 32, "Granted count")
            log.add(jobj("op" to "grant_currency_item", "template" to template, "count" to count, "role" to role))
            return roleAdd(role, BigInteger.valueOf(count))
        }
        val row = inputs.item(template) ?: throw Acquisition.Rejected("Unknown item template $template")
        Acquisition.u(count, 32, "Granted count")
        granted[template] = (granted[template] ?: 0L) + count
        val stacks = items.filter { it.value.template == template && it.value.count > 0 && it.value.timed == 0L }.keys.sorted()
        if (stacks.isNotEmpty()) {
            val uid = stacks[0]
            val total = items.getValue(uid).count + count
            val limit = row.long("max_stack_203").let { if (it == 0L) 0xFFFFFFFFL else it }
            if (total > minOf(limit, 0xFFFFFFFFL)) throw Acquisition.Rejected("Stack would exceed its configured maximum", Acquisition.ERROR_TOO_MANY)
            setCount(uid, total)
            log.add(jobj("op" to "grant_item", "uid" to uid, "template" to template, "count" to count, "stack" to total))
            return Acquisition.S_ITEM_UPDATE to Acquisition.itemUpdatePayload(listOf(uid to total))
        }
        val bag = bagOf(template)
        val capacity = state.arr("item_capacity_values")[(bag - 1).toInt()].long
        if (bagCount(bag) >= capacity) throw Acquisition.Rejected("The bag for this item is full", Acquisition.BAG_FULL.getValue(bag))
        val uid = nextItemUid()
        items[uid] = ItemEntry(template, count, 0, "new")
        newItems[uid] = template to count
        log.add(jobj("op" to "new_item", "uid" to uid, "template" to template, "count" to count, "bag" to bag))
        return Acquisition.S_ITEM_ADD to Inventory.encode(jarr(jobj("wire_values" to jarr(uid, template, count), "timed_flag" to 0)))
    }

    /** The value of one scalar role property (`{"tag", "bits", ...}`). */
    fun role(fieldId: Long): JObj {
        val matches = state.arr("role_properties").map { it.asObj }.filter { it.long("id") == fieldId }.map { it.obj("value") }
        if (matches.size != 1 || matches[0].long("tag").toInt() !in TypedValues.WIDTH_FORMAT) throw Acquisition.Rejected("Expected one scalar role property $fieldId")
        return matches[0]
    }

    fun roleBits(fieldId: Long): BigInteger = (role(fieldId)["bits"] as JInt).value

    fun roleAdd(fieldId: Long, delta: BigInteger): Frame {
        val value = role(fieldId)
        val fmt = TypedValues.WIDTH_FORMAT.getValue(value.long("tag").toInt())
        val size = when (fmt.uppercaseChar()) { 'B' -> 8; 'H' -> 16; 'I' -> 32; else -> 64 }
        var low = if (fmt.isLowerCase()) BigInteger.ONE.shiftLeft(size - 1).negate() else BigInteger.ZERO
        val high = if (fmt.isLowerCase()) BigInteger.ONE.shiftLeft(size - 1) - BigInteger.ONE else BigInteger.ONE.shiftLeft(size) - BigInteger.ONE
        if (fieldId in setOf(Acquisition.DIAMOND, Acquisition.STAMINA, Acquisition.ENERGY)) low = BigInteger.ZERO
        val bits = (value["bits"] as JInt).value
        val new = bits + delta
        if (new < low) throw Acquisition.Rejected("Not enough resources", Acquisition.ERROR_RESOURCES)
        if (new > high) throw Acquisition.Rejected("Resource would exceed its native range", Acquisition.ERROR_TOO_MANY)
        val before = roleChanges[fieldId]?.first ?: bits
        value["bits"] = JInt(new)
        roleChanges[fieldId] = before to new
        return Acquisition.S_ROLE to Acquisition.roleUpdatePayload(listOf(Triple(fieldId, value.long("tag"), new)))
    }

    fun roleAdd(fieldId: Long, delta: Long): Frame = roleAdd(fieldId, BigInteger.valueOf(delta))

    fun heroUids(): List<Long> = state.arr("heroes").map { Acquisition.heroValues(it.asArr)[0L]!!.long }

    /** A new hero (uid = max + 1): frame groups add (S32, S38), book, god (S2850), activity (S1184). */
    fun grantHero(template: Long): Pair<Long, Map<String, List<Frame>>> {
        if (!inputs.heroExists(template)) throw Acquisition.Rejected("Unknown hero template $template")
        if (state.arr("heroes").size + 1 > Acquisition.HERO_WIRE_LIMIT) throw Acquisition.Rejected("Hero list is at its wire limit", Acquisition.ERROR_HEROES_FULL)
        val uid = (heroUids().maxOrNull() ?: 0L) + 1
        val s32 = inputs.freshHeroFields(uid, template)
        state.arr("heroes").add(Acquisition.storedHeroFields(s32))
        state.arr("offline_hero_uids").add(JInt(uid))
        heroesAdded.add(jobj("uid" to uid, "template" to template))
        val groups = linkedMapOf<String, List<Frame>>(
            "add" to listOf(Acquisition.S_HERO_ADD to Acquisition.heroAddPayload(listOf(s32)), Acquisition.S_BENCH_ADD to Acquisition.uidListPayload(listOf(uid))),
            "book" to collect("hero_collection", template, Acquisition.HERO_BOOK))
        val god = current.godSkills
        val godFrames = ArrayList<Frame>()
        if (god != null) {
            if (godDocument == null) godDocument = god.obj("document").deepCopy()
            val skills = inputs.astralInitialSkills(template)
            val heroes = godDocument!!.arr("heroes").filter { it.asObj.long("uid") != uid }.toMutableList()
            heroes.add(jobj("uid" to uid, "provenance" to "acquired_initial_group_991", "skills" to skills))
            godDocument!!["heroes"] = JArr(heroes.sortedBy { it.asObj.long("uid") }.toMutableList())
            godFrames.add(Acquisition.S_GOD_SKILLS to GodSkills.encodeGodSkillUpdate(uid, skills.map { it.asArr }))
        }
        groups["god"] = godFrames
        groups["activity"] = listOf(Acquisition.S_ACTIVITY to PlayerSections.encodeSection("game_activities", state.obj("subsystems").obj("game_activities")))
        log.add(jobj("op" to "new_hero", "uid" to uid, "template" to template))
        return uid to groups
    }

    fun removeHero(uid: Long) {
        state["heroes"] = JArr(state.arr("heroes").filter { Acquisition.heroValues(it.asArr)[0L]!!.long != uid }.toMutableList())
        state["offline_hero_uids"] = JArr(state.arr("offline_hero_uids").filter { it.long != uid }.toMutableList())
        heroesRemoved.add(uid)
    }

    /** A new equipment record `[uid, template, 1, 0, 1, 0, 0]` into the bag (uid = max + 1). */
    fun grantEquipment(template: Long): Pair<Long, Map<String, List<Frame>>> {
        if (!inputs.exists("equip", template)) throw Acquisition.Rejected("Unknown equipment template $template")
        if (state.arr("equipment").size + 1 > Acquisition.EQUIP_WIRE_LIMIT) throw Acquisition.Rejected("Equipment list is at its wire limit", Acquisition.ERROR_TOO_MANY)
        val uid = (state.arr("equipment").map { it.asObj.arr("wire_values")[0].long }.maxOrNull() ?: 0L) + 1
        val record = listOf(uid, template) + Acquisition.FRESH_EQUIPMENT
        state.arr("equipment").add(jobj("offset" to JNull, "wire_values" to record))
        state["bag_equipment_uids"] = JArr((state.arr("bag_equipment_uids").map { it.long } + uid).sorted().mapTo(ArrayList()) { JInt(it) })
        equipmentAdded.add(jobj("uid" to uid, "template" to template))
        log.add(jobj("op" to "new_equipment", "uid" to uid, "template" to template))
        return uid to linkedMapOf(
            "add" to listOf(Acquisition.S_EQUIP_ADD to Acquisition.equipmentAddPayload(listOf(record)), Acquisition.S_EQUIP_BAG_ADD to Acquisition.uidListPayload(listOf(uid))),
            "book" to collect("equip_collection", template, Acquisition.EQUIP_BOOK))
    }

    fun collect(section: String, template: Long, bookField: Long): List<Frame> {
        val collection = state.obj("subsystems").obj(section)
        val present = collection.arr("entries").map { it.asObj.arr("wire_values")[0].long }.toSet()
        if (template in present) return emptyList()
        val entries = (collection.arr("entries").toList() + jobj("wire_values" to jarr(template))).sortedBy { it.asObj.arr("wire_values")[0].long }
        collection["entries"] = JArr(entries.toMutableList())
        collection["count"] = JInt(entries.size)
        val frames = ArrayList<Frame>()
        frames.add(Acquisition.S_COLLECTION to Acquisition.collectionPayload(Acquisition.COLLECTION_KIND.getValue(section), template))
        val bookFrame = roleAdd(bookField, BigInteger.ONE)
        val bookValue = roleBits(bookField)
        for (entry in state.obj("subsystems").obj("achievements").arr("entries")) {
            val wire = entry.asObj.arr("wire_values")
            if (wire[0].long == Acquisition.BOOK_ACHIEVEMENT.getValue(bookField)) {
                wire[2] = JInt(bookValue)
                frames.add(Acquisition.S_ACHIEVEMENT to Acquisition.achievementPayload(wire))
            }
        }
        frames.add(bookFrame)
        log.add(jobj("op" to "collection_add", "section" to section, "template" to template, "book" to bookValue))
        return frames
    }
}
