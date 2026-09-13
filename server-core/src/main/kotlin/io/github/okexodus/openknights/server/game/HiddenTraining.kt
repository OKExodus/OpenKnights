package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import java.math.BigInteger

/**
 * The login / query parts of `hidden_training.py`: the training room list S2112 (C1761; offline every room but the
 * default one is the player's own), the Blacksmith / Crafting cooldowns (S1760 type 5, S3726) and the Hero Set Out
 * slots S2274 (seeded once from the seed frame). Every timer is an absolute deadline sent as the remaining seconds.
 */
object HiddenTraining {
    const val C_ROOM_LIST = 1761
    const val C_ROOM_CREATE = 1763
    const val C_ROOM_ENTER = 1765
    const val C_TRAIN = 1767
    const val C_ROOM_PASSWORD = 1769
    const val C_ROOM_INVITE = 1771
    const val C_ROOM_KICK = 1773
    const val C_TRAIN_PREVIEW = 1775
    const val C_TRAIN_CLAIM = 1777
    const val C_ROOM_ADD_TIME = 1779
    const val S_ROOM_LIST = 2112
    const val S_EVENT_UPDATE = 1760
    const val T_SMITH = 5
    const val S_CRAFT_CD = 3726
    const val S_EXPLORE_SLOTS = 2274

    const val ROLE_ID = 0L
    const val ROLE_NAME = 2L
    const val TRAINING_PROFILE = "training_state_v1"
    const val FORGE_PROFILE = "forge_state_v1"
    const val EXPLORE_PROFILE = "explore_state_v1"
    const val ERROR_NO_ROOM = 38000
    const val DEFAULT_ROOM = 1L
    const val DEFAULT_ROW = 401L

    private fun copy(value: JValue): JObj = value.deepCopy() as JObj

    private fun roleBits(state: JObj, fieldId: Long, default: Long = 0): JValue? = PyDocs.role(state, fieldId, JInt(default))

    private fun nameRaw(state: JObj): ByteArray {
        for (f in state.arr("role_properties")) {
            val field = f.asObj
            if (field["id"] == JInt(ROLE_NAME)) {
                val value = field.obj("value")
                return ((value["raw_hex"] ?: JStr("")) as JStr).value.hexBytes()
            }
        }
        return ByteArray(0)
    }

    private fun cstr(raw: ByteArray, offset: Int): Pair<ByteArray, Int> {
        var end = offset
        while (end < raw.size && raw[end] != 0.toByte()) end++
        if (end >= raw.size) throw PyValues.ValueError("subsection not found")
        return raw.copyOfRange(offset, end) to end + 1
    }

    // --- the lineup (the reference's world_participants.lineup_of, used for the seat avatar) ------------------------------

    private class LineupEntry(val position: JValue, val template: Long)

    /** Formation slots with a hero, in slot order, joined with the owned hero fields (field 1 = template). */
    private fun lineupOf(state: JObj): List<LineupEntry> {
        val heroes = LinkedHashMap<JValue?, Map<Long, JValue?>>()
        for (fields in state.arr("heroes")) {
            val values = Acquisition.heroValues(fields.asArr)
            if (0L !in values) throw PyDocs.KeyError(0)
            heroes[values[0L]] = values
        }
        val out = ArrayList<LineupEntry>()
        val formation = ((state["formation"] as? JArr) ?: JArr()).sortedWith { a, b -> PyDocs.compare(PyDocs.at(a.asObj, "slot_id"), PyDocs.at(b.asObj, "slot_id")) }
        for (s in formation) {
            val slot = s.asObj
            val values = heroes[slot["hero_uid"]]
            if (!PyDocs.truthy(slot["hero_uid"]) || values == null) continue
            val template = values[1L]?.takeIf { PyDocs.truthy(it) }?.let { PyDocs.long(it) } ?: 0L
            out.add(LineupEntry(PyDocs.at(slot, "slot_id"), template))
        }
        return out
    }

