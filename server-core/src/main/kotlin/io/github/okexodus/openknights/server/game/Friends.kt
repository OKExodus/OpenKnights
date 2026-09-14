package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.game.WorldParticipants.Participant
import java.math.BigInteger
import kotlin.math.abs

/**
 * Friends (`friends.py`): the friend list, pending requests, recommendations / campaign helpers and praise (Pal
 * Points). Friend relations live in the world document `social`, keyed by wire role id; adding someone puts them on
 * your list at once and leaves a pending request with them; accepting adds you back and needs room on both lists. Each
 * character's S18 carries its list, rebuilt from the world at login.
 */
object Friends {
    const val C_PENDING = 353
    const val C_RECOMMEND = 355
    const val C_ADD = 359
    const val C_ADD_NAME = 361
    const val C_REPLY = 363
    const val C_REMOVE = 365
    const val C_PRAISE = 387
    const val C_INFO = 385
    const val C_SPAR = 389
    const val S_PENDING = 384
    const val S_RECOMMEND = 386
    const val S_ADD_RESULT = 388
    const val S_REPLY_RESULT = 390
    const val S_FRIEND_ADD = 394
    const val S_FRIEND_REMOVE = 396
    const val S_ONLINE = 398
    const val S_OFFLINE = 400
    const val S_PRAISE_RESULT = 418
    const val S_INFO = 416
    const val ROLE_MAX_FRIEND = 25
    const val ROLE_FRIEND_POINT = 11
    const val ROLE_VIP = 27
    const val PRAISE_CD_PROPERTY = 123
    const val PRAISE_POINTS = 40L
    const val PRAISE_NOTE_POINTS = 10L
    /** S578 [26, tier, count] rises by one per praise. */
    const val ACH_PRAISE = 26L
    const val KIND_TOWER = 1L
    const val KIND_FRIEND = 2L
    const val KIND_TOP = 3L
    const val HELPER_CD_PROPERTY = 13
    /** MaxFriend = 30 + viplv col 112. */
    const val MAX_BASE = 30L

    const val ERR_SELF = 13000
    const val ERR_NOT_FRIEND = 13001
    const val ERR_ALREADY = 13002
    const val ERR_REPEATED = 13003
    const val ERR_NO_REQUEST = 13004
    const val ERR_MAX = 13005
    const val ERR_NO_PLAYER = 14000

    fun friendsOf(social: JObj, role: Long): List<Long> =
        (social.obj("friends")[role.toString()] as? JArr)?.map { (it as JInt).toLong() } ?: emptyList()

    fun maxFriends(vip: Long, inputs: DailyInputs): Long = MAX_BASE + inputs.vipMaxFriend(vip)

    private fun remaining(until: Long, now: Long) = maxOf(0, until - now)

    /** `int(datetime.fromisoformat(created).timestamp())`, 0 when the text is not a date-time. */
    fun createdEpoch(person: Participant): Long = try {
        java.time.OffsetDateTime.parse(person.created).toEpochSecond()
    } catch (e: java.time.format.DateTimeParseException) {
        0
    }

    /**
     * The last-login field ("Online" = 0, else a time against the viewer's clock): presence times shifted by the
     * viewer's [offset]; a character never seen online shows its creation time; a bot without presence is online.
     */
    fun lastLogin(person: Participant, presence: JObj, offset: Long = 0): Long {
        val entry = presence[person.participantId.toString()]
        if ((entry == null || entry == io.github.okexodus.openknights.exact.JNull) && person.kind == "bot") return 0
        val seen = entry as? JObj ?: JObj()
        if (Py.truthy(seen["online"])) return 0
        val lastSeen = seen["last_seen"]
        val at = if (Py.truthy(lastSeen)) (lastSeen as JInt).toLong() else createdEpoch(person)
        return if (at != 0L) maxOf(1, at + offset) else 1
    }

    private fun captainLevel(person: Participant): Long = person.lineup.firstOrNull { it.template == person.leaderTemplate }?.level ?: 1

    /**
     * STC_FRIEND_CLIENT: `u32 id, cstr name, u32 level, u32 last login (0 = online), u32 captain template, u32 captain
     * level, u64 power, i32 helper cd, i32 praise cd`.
     */
    fun record(person: Participant, presence: JObj, now: Long, helperUntil: Long = 0, praiseUntil: Long = 0, offset: Long = 0): ByteArray {
        val last = lastLogin(person, presence, offset)
        return WireWriter().u32(person.participantId).raw(person.nameRaw).raw(byteArrayOf(0))
            .u32(person.level).u32(last).u32(person.leaderTemplate).u32(captainLevel(person))
            .number('Q', person.power ?: BigInteger.ZERO).number('i', remaining(helperUntil, now)).number('i', remaining(praiseUntil, now)).bytes()
    }

