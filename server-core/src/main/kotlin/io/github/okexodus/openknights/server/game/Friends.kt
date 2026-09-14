package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.game.WorldParticipants.Participant
import java.math.BigInteger

/**
 * Friends (`friends.py`): the friend list, pending requests and praise cooldowns. Friend relations live in the world
 * document `social`, keyed by wire role id; each character's S18 carries its list, rebuilt from the world at login.
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
    const val S_ADD_RESULT = 388
    const val S_REPLY_RESULT = 390
    const val S_ONLINE = 398
    const val S_OFFLINE = 400
    const val ROLE_MAX_FRIEND = 25
    const val ROLE_FRIEND_POINT = 11
    const val ROLE_VIP = 27
    const val PRAISE_CD_PROPERTY = 123
    const val HELPER_CD_PROPERTY = 13
    /** MaxFriend = 30 + viplv col 112. */
    const val MAX_BASE = 30L

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
}