    /** The seat avatar: the captain's hero template (else the first lineup hero, else 0). */
    fun seatTemplate(state: JObj): Long {
        val lineup = lineupOf(state)
        val captain = state["captain_slot"] ?: JNull
        return lineup.firstOrNull { it.position == captain }?.template ?: (lineup.firstOrNull()?.template ?: 0L)
    }

    // --- training rooms -----------------------------------------------------------------------------------------------------

    fun trainingDocument(document: JValue?): JObj {
        val doc = if (PyDocs.truthy(document)) copy(document!!) else jobj("profile" to TRAINING_PROFILE)
        if (!doc.containsKey("next_room")) doc["next_room"] = JInt(2)
        if (!doc.containsKey("rooms")) doc["rooms"] = JArr()
        if (!doc.containsKey("seat")) doc["seat"] = JNull
        return doc
    }

    private fun row(inputs: DailyInputs, row: JValue): JObj =
        inputs.trainingRoom(PyDocs.long(row)) ?: throw Acquisition.Rejected("Unknown training room row ${PyDocs.str(row)}", ERROR_NO_ROOM)

    class Player(val seat: JValue, val playerId: JValue?, val nameRaw: ByteArray, val template: Long)

    class Room(val uid: JValue, val row: JValue, val masterRaw: ByteArray, val expiresAt: JValue?, val password: ByteArray,
               val mine: Boolean, var players: MutableList<Player>)

    private fun selfPlayer(state: JObj) = Player(JNull, roleBits(state, ROLE_ID), nameRaw(state), seatTemplate(state))

    /** Training ends after xiuxing 401 seconds or when its room's lifetime ends, whichever is first. */
    fun seatEnd(seat: JObj, roomsByUid: Map<JValue, Room>, inputs: DailyInputs): BigInteger {
        var end = PyDocs.int(PyDocs.at(seat, "seated_at")) + BigInteger.valueOf(row(inputs, PyDocs.at(seat, "row")).long("duration"))
        val room = roomsByUid[PyDocs.at(seat, "room")]
        if (room?.expiresAt != null) end = end.min(PyDocs.int(room.expiresAt))
        return end
    }

    /** The rooms the player sees: the default room and the player's own rooms whose lifetime has not ended (or which
     * the player still trains in); the seated player joins its room. */
    fun roomsView(document: JValue?, state: JObj, inputs: DailyInputs, now: Long): List<Room> {
        val doc = trainingDocument(document)
        val seat = PyDocs.get(doc, "seat")?.let { it as JObj }
        val me = selfPlayer(state)
        val rooms = mutableListOf(Room(JInt(DEFAULT_ROOM), JInt(DEFAULT_ROW), ByteArray(0), null, ByteArray(0), false, ArrayList()))
        for (r in doc.arr("rooms")) {
            val room = r.asObj
            val expires = PyDocs.at(room, "expires_at")
            if (expires is JNull) throw PyDocs.TypeError("'>' not supported between instances of 'NoneType' and 'int'")
            if (PyDocs.compare(expires, JInt(now)) > 0 || (PyDocs.truthy(seat) && PyDocs.at(seat!!, "room") == PyDocs.at(room, "uid"))) {
                val password = ((room["password_hex"] ?: JStr("")) as JStr).value.hexBytes()
                rooms.add(Room(PyDocs.at(room, "uid"), PyDocs.at(room, "row"), me.nameRaw, expires, password, true, ArrayList()))
            }
        }
        if (PyDocs.truthy(seat)) {
            for (room in rooms) {
                if (room.uid == PyDocs.at(seat!!, "room")) {
                    val players = room.players.filter { it.playerId != me.playerId }.toMutableList()
                    players.add(Player(PyDocs.at(seat, "seat"), me.playerId, me.nameRaw, me.template))
                    room.players = players.sortedWith { a, b -> PyDocs.compare(a.seat, b.seat) }.toMutableList()
                }
            }
        }
        return rooms
    }