    /** The same record in the S18 friends-section form. */
    fun sectionEntry(person: Participant, presence: JObj, now: Long, helperUntil: Long = 0, praiseUntil: Long = 0, offset: Long = 0): JObj {
        val last = lastLogin(person, presence, offset)
        return jobj("wire_u32_1" to person.participantId, "cstring_hex" to person.nameRaw.toHexString(),
            "wire_values_after_string" to jarr(person.level, last, person.leaderTemplate, captainLevel(person),
                person.power ?: BigInteger.ZERO, remaining(helperUntil, now), remaining(praiseUntil, now)))
    }

    fun praiseUntil(social: JObj, role: Long, target: Long, inputs: DailyInputs): Long {
        val at = social.obj("praise")["$role:$target"]
        return if (Py.truthy(at)) (at as JInt).toLong() + inputs.prop(PRAISE_CD_PROPERTY, 7200) else 0
    }

    /** End of the pair's campaign-helper cooldown (the world social "helper" time + property 13). */
    fun helperUntil(social: JObj, role: Long, target: Long, inputs: DailyInputs): Long {
        val helper = social["helper"].let { if (Py.truthy(it)) it as JObj else JObj() }
        val at = helper["$role:$target"]
        return if (Py.truthy(at)) (at as JInt).toLong() + inputs.prop(HELPER_CD_PROPERTY, 1800) else 0
    }

    fun friendsSection(social: JObj, role: Long, people: Map<Long, Participant>, presence: JObj, inputs: DailyInputs, now: Long, offset: Long = 0): JObj {
        val entries = JArr()
        for (target in friendsOf(social, role)) {
            val person = people[target] ?: continue
            entries.add(sectionEntry(person, presence, now, helperUntil(social, role, target, inputs), praiseUntil(social, role, target, inputs), offset))
        }
        return jobj("count" to entries.size, "entries" to entries)
    }

    /** `u8 n, n × record`; [helperOf] gives the helper cooldown end shown in the record (the helper list). */
    fun listPayload(people: List<Participant>, presence: JObj, now: Long, offset: Long = 0, helperOf: ((Participant) -> Long)? = null): ByteArray {
        if (people.size > 255) throw IllegalArgumentException("bytes must be in range(0, 256)")
        val w = WireWriter().u8(people.size)
        for (p in people) w.raw(record(p, presence, now, offset = offset, helperUntil = helperOf?.invoke(p) ?: 0))
        return w.bytes()
    }

    /** S384 `u8 n, n × record`: who added this player and is not on the player's list yet. */
    fun pendingPayload(social: JObj, role: Long, people: Map<Long, Participant>, presence: JObj, now: Long, offset: Long = 0): ByteArray {
        val mine = friendsOf(social, role).toSet()
        val requests = (social.obj("requests")[role.toString()] as? JArr) ?: JArr()
        val rows = requests.map { (it as JObj).long("from") }.filter { it in people && it !in mine }.map { people.getValue(it) }
        return listPayload(rows.take(255), presence, now, offset)
    }

    /** RoleBase properties shown on the player card. */
    val INFO_ROLE: Map<String, Long> = linkedMapOf("level" to 3L, "reputation" to 12L, "stage" to 13L, "train" to 14L, "star" to 15L,
        "hero_book" to 16L, "equip_book" to 17L, "achieve" to 18L, "pvp_win" to 19L, "pvp_lose" to 20L, "signature" to 21L, "title" to 22L)

    /**
     * C385 `u32 id` → S416, the player card: `u32 id, cstr name, u32 last login (0 = online), u32 level, u32 0, u32
     * stage, u32 tower, u16 hero album, u16 gear album, u32 medal points, u32 PvP wins, u32 PvP losses, u64 power, u32
     * title, cstr signature, u32 leader hero, u32 prestige, u32 stars`. [state] is the target's save, or null (a bot:
     * its roster card — no bot exists in release).
     */
    fun playerInfoPayload(person: Participant, state: JObj?, presence: JObj, now: Long, offset: Long = 0): ByteArray {
        val props = LinkedHashMap<Long, JObj>()
        for (f in (state?.get("role_properties") as? JArr) ?: JArr()) props[(f as JObj).long("id")] = f.obj("value")
        if (state == null && person.kind == "bot") throw NotPorted("friends.player_info_payload of a bot participant")

        fun bits(name: String, default: Long = 0): BigInteger {
            val value = props[INFO_ROLE.getValue(name)]
            val bits = if (value != null && value.isNotEmpty()) value["bits"] ?: JInt(default) else JInt(default)
            return (bits as JInt).value
        }
        val signature = props[INFO_ROLE.getValue("signature")]?.let { ((it["raw_hex"] as? JStr)?.value ?: "").hexBytes() } ?: ByteArray(0)
        val cut = signature.indexOf(0.toByte()).let { if (it < 0) signature else signature.copyOfRange(0, it) }
        val last = lastLogin(person, presence, offset)
        val album = BigInteger.valueOf(0xFFFF)
        return WireWriter().u32(person.participantId).raw(person.nameRaw).raw(byteArrayOf(0))
            .number('I', last).number('I', bits("level", person.level)).number('I', 0L).number('I', bits("stage")).number('I', bits("train"))
            .number('H', bits("hero_book").min(album)).number('H', bits("equip_book").min(album)).number('I', bits("achieve"))
            .number('I', bits("pvp_win")).number('I', bits("pvp_lose")).number('Q', person.power ?: BigInteger.ZERO).number('I', bits("title"))
            .raw(cut).raw(byteArrayOf(0))
            .number('I', person.leaderTemplate).number('I', bits("reputation")).number('I', bits("star")).bytes()
    }

