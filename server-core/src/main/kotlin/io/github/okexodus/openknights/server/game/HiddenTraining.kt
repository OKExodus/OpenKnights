package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.PyInt
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

/**
 * The login / query parts of `hidden_training.py`: the training room list S2112 (C1761; offline every room but the
 * default one is the player's own), the room queries (C1765 enter / Train Now and C1773 remove → S2114, C1775 results
 * preview → S2116), the Blacksmith / Crafting cooldowns (S1760 type 5, S3726) and the Hero Set Out slots S2274 (seeded
 * once from the seed frame). Every timer is an absolute deadline sent as the remaining seconds.
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

    // --- the training room actions (C1763, C1767, C1769, C1777, C1779) ---------------------------------------------------

    /** C1767 `u32 room, u8 seat` (`decode_train`). */
    fun decodeTrain(payload: ByteArray): JObj = throw NotPorted("hidden_training.decode_train")

    /** C1767 seat (`plan_train`); `document` = the stored `training_state` or null. */
    fun planTrain(request: JObj, owned: Owned, inputs: DailyInputs, document: JValue?, now: Long): Plan =
        throw NotPorted("hidden_training.plan_train")

    /** C1777 claim (`plan_claim`). */
    fun planClaim(owned: Owned, inputs: DailyInputs, document: JValue?, now: Long): Plan = throw NotPorted("hidden_training.plan_claim")

    /** C1763 `u8 type` room creation (`plan_create_room`). */
    fun planCreateRoom(roomType: Long, owned: Owned, inputs: DailyInputs, document: JValue?, now: Long, servedTime: Long): Plan =
        throw NotPorted("hidden_training.plan_create_room")

    /** C1769 room password (`plan_password`). */
    fun planPassword(request: RoomSecret, owned: Owned, inputs: DailyInputs, document: JValue?, now: Long): Plan =
        throw NotPorted("hidden_training.plan_password")

    /** C1779 `u32 room` more time (`plan_add_time`). */
    fun planAddTime(request: JObj, owned: Owned, inputs: DailyInputs, document: JValue?, now: Long, servedTime: Long): Plan =
        throw NotPorted("hidden_training.plan_add_time")

    // --- the forge (C1643, C3747, C3731, C3753) --------------------------------------------------------------------------

    const val C_SMITH = 1643
    const val C_SMITH_NO_CD = 3731
    const val C_CRAFT = 3747
    const val C_CRAFT_NO_CD = 3753

    /** C1643 / C3747 pick (`decode_pick`). */
    fun decodePick(payload: ByteArray, opcode: Int): JObj = throw NotPorted("hidden_training.decode_pick")

    /** C3731 / C3753 `u32 slot` (`decode_u32_slot`). */
    fun decodeU32Slot(payload: ByteArray, opcode: Int): JObj = throw NotPorted("hidden_training.decode_u32_slot")

    /** C1643 Blacksmith (`plan_smith`); `document` = the stored `forge_state` or null. */
    fun planSmith(request: JObj, owned: Owned, inputs: DailyInputs, document: JValue?, now: Long): Plan =
        throw NotPorted("hidden_training.plan_smith")

    /** C3747 Crafting (`plan_craft`). */
    fun planCraft(request: JObj, owned: Owned, current: StateStore.Current, inputs: DailyInputs, document: JValue?, now: Long): Plan =
        throw NotPorted("hidden_training.plan_craft")

    /** C3731 / C3753 cooldown removal (`plan_no_cd`, kind "smith" / "craft"). */
    fun planNoCd(kind: String, request: JObj, owned: Owned, inputs: DailyInputs, document: JValue?, now: Long, servedTime: Long): Plan =
        throw NotPorted("hidden_training.plan_no_cd")

    // --- Hero Set Out (C2113 … C2125) ------------------------------------------------------------------------------------

    const val C_EXPLORE_HERO = 2113
    const val C_EXPLORE_GO = 2115
    const val C_EXPLORE_REFRESH = 2117
    const val C_EXPLORE_CLAIM = 2119
    const val C_EXPLORE_BUY = 2121
    const val C_EXPLORE_RETURN = 2123
    const val C_EXPLORE_CANCEL = 2125

    /** C2113 / C2115 pick (`decode_explore_pick`). */
    fun decodeExplorePick(payload: ByteArray, opcode: Int): JObj = throw NotPorted("hidden_training.decode_explore_pick")

    /** C2117 refresh (`decode_refresh`). */
    fun decodeRefresh(payload: ByteArray): JObj = throw NotPorted("hidden_training.decode_refresh")

    /** C2119 / C2123 / C2125 `u8 slot` (`decode_u8_slot`). */
    fun decodeU8Slot(payload: ByteArray, opcode: Int): JObj = throw NotPorted("hidden_training.decode_u8_slot")

    fun planExploreHero(request: JObj, owned: Owned, inputs: DailyInputs, document: JObj, now: Long, ownerKey: String): Plan =
        throw NotPorted("hidden_training.plan_explore_hero")

    fun planExploreGo(request: JObj, owned: Owned, inputs: DailyInputs, document: JObj, now: Long): Plan =
        throw NotPorted("hidden_training.plan_explore_go")

    fun planExploreRefresh(request: JObj, owned: Owned, inputs: DailyInputs, document: JObj, now: Long, servedTime: Long,
                           ownerKey: String): Plan = throw NotPorted("hidden_training.plan_explore_refresh")

    fun planExploreReturn(request: JObj, owned: Owned, inputs: DailyInputs, document: JObj, now: Long, ownerKey: String): Plan =
        throw NotPorted("hidden_training.plan_explore_return")

    fun planExploreClaim(request: JObj, owned: Owned, inputs: DailyInputs, document: JObj, now: Long, ownerKey: String): Plan =
        throw NotPorted("hidden_training.plan_explore_claim")

    fun planExploreBuy(owned: Owned, inputs: DailyInputs, document: JObj, now: Long, servedTime: Long): Plan =
        throw NotPorted("hidden_training.plan_explore_buy")

    fun planExploreCancel(request: JObj, owned: Owned, inputs: DailyInputs, document: JObj, now: Long): Plan =
        throw NotPorted("hidden_training.plan_explore_cancel")
}
