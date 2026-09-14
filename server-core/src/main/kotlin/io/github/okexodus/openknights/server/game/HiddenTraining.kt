package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.PyInt
import io.github.okexodus.openknights.exact.PyRandom
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.StateStore
import java.math.BigInteger
import java.security.MessageDigest

/**
 * `hidden_training.py`: the training room list S2112 (C1761; offline every room but the default one is the player's
 * own), the room queries (C1765 enter / Train Now and C1773 remove → S2114, C1775 results preview → S2116), the
 * Blacksmith / Crafting cooldowns (S1760 type 5, S3726) and the Hero Set Out slots S2274 (seeded once from the seed
 * frame); and the actions: rooms (C1763 create, C1767 seat, C1769 code, C1777 claim, C1779 more time), the forge (C1643
 * Blacksmith, C3747 Crafting, C3731 / C3753 cooldown removal) and Hero Set Out (C2113 … C2125). Every timer is an absolute
 * deadline sent as the remaining seconds; the planners work on the [Owned] view (one audited revision each).
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
    const val S_ROOM_INFO = 2114
    const val S_TRAIN_PREVIEW = 2116
    const val S_EVENT_UPDATE = 1760
    const val T_SMITH = 5
    const val S_CRAFT_CD = 3726
    const val S_EXPLORE_SLOTS = 2274

    const val ROLE_ID = 0L
    const val ROLE_NAME = 2L
    const val ROLE_LEVEL = 3L
    const val TITLE = 22L
    const val TRAINING_PROFILE = "training_state_v1"
    const val FORGE_PROFILE = "forge_state_v1"
    const val EXPLORE_PROFILE = "explore_state_v1"
    const val ERROR_NO_ROOM = 38000
    const val ERROR_ROOM_CODE = 38001
    const val ERROR_LEVEL = 38004
    const val ERROR_NOT_OWNER = 38006
    const val ERROR_NOT_TRAINING = 38007
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

    /** The seat avatar: the captain's hero template (else the first lineup hero, else 0). */
    fun seatTemplate(state: JObj): Long {
        val lineup = WorldParticipants.lineupOf(state)
        val captain = state.longOrNull("captain_slot")
        return lineup.firstOrNull { it.position == captain }?.template ?: (lineup.firstOrNull()?.template ?: 0L)
    }

    // --- training rooms -----------------------------------------------------------------------------------------------------

    fun trainingDocument(document: JValue?): JObj {
        val doc = if (Py.truthy(document)) copy(document!!) else jobj("profile" to TRAINING_PROFILE)
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
            if (PyDocs.compare(expires, JInt(now)) > 0 || (Py.truthy(seat) && PyDocs.at(seat!!, "room") == PyDocs.at(room, "uid"))) {
                val password = ((room["password_hex"] ?: JStr("")) as JStr).value.hexBytes()
                rooms.add(Room(PyDocs.at(room, "uid"), PyDocs.at(room, "row"), me.nameRaw, expires, password, true, ArrayList()))
            }
        }
        if (Py.truthy(seat)) {
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
        val inRoom: JValue = if (Py.truthy(seat)) PyDocs.at(seat!!, "room") else JInt(0)
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
        val done = Py.truthy(seat) && BigInteger.valueOf(now) >= seatEnd(seat!!, byUid, inputs)
        return w.number('I', inRoom).number('B', if (done) 1L else 0L).bytes()
    }

    fun roomListReply(document: JValue?, state: JObj, inputs: DailyInputs, now: Long): List<Frame> {
        val doc = trainingDocument(document)
        val rooms = roomsView(doc, state, inputs, now)
        return listOf(S_ROOM_LIST to roomListPayload(rooms, PyDocs.get(doc, "seat")?.let { it as JObj }, now, inputs))
    }

    /**
     * The player's attack score for the training settlement (`attack_score`, policy): the client's per-slot Power
     * formula 2·HP + 21·DEF + 15·(ATK + Unique) + 20·RebornAtk + 25·RebornDef (u32 stats) over the formation's heroes
     * (hero fields 4 / 6 / 8 / 10 / 22 / 23) with the title row (title.csv 201 HP, 202 Unique, 203 ATK).
     */
    fun attackScore(state: JObj, inputs: DailyInputs): BigInteger {
        val title = roleBits(state, TITLE)
        val row = if (title is JInt && title.value.bitLength() < 64) inputs.titleBonus(title.value.toLong())
            else jobj("hp" to 0, "unique" to 0, "atk" to 0)
        val heroes = HashMap<JValue, Map<JValue, JValue>>()
        for (fields in PyDocs.at(state, "heroes") as JArr) {
            val values = LinkedHashMap<JValue, JValue>()
            for (f in fields as JArr) {
                val bits = f.asObj.obj("value")["bits"]
                values[PyDocs.at(f.asObj, "id")] = if (Py.truthy(bits)) bits!! else JInt(0)
            }
            heroes[values[JInt(0)] ?: throw PyDocs.KeyError("0")] = values
        }
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        var total = BigInteger.ZERO
        for (s in (state["formation"] as? JArr) ?: JArr()) {
            val slot = s.asObj
            val uid = slot["hero_uid"]
            val values = heroes[uid ?: JNull]
            if (!Py.truthy(uid) || values == null) continue
            fun v(id: Long): BigInteger = PyDocs.int(values[JInt(id)] ?: JInt(0))
            val hp = v(4) + PyDocs.int(row["hp"])
            val atk = v(6) + PyDocs.int(row["atk"])
            val defense = v(8)
            val unique = v(10) + PyDocs.int(row["unique"])
            total += BigInteger.TWO * hp.and(mask) + BigInteger.valueOf(21) * defense.and(mask) +
                BigInteger.valueOf(15) * (atk.and(mask) + unique.and(mask)) + BigInteger.valueOf(20) * v(22).and(mask) +
                BigInteger.valueOf(25) * v(23).and(mask)
        }
        return total
    }

    private fun find(rooms: List<Room>, uid: JValue?): Room =
        rooms.firstOrNull { it.uid == uid } ?: throw Acquisition.Rejected("Training room does not exist", ERROR_NO_ROOM)

    /**
     * S2114 `u32 uid, u32 row, cstr master, i32 room remaining, i32 own training remaining, cstr password, u8 n, n ×
     * (u8 seat, u32 player id, cstr name, u32 hero template)`. The password shows only to the room's owner.
     */
    fun roomInfoPayload(room: Room, seat: JObj?, now: Long, inputs: DailyInputs, rooms: List<Room>): ByteArray {
        val byUid = LinkedHashMap<JValue, Room>().also { m -> rooms.forEach { m[it.uid] = it } }
        val own = if (Py.truthy(seat) && PyDocs.at(seat!!, "room") == room.uid) remaining(JInt(seatEnd(seat, byUid, inputs)), now) else BigInteger.ZERO
        val w = WireWriter().number('I', room.uid).number('I', room.row).raw(room.masterRaw).raw(byteArrayOf(0))
        w.number('i', remaining(room.expiresAt, now)).number('i', own)
        w.raw(if (room.mine) room.password else ByteArray(0)).raw(byteArrayOf(0)).raw(PyDocs.bytes(listOf(room.players.size.toLong())))
        for (player in room.players) {
            w.number('B', player.seat).number('I', player.playerId ?: JNull).raw(player.nameRaw).raw(byteArrayOf(0))
            w.number('I', player.template)
        }
        return w.bytes()
    }

    /**
     * Rate factor per 10,000 (`training_boost`): xiuxing 301 (base), + 303 in the player's own room, + 302 when every
     * seat is taken (policy).
     */
    fun trainingBoost(room: Room, inputs: DailyInputs): Long {
        val row = row(inputs, room.row)
        var boost = row.long("boost")
        if (room.mine) boost += row.long("creator_boost")
        if (room.players.size >= row.long("seats")) boost += row.long("full_boost")
        return boost
    }

    /** EXP(t) = ⌊t·P·601·B / (6·10⁸·10⁴)⌋, Honor(t) = ⌊t·P·602·B / (6·10¹⁰·10⁴)⌋ (`training_reward`). */
    fun trainingReward(seconds: BigInteger, power: BigInteger, row: JObj, boost: Long): Pair<BigInteger, BigInteger> {
        val base = seconds * power
        return PyInt.floorDiv(base * row.int("exp_rate") * BigInteger.valueOf(boost), BigInteger.valueOf(600_000_000L * 10_000L)) to
            PyInt.floorDiv(base * row.int("honor_rate") * BigInteger.valueOf(boost), BigInteger.valueOf(60_000_000_000L * 10_000L))
    }

    /** (seconds trained so far, training end) of the seated player; not seated: 38007 (`_trained`). */
    private fun trained(document: JObj, rooms: List<Room>, inputs: DailyInputs, now: Long): Pair<BigInteger, BigInteger> {
        val seat = PyDocs.at(document, "seat")
        if (!Py.truthy(seat)) throw Acquisition.Rejected("Training hasn't started or was claimed", ERROR_NOT_TRAINING)
        val byUid = LinkedHashMap<JValue, Room>().also { m -> rooms.forEach { m[it.uid] = it } }
        val end = seatEnd(seat as JObj, byUid, inputs)
        return (BigInteger.valueOf(now).min(end) - PyDocs.int(PyDocs.at(seat, "seated_at"))).max(BigInteger.ZERO) to end
    }

    /** A Reward with the EXP and the Honor (as exploit) (`_reward`). */
    private fun reward(exp: BigInteger, honor: BigInteger): ByteArray {
        val reward = Acquisition.emptyReward()
        reward["exp"] = JInt(exp)
        reward["exploit"] = JInt(honor)
        return BattleReport.encodeReward(reward)
    }

    private fun levelOk(room: Room, level: JValue?, inputs: DailyInputs): Boolean {
        val row = row(inputs, room.row)
        val lvl = level ?: JNull
        return PyDocs.compare(JInt(row.long("min_level")), lvl) <= 0 && PyDocs.compare(lvl, JInt(row.long("max_level"))) <= 0
    }

    /** C1765 / C1769 `u32 room, cstring password` (`decode_room_password`). */
    class RoomSecret(val room: JInt, val password: ByteArray)

    fun decodeRoomPassword(payload: ByteArray, opcode: Int): RoomSecret {
        if (payload.size < 5 || payload[payload.size - 1] != 0.toByte() || payload.copyOfRange(4, payload.size - 1).contains(0.toByte())) {
            throw Acquisition.Rejected("C$opcode is u32 room + cstring")
        }
        return RoomSecret(WireReader(payload).number('I'), payload.copyOfRange(4, payload.size - 1))
    }

    /**
     * C1765 `u32 room (0 = "Train Now"), cstr password` → S2114 (`enter_reply`). Train Now opens the room the player
     * trains in, else the joinable room with the highest base boost; a password is asked for rooms of others; the level
     * window is xiuxing 502 … 503.
     */
    fun enterReply(request: RoomSecret, document: JValue?, state: JObj, inputs: DailyInputs, now: Long): List<Frame> {
        val doc = trainingDocument(document)
        val rooms = roomsView(doc, state, inputs, now)
        val level = roleBits(state, ROLE_LEVEL, 1)
        val seat = PyDocs.at(doc, "seat").takeIf { it != JNull }
        val room: Room
        if (request.room.value.signum() == 0) {
            if (Py.truthy(seat)) {
                room = find(rooms, PyDocs.at(seat as JObj, "room"))
            } else {
                val joinable = rooms.filter { r ->
                    levelOk(r, level, inputs) && (r.mine || r.password.isEmpty()) && r.players.size < row(inputs, r.row).long("seats")
                }
                if (joinable.isEmpty()) throw Acquisition.Rejected("No training room is open", ERROR_NO_ROOM)
                var best = joinable[0]
                fun key(r: Room): Pair<Long, BigInteger> = row(inputs, r.row).long("boost") to PyDocs.int(r.uid).negate()
                var bestKey = key(best)
                for (r in joinable.drop(1)) {
                    val k = key(r)
                    if (k.first > bestKey.first || (k.first == bestKey.first && k.second > bestKey.second)) { best = r; bestKey = k }
                }
                room = best
            }
        } else {
            room = find(rooms, request.room)
            if (room.password.isNotEmpty() && !room.mine && !room.password.contentEquals(request.password)) {
                throw Acquisition.Rejected("Incorrect room code", ERROR_ROOM_CODE)
            }
            if (!levelOk(room, level, inputs)) throw Acquisition.Rejected("Your level is too low", ERROR_LEVEL)
        }
        return listOf(S_ROOM_INFO to roomInfoPayload(room, seat as JObj?, now, inputs, rooms))
    }

    /**
     * C1775 ("End" in the room) → S2116 Reward (EXP, Honor so far) + i32 remaining training seconds; no change
     * (`preview_reply`). `power`: the attack score to use (null = the save's).
     */
    fun previewReply(document: JValue?, state: JObj, inputs: DailyInputs, now: Long, power: BigInteger? = null): List<Frame> {
        val doc = trainingDocument(document)
        val rooms = roomsView(doc, state, inputs, now)
        val (seconds, end) = trained(doc, rooms, inputs, now)
        val room = find(rooms, PyDocs.at(doc.obj("seat"), "room"))
        val score = power ?: attackScore(state, inputs)
        val (exp, honor) = trainingReward(seconds, score, row(inputs, room.row), trainingBoost(room, inputs))
        return listOf(S_TRAIN_PREVIEW to (reward(exp, honor) + WireWriter().number('i', remaining(JInt(end), now)).bytes()))
    }

    /** A room the player owns (stored); the default room: 38006, another: 38000 (`_own_room`). */
    private fun ownRoom(document: JObj, uid: JValue): JObj =
        document.arr("rooms").firstOrNull { PyDocs.at(it.asObj, "uid") == uid }?.asObj
            ?: if (uid == JInt(DEFAULT_ROOM)) throw Acquisition.Rejected("Only room owner can use this function", ERROR_NOT_OWNER)
            else throw Acquisition.Rejected("Training room does not exist", ERROR_NO_ROOM)

    /** C1773 `u32 room, u32 player` (owner only): offline nobody else sits in the player's rooms → S2114 (`kick_reply`). */
    fun kickReply(room: JValue, document: JValue?, state: JObj, inputs: DailyInputs, now: Long): List<Frame> {
        val doc = trainingDocument(document)
        ownRoom(doc, room)
        val rooms = roomsView(doc, state, inputs, now)
        return listOf(S_ROOM_INFO to roomInfoPayload(find(rooms, room), PyDocs.at(doc, "seat").takeIf { it != JNull } as JObj?, now, inputs, rooms))
    }

    // --- Blacksmith / Crafting ---------------------------------------------------------------------------------------------

    fun forgeDocument(document: JValue?): JObj {
        val doc = if (Py.truthy(document)) copy(document!!) else jobj("profile" to FORGE_PROFILE)
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
        if (Py.truthy(document)) return copy(document!!)
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
        if (!Py.truthy(slot["hero"])) return w.bytes()
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

    // --- shared helpers of the actions ------------------------------------------------------------------------------------

    const val S_TRAIN_CLAIM = 2118
    const val S_EVENT_REWARD = 1762
    const val S_GEAR = 106
    const val S_JEWEL = 3080
    const val S_EXPLORE_SLOT = 2272
    const val S_EXPLORE_REWARD = 2276
    const val S_HERO_UPDATE = 46
    const val HONOR = 7L
    const val VIP_LEVEL = 27L
    const val ERROR_REWARD_WAITING = 38002
    const val ERROR_TRAINING = 38003
    const val ERROR_SEAT_TAKEN = 38005
    const val ERROR_SLOT = 51000
    const val ERROR_EXPLORE_ID = 51001
    const val ERROR_STATUS = 51002
    const val ERROR_NO_HERO = 51003
    const val ERROR_SLOTS_MAX = 51004
    const val ERROR_RESOURCES = 4000
    const val ERROR_INVALID = 102

    /** `random.Random` seeded with the first 8 bytes (little-endian) of SHA-256 of the parts joined by `|` (`_rng`). */
    private fun rng(vararg parts: Any): PyRandom {
        val text = parts.joinToString("|") { if (it is JValue) PyDocs.str(it) else it.toString() }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return PyRandom.seeded(BigInteger(1, digest.copyOfRange(0, 8).reversedArray()))
    }

    /** S128 of the role properties' current (tag, bits) (`_roles`). */
    private fun roles(owned: Owned, fields: List<Long>): Frame =
        Acquisition.S_ROLE to Acquisition.roleUpdatePayload(fields.map { Triple(it, owned.role(it).long("tag"), owned.roleBits(it)) })

    /** Diamond spend: S578 + S1188 ladders, then S128 (the live order of the Blacksmith No CD) (`_diamonds`). */
    private fun diamonds(owned: Owned, price: Long, servedTime: Long): List<Frame> {
        if (owned.roleBits(Acquisition.DIAMOND) < BigInteger.valueOf(price)) throw Acquisition.Rejected("Not enough Diamonds", ERROR_RESOURCES)
        owned.roleAdd(Acquisition.DIAMOND, -price)
        return Shops.diamondAchievement(owned, price, servedTime) + roles(owned, listOf(Acquisition.DIAMOND))
    }

    private fun seatOf(document: JObj): JObj? = PyDocs.get(document, "seat")?.let { it as JObj }

    private fun lt(a: JValue?, b: JValue?): Boolean = PyDocs.compare(a ?: JNull, b ?: JNull) < 0

    // --- the training room actions (C1763, C1767, C1769, C1777, C1779) ---------------------------------------------------

    /** C1767 `u32 room, u8 seat` (`decode_train`). */
    fun decodeTrain(payload: ByteArray): JObj {
        if (payload.size != 5) throw Acquisition.Rejected("C1767 is u32 room + u8 seat")
        val r = WireReader(payload)
        return jobj("room" to r.number('I'), "seat" to r.number('B'))
    }

    /** C1767 `u32 room, u8 seat` → S2114 with the player seated (`plan_train`); `document` = the stored `training_state` or null. */
    fun planTrain(request: JObj, owned: Owned, inputs: DailyInputs, document: JValue?, now: Long): Plan {
        val doc = trainingDocument(document)
        var rooms = roomsView(doc, owned.state, inputs, now)
        var room = find(rooms, PyDocs.at(request, "room"))
        val seat = PyDocs.at(doc, "seat")
        if (Py.truthy(seat)) {
            val byUid = LinkedHashMap<JValue, Room>().also { m -> rooms.forEach { m[it.uid] = it } }
            if (BigInteger.valueOf(now) >= seatEnd(seat as JObj, byUid, inputs)) throw Acquisition.Rejected("Training reward is available", ERROR_REWARD_WAITING)
            throw Acquisition.Rejected("Already training", ERROR_TRAINING)
        }
        val row = row(inputs, room.row)
        if (!levelOk(room, roleBits(owned.state, ROLE_LEVEL, 1), inputs)) throw Acquisition.Rejected("Your level is too low", ERROR_LEVEL)
        val wanted = PyDocs.at(request, "seat")
        if (!(!lt(wanted, JInt(0)) && lt(wanted, JInt(row.long("seats")))) || room.players.any { it.seat == wanted }) {
            throw Acquisition.Rejected("The training position is occupied", ERROR_SEAT_TAKEN)
        }
        if (room.expiresAt != null && room.expiresAt != JNull && !lt(JInt(now), room.expiresAt)) throw Acquisition.Rejected("Training room does not exist", ERROR_NO_ROOM)
        doc["seat"] = jobj("room" to room.uid, "row" to room.row, "seat" to wanted, "seated_at" to now)
        rooms = roomsView(doc, owned.state, inputs, now)
        room = find(rooms, PyDocs.at(request, "room"))
        return Plan(jobj("room" to room.uid, "seat" to wanted, "training_state_after" to doc, "evidence_class" to "capture_observed"),
            listOf(S_ROOM_INFO to roomInfoPayload(room, seatOf(doc), now, inputs, rooms)))
    }

    /**
     * C1777 → S128 EXP (player level-ups included), S2118 Reward; Honor is credited without a frame (`plan_claim`). The
     * attack score is the save's.
     */
    fun planClaim(owned: Owned, inputs: DailyInputs, document: JValue?, now: Long): Plan {
        val doc = trainingDocument(document)
        val rooms = roomsView(doc, owned.state, inputs, now)
        val (seconds, _) = trained(doc, rooms, inputs, now)
        val room = find(rooms, PyDocs.at(doc.obj("seat"), "room"))
        val score = attackScore(owned.state, inputs)
        val boost = trainingBoost(room, inputs)
        val (exp, honor) = trainingReward(seconds, score, row(inputs, room.row), boost)
        val (frames, levels) = PlayerLevel.grantExp(owned, exp, inputs)
        if (honor.signum() != 0) owned.roleAdd(HONOR, honor)
        doc["seat"] = JNull
        doc["rooms"] = JArr(doc.arr("rooms").filterTo(ArrayList()) { lt(JInt(now), PyDocs.at(it.asObj, "expires_at")) })
        return Plan(jobj("seconds" to seconds, "attack_score" to score, "boost" to boost, "exp" to exp, "honor" to honor,
            "levels_gained" to levels, "training_state_after" to doc, "evidence_class" to "capture_observed_calculation_attack_score_policy"),
            frames + listOf(S_TRAIN_CLAIM to reward(exp, honor)))
    }

    /**
     * C1763 `u8 type` (1 Goblinia, 2 Dwarfia, 3 Dragania) → Diamonds (xiuxing 204) → S2114 of the new room (`plan_create_room`).
     * The row is the one of that type whose level window holds the player's level; VIP ≥ xiuxing 203; one own room at a
     * time, lifetime xiuxing 304 (policy).
     */
    fun planCreateRoom(roomType: Long, owned: Owned, inputs: DailyInputs, document: JValue?, now: Long, servedTime: Long): Plan {
        val doc = trainingDocument(document)
        val level = roleBits(owned.state, ROLE_LEVEL, 1) ?: JNull
        val rows = inputs.trainingRooms().filter { it.long("type") == roomType }
        if (rows.isEmpty()) throw Acquisition.Rejected("Please select a room", ERROR_INVALID)
        val row = rows.firstOrNull { !lt(level, JInt(it.long("min_level"))) && !lt(JInt(it.long("max_level")), level) }
            ?: throw Acquisition.Rejected("Your level is too low", ERROR_LEVEL)
        if (lt(roleBits(owned.state, VIP_LEVEL), JInt(row.long("vip")))) throw Acquisition.Rejected("VIP level too low to create this room", ERROR_INVALID)
        if (doc.arr("rooms").any { lt(JInt(now), PyDocs.at(it.asObj, "expires_at")) }) throw Acquisition.Rejected("You already have a training room", ERROR_INVALID)
        val frames = diamonds(owned, row.long("create_price"), servedTime)
        val uid = PyDocs.at(doc, "next_room")
        doc["next_room"] = JInt(PyDocs.int(uid) + BigInteger.ONE)
        val seat = PyDocs.at(doc, "seat")
        doc["rooms"] = JArr(doc.arr("rooms").filterTo(ArrayList()) {
            lt(JInt(now), PyDocs.at(it.asObj, "expires_at")) || (Py.truthy(seat) && PyDocs.at(seat as JObj, "room") == PyDocs.at(it.asObj, "uid"))
        })
        doc.arr("rooms").add(jobj("uid" to uid, "row" to row["row"], "password_hex" to "", "expires_at" to now + row.long("lifetime")))
        val rooms = roomsView(doc, owned.state, inputs, now)
        val room = find(rooms, uid)
        return Plan(jobj("room" to uid, "row" to row["row"], "price" to row["create_price"], "training_state_after" to doc,
            "evidence_class" to "native_use_policy"), frames + listOf(S_ROOM_INFO to roomInfoPayload(room, seatOf(doc), now, inputs, rooms)))
    }

    /** C1769 `u32 room, cstr password` (owner only) → S2114 (`plan_password`). */
    fun planPassword(request: RoomSecret, owned: Owned, inputs: DailyInputs, document: JValue?, now: Long): Plan {
        val doc = trainingDocument(document)
        val room = ownRoom(doc, request.room)
        if (request.password.size > 32) throw Acquisition.Rejected("Room code too long", ERROR_INVALID)
        room["password_hex"] = JStr(request.password.toHexString())
        val rooms = roomsView(doc, owned.state, inputs, now)
        val uid = PyDocs.at(room, "uid")
        return Plan(jobj("room" to uid, "training_state_after" to doc, "evidence_class" to "native_use_policy"),
            listOf(S_ROOM_INFO to roomInfoPayload(find(rooms, uid), seatOf(doc), now, inputs, rooms)))
    }

    /** C1779 `u32 room` (xiuxing 501 Diamonds for 401 more seconds) → Diamonds → S2114 (`plan_add_time`). */
    fun planAddTime(request: JObj, owned: Owned, inputs: DailyInputs, document: JValue?, now: Long, servedTime: Long): Plan {
        val doc = trainingDocument(document)
        val room = ownRoom(doc, PyDocs.at(request, "room"))
        val row = row(inputs, PyDocs.at(room, "row"))
        val frames = diamonds(owned, row.long("add_time_price"), servedTime)
        val expires = PyDocs.at(room, "expires_at")
        val start = if (lt(expires, JInt(now))) JInt(now) else expires
        room["expires_at"] = JInt(PyDocs.int(start) + BigInteger.valueOf(row.long("duration")))
        val rooms = roomsView(doc, owned.state, inputs, now)
        val uid = PyDocs.at(room, "uid")
        return Plan(jobj("room" to uid, "price" to row["add_time_price"], "training_state_after" to doc, "evidence_class" to "native_use_policy"),
            frames + listOf(S_ROOM_INFO to roomInfoPayload(find(rooms, uid), seatOf(doc), now, inputs, rooms)))
    }

    // --- the forge (C1643, C3747, C3731, C3753) --------------------------------------------------------------------------

    const val C_SMITH = 1643
    const val C_SMITH_NO_CD = 3731
    const val C_CRAFT = 3747
    const val C_CRAFT_NO_CD = 3753
    const val SMITH_CD = 14_400L
    const val NO_CD_PRICE = 300203L

    /** The `(level, EXP)` after the forge EXP (`_settle`): reaching the cap keeps no residual (the item-Fortify rule). */
    private fun settle(level0: Long, exp: Long, cap: Long, awarded: Long, curve: JObj, scale: Long): Pair<Long, Long> {
        var level = level0
        if (level >= cap) throw Acquisition.Rejected("Already at the maximum level", ERROR_INVALID)
        var remaining = exp + awarded
        while (level < cap) {
            val base = curve[level.toString()] ?: throw PyDocs.KeyError(level)
            val need = HeroFortify.expForEquipLevel(PyDocs.long(base), scale)
            if (remaining < need) break
            remaining -= need
            level += 1
        }
        if (level >= cap) remaining = 0
        return level to remaining
    }

    /** A usable forge slot of `kind`: 0 or 1 and not cooling down (`_slot`). */
    private fun forgeSlot(request: JObj, document: JObj, kind: String, now: Long) {
        val slot = PyDocs.at(request, "slot")
        if (slot != JInt(0) && slot != JInt(1)) throw Acquisition.Rejected("Unknown slot", ERROR_INVALID)
        if (lt(JInt(now), PyDocs.index(document.arr(kind), PyDocs.long(slot).toInt()))) throw Acquisition.Rejected("The slot is cooling down", ERROR_INVALID)
    }

    /** C1643 / C3747 `u8 slot, u32 uid` (`decode_pick`). */
    fun decodePick(payload: ByteArray, opcode: Int): JObj {
        if (payload.size != 5) throw Acquisition.Rejected("C$opcode is u8 slot + u32 uid")
        val r = WireReader(payload)
        return jobj("slot" to r.number('B'), "uid" to r.number('I'))
    }

    /** C3731 / C3753 `u32 slot` (`decode_u32_slot`). */
    fun decodeU32Slot(payload: ByteArray, opcode: Int): JObj {
        if (payload.size != 4) throw Acquisition.Rejected("C$opcode is u32 slot")
        return jobj("slot" to WireReader(payload).number('I'))
    }

    /**
     * C1643 `u8 slot, u32 gear uid` → S106 gear record, S1762 type 5 Reward `equip_grow (uid, EXP, levels)`, S128 Honor,
     * S1760 type 5 (slot cooldown 14,400 s) (`plan_smith`); `document` = the stored `forge_state` or null.
     */
    fun planSmith(request: JObj, owned: Owned, inputs: DailyInputs, document: JValue?, now: Long): Plan {
        val doc = forgeDocument(document)
        forgeSlot(request, doc, "smith", now)
        val uid = PyDocs.at(request, "uid")
        val record = owned.state.arr("equipment").map { it.asObj.arr("wire_values") }.firstOrNull { it[0] == uid }
            ?: throw Acquisition.Rejected("Gear is not owned", ERROR_INVALID)
        val loaded = inputs.gearExp(PyDocs.long(record[1]), PyDocs.long(record[4]))
        val awarded = inputs.forgeExp()
        val before = PyDocs.long(record[2])
        val (level, exp) = settle(before, PyDocs.long(record[3]), loaded.long("cap"), awarded, loaded.obj("equipexp"), loaded.long("scale_113"))
        val grown = level - before
        record[2] = JInt(level)
        record[3] = JInt(exp)
        doc.arr("smith")[PyDocs.long(request["slot"]).toInt()] = JInt(now + SMITH_CD)
        val reward = Acquisition.emptyReward()
        reward["equip_grow"] = jarr(jarr(uid, awarded, grown))
        return Plan(jobj("uid" to uid, "level" to level, "exp" to exp, "forge_state_after" to doc, "evidence_class" to "capture_observed"),
            listOf(S_GEAR to HeroFortify.equipmentRecordPayload(record.map { PyDocs.long(it) }),
                S_EVENT_REWARD to (byteArrayOf(T_SMITH.toByte()) + BattleReport.encodeReward(reward)),
                roles(owned, listOf(HONOR)), smithCdFrame(doc, now)))
    }

    /**
     * C3747 `u8 slot, u32 jewel uid` → S3080 jewel record, S1762 type 5 Reward `jewel_grow (uid, EXP, levels)`, S3726
     * cooldowns (`plan_craft`). Equipped jewels live in the formation's 40-byte blocks, the others in the opcode-3072 list
     * (`current.jewelEntriesView`; the plan's `jewel_entries_after`).
     */
    fun planCraft(request: JObj, owned: Owned, current: StateStore.Current, inputs: DailyInputs, document: JValue?, now: Long): Plan {
        val doc = forgeDocument(document)
        forgeSlot(request, doc, "craft", now)
        val view = ItemFortify.jewelryView(owned.state.arr("formation"))
        val entries = current.jewelEntriesView
        val plan = JObj()
        val uid = PyDocs.at(request, "uid")
        val equipped = view[PyDocs.long(uid)]
        val record: MutableList<Long>
        if (equipped != null) {
            record = equipped.record.toMutableList()
        } else {
            val entry = (entries ?: JArr()).firstOrNull { PyDocs.at(it.asObj, "record").asArr[0] == uid }
                ?: throw Acquisition.Rejected("Jewelry is not owned", ERROR_INVALID)
            record = entry.asObj.arr("record").mapTo(ArrayList()) { PyDocs.long(it) }
        }
        val loaded = inputs.jewelExp(record[1], record[4])
        val awarded = inputs.forgeExp()
        val (level, exp) = settle(record[2], record[3], loaded.long("cap"), awarded, loaded.obj("jewelryexp"), loaded.long("scale_jewelry"))
        val grown = level - record[2]
        record[2] = level
        record[3] = exp
        if (equipped != null) {
            val block = owned.state.arr("formation")[equipped.slotIndex].asObj.arr("blocks_40")[equipped.blockIndex].asObj
            block["raw_hex"] = JStr(ItemFortify.jewelryBlockWith(equipped.raw, exp, level).toHexString())
        } else {
            plan["jewel_entries_after"] = JArr(entries!!.mapTo(ArrayList()) { e ->
                if (PyDocs.at(e.asObj, "record").asArr[0] == uid) PyDocs.shallow(e.asObj).also { it["record"] = JArr(record.mapTo(ArrayList<JValue>()) { v -> JInt(v) }) }
                else e
            })
        }
        doc.arr("craft")[PyDocs.long(request["slot"]).toInt()] = JInt(now + SMITH_CD)
        val reward = Acquisition.emptyReward()
        reward["jewel_grow"] = jarr(jarr(uid, awarded, grown))
        for ((k, v) in jobj("uid" to uid, "level" to level, "exp" to exp, "forge_state_after" to doc, "evidence_class" to "capture_observed")) plan[k] = v
        return Plan(plan, listOf(S_JEWEL to EquipEvolve.recordPayload(record),
            S_EVENT_REWARD to (byteArrayOf(T_SMITH.toByte()) + BattleReport.encodeReward(reward)), craftCdFrame(doc, now)))
    }

    /**
     * C3731 / C3753 `u32 slot` (property 300203 = 99 Diamonds) → S578, S1188, S128 Diamonds, then the cooldown frame with
     * that slot cleared (`plan_no_cd`, kind "smith" / "craft").
     */
    fun planNoCd(kind: String, request: JObj, owned: Owned, inputs: DailyInputs, document: JValue?, now: Long, servedTime: Long): Plan {
        val doc = forgeDocument(document)
        val slot = PyDocs.at(request, "slot")
        if (slot != JInt(0) && slot != JInt(1)) throw Acquisition.Rejected("Unknown slot", ERROR_INVALID)
        val index = PyDocs.long(slot).toInt()
        if (!lt(JInt(now), PyDocs.index(doc.arr(kind), index))) throw Acquisition.Rejected("The slot is not cooling down", ERROR_INVALID)
        val price = inputs.prop(NO_CD_PRICE, 99)
        val frames = diamonds(owned, price, servedTime)
        doc.arr(kind)[index] = JInt(0)
        val cd = if (kind == "smith") smithCdFrame(doc, now) else craftCdFrame(doc, now)
        return Plan(jobj("slot" to slot, "price" to price, "forge_state_after" to doc,
            "evidence_class" to if (kind == "smith") "capture_observed" else "native_use_candidate_reply"), frames + listOf(cd))
    }

    // --- Hero Set Out (C2113 … C2125) ------------------------------------------------------------------------------------

    const val C_EXPLORE_HERO = 2113
    const val C_EXPLORE_GO = 2115
    const val C_EXPLORE_REFRESH = 2117
    const val C_EXPLORE_CLAIM = 2119
    const val C_EXPLORE_BUY = 2121
    const val C_EXPLORE_RETURN = 2123
    const val C_EXPLORE_CANCEL = 2125
    const val EXPLORE_GOLD_REFRESH = 252L
    const val EXPLORE_DIAMOND_REFRESH = 253L
    val EXPLORE_SLOT_PRICES = listOf(244L, 245L, 246L, 247L, 248L, 249L)
    const val EXPLORE_TOKEN_KIND = 1L
    const val TEXT_ROBBED = 4653L
    const val TEXT_MET = 4654L
    const val TEXT_GIFT = 4655L
    const val MET_NAME = "a traveler"
    const val ROBBER_NAME = "a robber"
    const val ROBBED_TAIL = "nothing was left"

    /** C2113 / C2115 `u8 slot, u32 hero / dungeon` (`decode_explore_pick`). */
    fun decodeExplorePick(payload: ByteArray, opcode: Int): JObj {
        if (payload.size != 5) throw Acquisition.Rejected("C$opcode is u8 slot + u32")
        val r = WireReader(payload)
        val slot = r.number('B')
        return jobj("slot" to slot, (if (opcode == C_EXPLORE_HERO) "uid" else "explore") to r.number('I'))
    }

    /** C2117 `u8 slot, u8 method` (`decode_refresh`). */
    fun decodeRefresh(payload: ByteArray): JObj {
        if (payload.size != 2) throw Acquisition.Rejected("C2117 is u8 slot + u8 method")
        return jobj("slot" to (payload[0].toInt() and 0xFF), "method" to (payload[1].toInt() and 0xFF))
    }

    /** C2119 / C2123 / C2125 `u8 slot` (`decode_u8_slot`). */
    fun decodeU8Slot(payload: ByteArray, opcode: Int): JObj {
        if (payload.size != 1) throw Acquisition.Rejected("C$opcode is u8 slot")
        return jobj("slot" to (payload[0].toInt() and 0xFF))
    }

    private fun slotOf(document: JObj, pos: JValue?): JObj =
        document.arr("slots").firstOrNull { PyDocs.at(it.asObj, "pos") == pos }?.asObj ?: throw Acquisition.Rejected("Wrong Set Out position", ERROR_SLOT)

    /**
     * Three different dungeons drawn by yingxiongyuanzheng 104 (Gold refresh / new hero) or 105 (Diamond refresh) weights,
     * without repeats (`_draw_choices`, policy).
     */
    private fun drawChoices(inputs: DailyInputs, rng: PyRandom, weightKey: String, count: Int = 3): JArr {
        val pool = inputs.exploreRows().filter { it.long(weightKey) > 0 }.mapTo(ArrayList()) { it.long("id") to it.long(weightKey) }
        val picked = JArr()
        while (pool.isNotEmpty() && picked.size < count) {
            var roll = rng.randrange(pool.sumOf { it.second })
            for (index in pool.indices) {
                val (ident, weight) = pool[index]
                if (roll < weight) {
                    picked.add(JInt(ident))
                    pool.removeAt(index)
                    break
                }
                roll -= weight
            }
        }
        return picked
    }

    /** The heroes of the other slots (`_hero_slots`). */
    private fun heroSlots(document: JObj, pos: JValue?): Set<JValue> =
        document.arr("slots").map { it.asObj }.filter { Py.truthy(it["hero"]) && PyDocs.at(it, "pos") != pos }.mapTo(LinkedHashSet()) { it["hero"]!! }

    /**
     * C2113 `u8 slot, u32 hero` → S2272: the slot's hero (state 1 with three dungeons; a slot changing its hero keeps its
     * dungeons). A hero holds one slot; a slot that is out or back cannot change (51002) (`plan_explore_hero`).
     */
    fun planExploreHero(request: JObj, owned: Owned, inputs: DailyInputs, document: JObj, now: Long, ownerKey: String): Plan {
        val doc = copy(document)
        val slot = slotOf(doc, PyDocs.at(request, "slot"))
        val uid = PyDocs.at(request, "uid")
        if (owned.heroUids().none { JInt(it) == uid }) throw Acquisition.Rejected("You have not assigned Hero yet", ERROR_NO_HERO)
        if (uid in heroSlots(doc, PyDocs.at(slot, "pos"))) throw Acquisition.Rejected("Hero already sets out in another slot", ERROR_STATUS)
        val state = PyDocs.at(slot, "state")
        if (state != JInt(0) && state != JInt(1)) throw Acquisition.Rejected("Status Error", ERROR_STATUS)
        if (state == JInt(0) || !Py.truthy(PyDocs.at(slot, "choices"))) {
            slot["choices"] = drawChoices(inputs, rng("explore_hero", ownerKey, now, PyDocs.at(slot, "pos")), "gold_weight")
        }
        slot["hero"] = uid
        slot["state"] = JInt(1)
        return Plan(jobj("slot" to slot["pos"], "hero" to uid, "explore_state_after" to doc, "evidence_class" to "native_use_policy"),
            listOf(S_EXPLORE_SLOT to slotPayload(slot, now)))
    }

    /** C2115 `u8 slot, u32 dungeon` → S68 Token (yingxiongyuanzheng 108 × 109), S2272 state 2 with 106 seconds (`plan_explore_go`). */
    fun planExploreGo(request: JObj, owned: Owned, inputs: DailyInputs, document: JObj, now: Long): Plan {
        val doc = copy(document)
        val slot = slotOf(doc, PyDocs.at(request, "slot"))
        if (PyDocs.at(slot, "state") != JInt(1) || !Py.truthy(PyDocs.at(slot, "hero"))) throw Acquisition.Rejected("You have not assigned Hero yet", ERROR_NO_HERO)
        val explore = PyDocs.at(request, "explore")
        if (explore !in slot.arr("choices")) throw Acquisition.Rejected("Invalid Set Out ID", ERROR_EXPLORE_ID)
        val row = inputs.exploreRow(PyDocs.long(explore)) ?: throw PyDocs.TypeError("'NoneType' object is not subscriptable")
        val frames = ArrayList<Frame>()
        if (row.long("cost_kind") == EXPLORE_TOKEN_KIND && row.long("cost_count") != 0L) frames += owned.consumeTemplate(row.long("cost_item"), row.long("cost_count"))
        slot["state"] = JInt(2)
        slot["explore"] = explore
        slot["total"] = PyDocs.at(row, "duration")
        slot["returns_at"] = JInt(now + row.long("duration"))
        return Plan(jobj("slot" to slot["pos"], "explore" to explore, "explore_state_after" to doc, "evidence_class" to "capture_observed"),
            frames + listOf(S_EXPLORE_SLOT to slotPayload(slot, now)))
    }

    /**
     * C2117 `u8 slot, u8 method` (1: property 252 Gold, weights 104; 2: property 253 Diamonds, weights 105) → currency
     * frames, S2272 with three new dungeons (`plan_explore_refresh`).
     */
    fun planExploreRefresh(request: JObj, owned: Owned, inputs: DailyInputs, document: JObj, now: Long, servedTime: Long,
                           ownerKey: String): Plan {
        val doc = copy(document)
        val slot = slotOf(doc, PyDocs.at(request, "slot"))
        if (PyDocs.at(slot, "state") != JInt(1)) throw Acquisition.Rejected("Status Error", ERROR_STATUS)
        val method = PyDocs.at(request, "method")
        val price: Long
        val frames: List<Frame>
        val weights: String
        when (method) {
            JInt(1) -> {
                price = inputs.prop(EXPLORE_GOLD_REFRESH, 25_000_000)
                if (owned.roleBits(Acquisition.GOLD) < BigInteger.valueOf(price)) throw Acquisition.Rejected("Not enough Gold", ERROR_RESOURCES)
                owned.roleAdd(Acquisition.GOLD, -price)
                frames = listOf(roles(owned, listOf(Acquisition.GOLD)))
                weights = "gold_weight"
            }
            JInt(2) -> {
                price = inputs.prop(EXPLORE_DIAMOND_REFRESH, 100)
                frames = diamonds(owned, price, servedTime)
                weights = "diamond_weight"
            }
            else -> throw Acquisition.Rejected("Unknown refresh method", ERROR_INVALID)
        }
        slot["choices"] = drawChoices(inputs, rng("explore_refresh", ownerKey, now, PyDocs.at(slot, "pos")), weights)
        return Plan(jobj("slot" to slot["pos"], "method" to method, "price" to price, "explore_state_after" to doc,
            "evidence_class" to "native_use_policy"), frames + listOf(S_EXPLORE_SLOT to slotPayload(slot, now)))
    }

    private fun weighted(rng: PyRandom, pairs: List<Pair<String, Long>>): String? {
        val total = pairs.sumOf { it.second }
        if (total == 0L) return null
        var roll = rng.randrange(total)
        for ((value, weight) in pairs) {
            if (roll < weight) return value
            roll -= weight
        }
        return null
    }

    private fun weightedBox(rng: PyRandom, boxes: List<Triple<Long, Long, Long>>): Pair<Long, Long> {
        var roll = rng.randrange(boxes.sumOf { it.third })
        for ((item, count, weight) in boxes) {
            if (roll < weight) return item to count
            roll -= weight
        }
        return PyDocs.index(boxes, -1).let { it.first to it.second }
    }

    /**
     * What a returning hero brought back (`_outcome`, policy): outcome by yingxiongyuanzheng 301 / 302 / 303 (robbed /
     * met / gift), one box by 204 / 208 / 212 among 202 / 206 / 210 and, for a gift, a second box; a robbed hero found a
     * box too but brings nothing back. The hero EXP is col 110.
     */
    internal fun outcome(inputs: DailyInputs, slot: JObj, ownerKey: String, now: Long): JObj {
        val row = inputs.exploreRow(PyDocs.long(PyDocs.at(slot, "explore"))) ?: throw PyDocs.TypeError("'NoneType' object is not subscriptable")
        val rng = rng("explore_return", ownerKey, now, PyDocs.at(slot, "pos"), PyDocs.at(slot, "explore"))
        val outcomes = listOf("robbed" to row.long("robbed_weight"), "met" to row.long("met_weight"), "gift" to row.long("gift_weight"))
        val kind = weighted(rng, outcomes.filter { it.second > 0 }) ?: "met"
        val boxes = row.arr("boxes").map { it.asObj }.filter { it.long("item") != 0L && it.long("weight") > 0 }
            .map { Triple(it.long("item"), it.long("count"), it.long("weight")) }
        if (kind == "robbed") {
            val found = if (boxes.isNotEmpty()) weightedBox(rng, boxes) else null
            return jobj("kind" to kind, "items" to JArr(), "robbed" to found?.let { jarr(it.first, it.second) }, "hero_exp" to row["hero_exp"])
        }
        val reward = mutableListOf(weightedBox(rng, boxes))
        if (kind == "gift") reward.add(weightedBox(rng, boxes))
        return jobj("kind" to kind, "items" to JArr(reward.mapTo(ArrayList<JValue>()) { jarr(it.first, it.second) }), "hero_exp" to row["hero_exp"])
    }

    /**
     * The state-3 message (`result_text`, policy): text 4653 (robbed) / 4654 (met) / 4655 (met + gift) with the hero's
     * name, the dungeon's name, the found box's name, [MET_NAME] / [ROBBER_NAME], [ROBBED_TAIL] and the hero EXP.
     */
    fun resultText(inputs: DailyInputs, state: JObj, slot: JObj, outcome: JObj): String {
        val heroes = HashMap<JValue?, Map<Long, JValue?>>()
        for (fields in state.arr("heroes")) {
            val values = Acquisition.heroValues(fields.asArr)
            heroes[values[0L] ?: JNull] = values
        }
        val template = heroes[PyDocs.at(slot, "hero")]?.get(1L)
        val name = inputs.heroName(if (Py.truthy(template)) PyDocs.long(template) else 0L).ifEmpty { "Hero" }
        val row = inputs.exploreRow(PyDocs.long(PyDocs.at(slot, "explore"))) ?: throw PyDocs.TypeError("'NoneType' object is not subscriptable")
        val place = inputs.text(row.long("name_text")).ifEmpty { "?" }
        fun itemName(item: JValue): String =
            inputs.text(inputs.item(PyDocs.long(item))?.let { PyDocs.long(it["name_text"] ?: JInt(0)) } ?: 0L).ifEmpty { PyDocs.str(item) }
        val found = PyDocs.at(outcome, "items").asArr.map { itemName(it.asArr[0]) }
        val exp = PyDocs.str(PyDocs.at(outcome, "hero_exp"))
        val values: List<String>
        var text: String
        when (PyDocs.at(outcome, "kind")) {
            JStr("gift") -> {
                values = listOf(name, place, found[0], MET_NAME, found[1], name, exp)
                text = inputs.text(TEXT_GIFT)
            }
            JStr("robbed") -> {
                val robbed = outcome["robbed"]
                values = listOf(name, place, if (Py.truthy(robbed)) itemName(robbed!!.asArr[0]) else "nothing", ROBBER_NAME, ROBBED_TAIL, name, exp)
                text = inputs.text(TEXT_ROBBED)
            }
            else -> {
                values = listOf(name, place, if (found.isNotEmpty()) found[0] else "nothing", MET_NAME, name, exp)
                text = inputs.text(TEXT_MET)
            }
        }
        for ((index, value) in values.withIndex()) text = text.replace("##$index##", value)
        return text
    }

    /** The hero came back: the outcome, the result text, state 3 (`_return`). */
    private fun returnSlot(inputs: DailyInputs, state: JObj, slot: JObj, ownerKey: String, now: Long) {
        val outcome = outcome(inputs, slot, ownerKey, now)
        slot["reward"] = outcome
        slot["text_hex"] = JStr(resultText(inputs, state, slot, outcome).toByteArray(Charsets.UTF_8).toHexString())
        slot["state"] = JInt(3)
    }

    /** C2123 `u8 slot` (sent when the countdown reached 0) → S2272 state 3 with the result text (`plan_explore_return`). */
    fun planExploreReturn(request: JObj, owned: Owned, inputs: DailyInputs, document: JObj, now: Long, ownerKey: String): Plan {
        val doc = copy(document)
        val slot = slotOf(doc, PyDocs.at(request, "slot"))
        if (PyDocs.at(slot, "state") != JInt(2) || lt(JInt(now), PyDocs.at(slot, "returns_at"))) throw Acquisition.Rejected("Status Error", ERROR_STATUS)
        returnSlot(inputs, owned.state, slot, ownerKey, now)
        return Plan(jobj("slot" to slot["pos"], "explore_state_after" to doc, "evidence_class" to "native_use_policy"),
            listOf(S_EXPLORE_SLOT to slotPayload(slot, now)))
    }

    private val heroExpCache = java.util.WeakHashMap<DailyInputs, HashMap<Long, JObj>>()

    /** The Fortify catalog values of one hero template (`hero_exp_inputs`: `hero_template_inputs`, cached per loader). */
    fun heroExpInputs(inputs: DailyInputs, template: Long): JObj {
        synchronized(heroExpCache) { heroExpCache[inputs]?.get(template)?.let { return it } }
        val config = PkFortifyContract.heroTemplateInputs(inputs.tables, template)
        synchronized(heroExpCache) { heroExpCache.getOrPut(inputs) { HashMap() }[template] = config }
        return config
    }

    /** The reference's exception class name of a caught settlement failure (`type(exc).__name__`). */
    private fun pythonName(e: Exception): String? = when (e) {
        is HeroFortify.FortifyRejected -> "FortifyRejected"
        is Acquisition.Rejected -> "AcquisitionRejected"
        is PyValues.ValueError -> "ValueError"
        is PyDocs.KeyError -> "KeyError"
        is IndexOutOfBoundsException -> "IndexError"
        is IllegalArgumentException -> "ValueError"
        else -> null
    }

    /**
     * Hero EXP of a Set Out return (col 110) through the Fortify level settlement (`grant_hero_exp`): (frames, HeroGrow row
     * or null, detail). At the hero's level cap the EXP stops (level pinned, EXP 0); a hero at the cap, not owned or
     * outside the stat model gets nothing — the claim itself still pays.
     */
    fun grantHeroExp(owned: Owned, inputs: DailyInputs, uid: JValue?, awarded: Long): Triple<List<Frame>, JArr?, JObj> {
        val detail = jobj("uid" to uid, "awarded" to awarded, "granted" to 0, "levels_gained" to 0)
        fun detailWith(vararg extra: Pair<String, Any?>): JObj = PyDocs.shallow(detail).also { d -> extra.forEach { (k, v) -> d[k] = io.github.okexodus.openknights.exact.jvalue(v) } }
        val fields = if (Py.truthy(uid)) owned.state.arr("heroes").firstOrNull { Acquisition.heroValues(it.asArr)[0L] == uid }?.asArr else null
        if (awarded <= 0 || fields == null) return Triple(emptyList(), null, detailWith("withheld" to if (awarded <= 0) "no EXP" else "hero not owned"))
        val level: Long
        val settlement: JObj
        val discarded: Long
        val changed: JArr
        val target: Map<Long, JObj>
        val cap: Long
        try {
            val template = Acquisition.heroValues(fields).let { if (1L in it) it[1L] else throw PyDocs.KeyError(1) }
            val config = heroExpInputs(inputs, PyDocs.long(template))
            val resolved = HeroFortify.resolveTargetProfile(fields, config)
            target = LinkedHashMap<Long, JObj>().also { m -> resolved.obj("values").forEach { (k, v) -> m[k.toLong()] = v.asObj } }
            level = PyDocs.long(target.getValue(HeroFortify.LEVEL)["bits"])
            cap = config.long("cap")
            if (level >= cap) return Triple(emptyList(), null, detailWith("withheld" to "at the level cap", "level" to level, "cap" to cap))
            var settled = HeroFortify.settleHeroExp(level, PyDocs.long(target.getValue(HeroFortify.EXP)["bits"]), awarded, cap,
                config.obj("heroexp"), config.long("scale_137"))
            var lost = 0L
            if (settled.bool("reached_cap")) {
                lost = settled.long("residual")
                settled = PyDocs.shallow(settled).also { it["residual"] = JInt(0) }
            }
            settlement = settled
            discarded = lost
            changed = HeroFortify.grownFields(target, settlement, resolved)
        } catch (e: Exception) {
            val name = pythonName(e) ?: throw e
            return Triple(emptyList(), null, detailWith("withheld" to "$name: ${e.message}"))
        }
        val grow = HeroFortify.heroGrowRow(PyDocs.long(uid), awarded, target, changed)
        val after = changed.associate { it.asObj.long("id") to it.asObj.obj("value")["bits"]!! }
        for (f in fields) {
            val field = f.asObj
            val value = after[field.long("id")] ?: continue
            field["value"] = PyDocs.shallow(field.obj("value")).also { it["bits"] = value }
        }
        val frames = if (changed.isNotEmpty()) listOf(S_HERO_UPDATE to HeroFortify.heroPropertyUpdatePayload(PyDocs.long(uid), changed)) else emptyList()
        val gained = settlement.long("levels_gained")
        return Triple(frames, grow, detailWith("granted" to awarded - discarded, "levels_gained" to gained, "level_before" to level,
            "level_after" to level + gained, "exp_after" to settlement["residual"], "cap" to cap,
            "reached_cap" to settlement["reached_cap"], "discarded_at_cap" to discarded))
    }

    /**
     * C2119 `u8 slot` → item frames, S46 hero EXP, S2276 Reward (items + HeroGrow row), then S2272 with the slot back at
     * state 1, the same hero and three new dungeons (`plan_explore_claim`, policy).
     */
    fun planExploreClaim(request: JObj, owned: Owned, inputs: DailyInputs, document: JObj, now: Long, ownerKey: String): Plan {
        val doc = copy(document)
        val slot = slotOf(doc, PyDocs.at(request, "slot"))
        if (PyDocs.at(slot, "state") == JInt(2) && !lt(JInt(now), PyDocs.at(slot, "returns_at"))) returnSlot(inputs, owned.state, slot, ownerKey, now)
        if (PyDocs.at(slot, "state") != JInt(3)) throw Acquisition.Rejected("Status Error", ERROR_STATUS)
        val outcome = PyDocs.at(slot, "reward").takeIf { Py.truthy(it) }?.asObj ?: jobj("items" to JArr())
        val frames = ArrayList<Frame>()
        val reward = Acquisition.emptyReward()
        for (entry in PyDocs.at(outcome, "items").asArr) {
            val (item, count) = entry.asArr
            frames.add(owned.grantItem(PyDocs.long(item), PyDocs.long(count)))
            reward.arr("items").add(jarr(item, count))
        }
        val hero = outcome["hero_exp"]
        val (expFrames, grow, heroExp) = grantHeroExp(owned, inputs, PyDocs.at(slot, "hero"), if (Py.truthy(hero)) PyDocs.long(hero) else 0L)
        if (grow != null) reward["hero_grow"] = jarr(grow)
        val choices = drawChoices(inputs, rng("explore_claim", ownerKey, now, PyDocs.at(slot, "pos")), "gold_weight")
        for ((k, v) in jobj("state" to 1, "explore" to 0, "total" to 0, "returns_at" to 0, "text_hex" to "", "reward" to null, "choices" to choices)) slot[k] = v
        return Plan(jobj("slot" to slot["pos"], "items" to outcome["items"], "hero_exp" to heroExp, "explore_state_after" to doc,
            "evidence_class" to "native_use_policy"),
            frames + expFrames + listOf(S_EXPLORE_REWARD to BattleReport.encodeReward(reward), S_EXPLORE_SLOT to slotPayload(slot, now)))
    }

    /** C2121 (property 244 + owned slots → 100 … 2,000 Diamonds) → Diamonds → S2272 of the new empty slot (`plan_explore_buy`). */
    fun planExploreBuy(owned: Owned, inputs: DailyInputs, document: JObj, now: Long, servedTime: Long): Plan {
        val doc = copy(document)
        val slots = doc.arr("slots")
        if (slots.size >= EXPLORE_SLOT_PRICES.size) throw Acquisition.Rejected("You cannot purchase this many positions", ERROR_SLOTS_MAX)
        val price = inputs.prop(EXPLORE_SLOT_PRICES[slots.size], 0)
        val frames = if (price != 0L) diamonds(owned, price, servedTime) else emptyList()
        var top: JValue = JInt(-1)
        for (s in slots) {
            val pos = PyDocs.at(s.asObj, "pos")
            if (lt(top, pos)) top = pos
        }
        val slot = jobj("pos" to JInt(PyDocs.int(top) + BigInteger.ONE), "hero" to 0, "state" to 0, "choices" to JArr(), "explore" to 0,
            "returns_at" to 0, "total" to 0, "text_hex" to "", "reward" to null)
        slots.add(slot)
        return Plan(jobj("slot" to slot["pos"], "price" to price, "explore_state_after" to doc, "evidence_class" to "native_use_policy"),
            frames + listOf(S_EXPLORE_SLOT to slotPayload(slot, now)))
    }

    /** C2125 `u8 slot` → S2272: an outgoing hero comes back to state 1 with its dungeons; no Token refund (`plan_explore_cancel`). */
    fun planExploreCancel(request: JObj, owned: Owned, inputs: DailyInputs, document: JObj, now: Long): Plan {
        val doc = copy(document)
        val slot = slotOf(doc, PyDocs.at(request, "slot"))
        if (PyDocs.at(slot, "state") != JInt(2)) throw Acquisition.Rejected("Status Error", ERROR_STATUS)
        for ((k, v) in jobj("state" to 1, "explore" to 0, "total" to 0, "returns_at" to 0)) slot[k] = v
        return Plan(jobj("slot" to slot["pos"], "explore_state_after" to doc, "evidence_class" to "native_use_policy"),
            listOf(S_EXPLORE_SLOT to slotPayload(slot, now)))
    }
}