    /**
     * S386 `u8 n, n × record` (C355 `u8 n, u8 kind`): kind 1 = the Friends screen's recommendations, kind 2 = the
     * campaign helper list (with the helper cooldowns). POLICY: the world's other participants nearest in level, then by
     * power.
     */
    fun recommendPayload(social: JObj, role: Long, people: Map<Long, Participant>, presence: JObj, now: Long, count: Long, kind: Long,
                         ownLevel: Long, offset: Long = 0, inputs: DailyInputs? = null): ByteArray {
        val mine = friendsOf(social, role).toSet() + role
        val candidates = people.filter { (pid, _) -> pid !in mine }.values
            .sortedWith(compareBy<Participant> { abs(it.level - ownLevel) }.thenByDescending { it.power ?: BigInteger.ZERO }.thenBy { it.participantId })
        val helperOf: ((Participant) -> Long)? = if (kind == 2L && inputs != null) { p -> helperUntil(social, role, p.participantId, inputs) } else null
        return listPayload(candidates.take(maxOf(0L, minOf(count, 50L)).toInt()), presence, now, offset, helperOf)
    }

    /** `d.setdefault(key, [])` of a list member. */
    private fun listMember(d: JObj, key: String): JArr = (d[key] as? JArr) ?: (d[key]?.let { throw PyDocs.TypeError("not a list") } ?: JArr().also { d[key] = it })

    private fun fromOf(request: JValue): JValue? = PyDocs.at(request as JObj, "from")

    /**
     * C359 / C361: the target joins the player's list; the target gets a pending request (13000 self, 13002 already,
     * 13005 full).
     */
    fun add(social: JObj, role: Long, target: Long, ownMax: Long, now: Long) {
        if (target == role) throw Acquisition.Rejected("You cannot Friend yourself", ERR_SELF)
        val friends = social.obj("friends")
        val requestsDoc = social.obj("requests")
        val mine = listMember(friends, role.toString())
        if (JInt(target) in mine) throw Acquisition.Rejected("The target is your Friend already", ERR_ALREADY)
        if (mine.size >= ownMax) throw Acquisition.Rejected("You have reached the max. limit of Friends", ERR_MAX)
        mine.add(JInt(target))
        val theirs = (friends[target.toString()] as? JArr) ?: JArr()
        if (JInt(role) !in theirs) {
            val requests = listMember(requestsDoc, target.toString())
            if (requests.none { fromOf(it) == JInt(role) }) requests.add(jobj("from" to role, "at" to now))
        }
        val mineRequests = (requestsDoc[role.toString()] as? JArr) ?: JArr()
        requestsDoc[role.toString()] = JArr(mineRequests.filterTo(ArrayList()) { fromOf(it) != JInt(target) })
    }

    /**
     * C363 `u32 id, u8 accept` → S390 result: 0 added, 1 own list full, 2 their list full, 3 rejected (13004 when there
     * is no such request).
     */
    fun reply(social: JObj, role: Long, requester: Long, accept: Boolean, ownMax: Long, theirMax: Long): Int {
        val requestsDoc = social.obj("requests")
        val requests = (requestsDoc[role.toString()] as? JArr) ?: JArr()
        if (requests.none { fromOf(it) == JInt(requester) }) throw Acquisition.Rejected("The Friend Request can't be located", ERR_NO_REQUEST)
        requestsDoc[role.toString()] = JArr(requests.filterTo(ArrayList()) { fromOf(it) != JInt(requester) })
        if (!accept) return 3
        val friends = social.obj("friends")
        val mine = listMember(friends, role.toString())
        val theirs = listMember(friends, requester.toString())
        if (JInt(requester) !in mine && mine.size >= ownMax) return 1
        if (JInt(role) !in theirs && theirs.size >= theirMax) return 2
        if (JInt(requester) !in mine) mine.add(JInt(requester))
        if (JInt(role) !in theirs) theirs.add(JInt(role))
        return 0
    }