    private fun remaining(deadline: JValue?, now: Long): BigInteger =
        if (deadline == null || deadline == JNull) BigInteger.ZERO else maxOf(BigInteger.ZERO, PyDocs.int(deadline) - BigInteger.valueOf(now))

    /**
     * S2112 `u16 page, u16 pages, u8 n, n × (u32 uid, u32 row, cstr master, i32 remaining, u8 seated, u8 password,
     * u8 mine), u32 room_self_in, u8 can_get_reward`; the room the player trains in is listed first.
     */
    fun roomListPayload(rooms: List<Room>, seat: JObj?, now: Long, inputs: DailyInputs, page: Long = 1): ByteArray {
        val inRoom: JValue = if (PyDocs.truthy(seat)) PyDocs.at(seat!!, "room") else JInt(0)
        val ordered = rooms.sortedWith { a, b ->
            val x = (if (a.uid != inRoom) 1 else 0).compareTo(if (b.uid != inRoom) 1 else 0)
            if (x != 0) x else PyDocs.compare(a.uid, b.uid)
        }
        val w = WireWriter().number('H', page).number('H', 1).number('B', ordered.size.toLong())
        for (room in ordered) {
            w.number('I', room.uid).number('I', room.row).raw(room.masterRaw).raw(byteArrayOf(0))
            w.number('i', remaining(room.expiresAt, now)).number('B', room.players.size.toLong())
                .number('B', if (room.password.isNotEmpty()) 1L else 0L).number('B', if (room.mine) 1L else 0L)
        }
        val byUid = LinkedHashMap<JValue, Room>().also { m -> rooms.forEach { m[it.uid] = it } }
        val done = PyDocs.truthy(seat) && BigInteger.valueOf(now) >= seatEnd(seat!!, byUid, inputs)
        return w.number('I', inRoom).number('B', if (done) 1L else 0L).bytes()
    }

    fun roomListReply(document: JValue?, state: JObj, inputs: DailyInputs, now: Long): List<Frame> {
        val doc = trainingDocument(document)
        val rooms = roomsView(doc, state, inputs, now)
        return listOf(S_ROOM_LIST to roomListPayload(rooms, PyDocs.get(doc, "seat")?.let { it as JObj }, now, inputs))
    }

    // --- Blacksmith / Crafting ---------------------------------------------------------------------------------------------

    fun forgeDocument(document: JValue?): JObj {
        val doc = if (PyDocs.truthy(document)) copy(document!!) else jobj("profile" to FORGE_PROFILE)
        if (!doc.containsKey("smith")) doc["smith"] = jarr(0, 0)
        if (!doc.containsKey("craft")) doc["craft"] = jarr(0, 0)
        return doc
    }

    private fun cds(until: JValue, now: Long): ByteArray {
        val values = (until as JArr).map { JInt(maxOf(BigInteger.ZERO, PyDocs.int(it) - BigInteger.valueOf(now))) }
        return WireWriter().values("ii", values).bytes()
    }

    fun smithCdFrame(document: JValue?, now: Long): Frame =
        S_EVENT_UPDATE to (byteArrayOf(T_SMITH.toByte()) + cds(PyDocs.at(forgeDocument(document), "smith"), now))

    fun craftCdFrame(document: JValue?, now: Long): Frame = S_CRAFT_CD to cds(PyDocs.at(forgeDocument(document), "craft"), now)

    // --- Hero Set Out -------------------------------------------------------------------------------------------------------

    /** S2274 `u8 n, n × slot`; slot = u8 pos, u32 hero, [u8 state; 1: u8 k, k × u32; 2: u32 total, i32 remaining;
     * 3: cstr result text]. */
    fun decodeSlots(payload: ByteArray): List<JObj> {
        val slots = ArrayList<JObj>()
        var offset = 1
        repeat(payload[0].toInt() and 0xFF) {
            val (slot, next) = decodeSlot(payload, offset)
            slots.add(slot)
            offset = next
        }
        if (offset != payload.size) throw PyValues.ValueError("S2274 trailing bytes")
        return slots
    }

