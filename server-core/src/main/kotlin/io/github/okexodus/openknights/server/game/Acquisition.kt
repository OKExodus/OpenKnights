package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.Inventory
import io.github.okexodus.openknights.protocol.PlayerSections
import io.github.okexodus.openknights.protocol.TypedValues
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

    private fun collect(section: String, template: Long, bookField: Long): List<Frame> {
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