    /** C365 `u32 id` → S396 `u32 id` (13001 when not a friend). POLICY: the relation ends on both sides. */
    fun remove(social: JObj, role: Long, target: Long) {
        val friends = social.obj("friends")
        val mine = (friends[role.toString()] as? JArr) ?: JArr()
        if (JInt(target) !in mine) throw Acquisition.Rejected("You cannot remove a player who is not your Friend.", ERR_NOT_FRIEND)
        friends[role.toString()] = JArr(mine.filterTo(ArrayList()) { it != JInt(target) })
        val theirs = (friends[target.toString()] as? JArr) ?: JArr()
        friends[target.toString()] = JArr(theirs.filterTo(ArrayList()) { it != JInt(role) })
    }

    /** The outcome of [planPraise]: whether the praise happened, and its frames. */
    class Praise(val praised: Boolean, val packets: List<Frame>)

    /**
     * C387 `u32 target, u8 kind` → S128 FriendPoint (+40), S578 [26, …, +1], S418 `00` + Reward friend_point 40; the
     * pair's praise cooldown is property 123. A running cooldown (or kind ≠ 2, or not a friend) → S418 `01` + empty
     * Reward. Kind 1 / 3 (tower encounters) belong to the tower (combat).
     */
    fun planPraise(social: JObj, role: Long, target: Long, kind: Long, owned: Owned, inputs: DailyInputs, now: Long): Praise {
        if (kind != KIND_FRIEND || target !in friendsOf(social, role)) {
            return Praise(false, listOf(S_PRAISE_RESULT to byteArrayOf(1) + BattleReport.encodeReward(Acquisition.emptyReward())))
        }
        if (praiseUntil(social, role, target, inputs) > now) {
            return Praise(false, listOf(S_PRAISE_RESULT to byteArrayOf(1) + BattleReport.encodeReward(Acquisition.emptyReward())))
        }
        val frame = owned.roleAdd(ROLE_FRIEND_POINT.toLong(), PRAISE_POINTS)
        val frames = arrayListOf(frame)
        for (entry in owned.state.obj("subsystems").obj("achievements").arr("entries")) {
            val wire = (entry as JObj).arr("wire_values")
            if (wire[0] == JInt(ACH_PRAISE)) {
                wire[2] = JInt((wire[2] as JInt).value + BigInteger.ONE)
                if (wire.size != 3) throw IllegalArgumentException("pack expected 3 items for packing (got ${wire.size})")
                frames.add(Acquisition.S_ACHIEVEMENT to WireWriter().number('B', wire[0]).number('B', wire[1]).number('I', wire[2]).bytes())
            }
        }
        val reward = Acquisition.emptyReward()
        reward["friend_point"] = JInt(PRAISE_POINTS)
        social.obj("praise")["$role:$target"] = JInt(now)
        frames.add(S_PRAISE_RESULT to byteArrayOf(0) + BattleReport.encodeReward(reward))
        return Praise(true, frames)
    }

    /** C355 `u8 count, u8 kind` → {"count", "kind"}. */
    fun decodeRecommend(payload: ByteArray): JObj {
        if (payload.size != 2) throw Acquisition.Rejected("C355 is u8 count, u8 kind")
        return jobj("count" to (payload[0].toInt() and 0xFF), "kind" to (payload[1].toInt() and 0xFF))
    }

    /** C363 `u32 id, u8 accept` → {"id", "accept"}. */
    fun decodeReply(payload: ByteArray): JObj {
        if (payload.size != 5) throw Acquisition.Rejected("C363 is u32 id, u8 accept")
        val r = WireReader(payload)
        return jobj("id" to r.u32(), "accept" to (r.u8() != 0))
    }

    /** C387 `u32 target, u8 kind` → {"target", "kind"}. */
    fun decodePraise(payload: ByteArray): JObj {
        if (payload.size != 5) throw Acquisition.Rejected("C387 is u32 target, u8 kind")
        val r = WireReader(payload)
        return jobj("target" to r.u32(), "kind" to r.u8())
    }

    /** One NUL-terminated name without an inner NUL. */
    fun decodeName(payload: ByteArray, opcode: Int): ByteArray {
        if (payload.isEmpty() || payload.last() != 0.toByte() || payload.copyOfRange(0, payload.size - 1).contains(0.toByte())) {
            throw Acquisition.Rejected("C$opcode is a cstring")
        }
        return payload.copyOfRange(0, payload.size - 1)
    }

    fun decodeId(payload: ByteArray, opcode: Int): Long {
        if (payload.size != 4) throw Acquisition.Rejected("C$opcode is u32")
        return WireReader(payload).u32()
    }
}