    private fun decodeSlot(raw: ByteArray, start: Int): Pair<JObj, Int> {
        val r = WireReader(raw).also { it.offset = start }
        val pos = r.number('B')
        val hero = r.number('I')
        var offset = start + 5
        val slot = jobj("pos" to pos, "hero" to hero, "state" to 0, "choices" to JArr(), "explore" to 0, "total" to 0,
            "remaining" to 0, "text_hex" to "")
        if (hero.value.signum() != 0) {
            val state = raw[offset].toInt() and 0xFF
            slot["state"] = JInt(state)
            offset += 1
            when (state) {
                1 -> {
                    val k = raw[offset].toInt() and 0xFF
                    val rr = WireReader(raw).also { it.offset = offset + 1 }
                    slot["choices"] = JArr((0 until k).mapTo(ArrayList()) { rr.number('I') })
                    offset += 1 + 4 * k
                }
                2 -> {
                    val rr = WireReader(raw).also { it.offset = offset }
                    slot["total"] = rr.number('I')
                    slot["remaining"] = rr.number('i')
                    offset += 8
                }
                3 -> {
                    val (text, next) = cstr(raw, offset)
                    slot["text_hex"] = JStr(text.toHexString())
                    offset = next
                }
            }
        }
        return slot to offset
    }

    /** The stored document, else seeded once from the seed S2274 (a state-2 slot returns `remaining` after `now`). */
    fun exploreDocument(document: JValue?, seedPayload: ByteArray? = null, now: Long = 0, provenance: JValue? = null): JObj {
        if (PyDocs.truthy(document)) return copy(document!!)
        val slots = JArr()
        for (slot in decodeSlots(if (seedPayload != null && seedPayload.isNotEmpty()) seedPayload else byteArrayOf(0))) {
            val entry = jobj("pos" to slot["pos"], "hero" to slot["hero"], "state" to slot["state"], "choices" to slot["choices"],
                "explore" to 0, "returns_at" to 0, "total" to slot["total"], "text_hex" to slot["text_hex"], "reward" to null)
            if (slot["state"] == JInt(2)) entry["returns_at"] = JInt(BigInteger.valueOf(now) + PyDocs.int(slot["remaining"]))
            slots.add(entry)
        }
        return jobj("profile" to EXPLORE_PROFILE, "slots" to slots, "seed" to (provenance ?: JNull))
    }

    fun slotPayload(slot: JObj, now: Long): ByteArray {
        val w = WireWriter().number('B', PyDocs.at(slot, "pos")).number('I', PyDocs.at(slot, "hero"))
        if (!PyDocs.truthy(slot["hero"])) return w.bytes()
        val state = PyDocs.at(slot, "state")
        w.raw(PyDocs.bytes(listOf(PyDocs.long(state))))
        when (state) {
            JInt(1) -> {
                val choices = slot.arr("choices")
                w.raw(PyDocs.bytes(listOf(choices.size.toLong())))
                for (c in choices) w.number('I', c)
            }
            JInt(2) -> w.number('I', PyDocs.at(slot, "total"))
                .number('i', maxOf(BigInteger.ZERO, PyDocs.int(PyDocs.at(slot, "returns_at")) - BigInteger.valueOf(now)))
            JInt(3) -> w.raw(slot.str("text_hex").hexBytes()).raw(byteArrayOf(0))
            else -> {}
        }
        return w.bytes()
    }

    fun slotsPayload(document: JObj, now: Long): ByteArray {
        val slots = document.arr("slots")
        val w = WireWriter().raw(PyDocs.bytes(listOf(slots.size.toLong())))
        for (s in slots) w.raw(slotPayload(s.asObj, now))
        return w.bytes()
    }
}
