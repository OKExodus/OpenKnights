package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.PyInt
import io.github.okexodus.openknights.exact.PyRandom
import io.github.okexodus.openknights.exact.PyText
import io.github.okexodus.openknights.exact.Utf8Lenient
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.game.WorldParticipants.Participant
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Guilds (`guild.py`): the world's guilds and everything a member does outside combat. A guild is a record in the
 * world document `guilds`, shared by every local character; members are keyed by the participant's wire role id (role
 * property 0). What belongs to one character stays in its own save: role 30 Donation ("Accu. Contribution", the
 * guild-shop currency), the personal Guild Tech levels and the guild task board (`guild_task_state`).
 *
 * The functions are pure: guild operations edit a copy of the world document and return frames; the route layer
 * ([SocialRoutes]) writes the world document (optimistic, audited) and runs character changes through the
 * acquisition transaction.
 */
object Guild {
    // --- opcodes ---------------------------------------------------------------------------------------------------------
    const val C_MY_GUILD = 2145
    const val C_MEMBERS = 2147
    const val C_GUILD_LIST = 2149
    const val C_APPLICANTS = 2151
    const val C_CREATE = 2153
    const val C_APPLY = 2155
    const val C_DONATE = 2157
    const val C_APPROVE = 2159
    const val C_KICK = 2161
    const val C_EMBLEM = 2163
    const val C_POSITION_APPLY = 2165
    const val C_TRANSFER = 2167
    const val C_GUILD_MAIL = 2169
    const val C_POSITIONS = 2171
    const val C_QUIT = 2173
    const val C_TECH_LIST = 2175
    const val C_TECH_UP = 2177
    const val C_NOTICE = 2181
    const val C_ACTIVITY = 2183
    const val C_WAR_SIGN = 2185
    const val C_WAR_FIELD = 2187
    const val C_WAR_JOIN = 2189
    const val C_MASKE = 2191
    const val C_BOSS = 2193
    const val C_BOSS_FIGHT = 2195
    const val C_BOSS_CD = 2197
    const val C_BOSS_RESET = 2199
    const val C_WAGE = 2201
    const val C_OTHER = 2203
    const val C_RENAME = 2205
    const val C_TASK_DONATE = 2435
    const val C_TASK_REFRESH = 2437
    const val C_TASK_ACCEPT = 2439
    const val C_TASKS = 2441
    const val C_TASK_CLAIM = 2443
    const val S_GUILD_LIST = 2304
    const val S_MY_GUILD = 2306
    const val S_MEMBERS = 2308
    const val S_CREATE = 2310
    const val S_APPLY = 2312
    const val S_DONATE = 2314
    const val S_TECH_LIST = 2318
    const val S_APPLICANTS = 2320
    const val S_APPROVE = 2322
    const val S_KICK = 2324
    const val S_POSITIONS = 2326
    const val S_QUIT = 2328
    const val S_TECH_PERSONAL = 2330
    const val S_ACTIVITY = 2332
    const val S_WAR = 2334
    const val S_MASKE = 2336
    const val S_BOSS = 2338
    const val S_WAGE = 2340
    const val S_OTHER = 2342
    const val S_TASKS = 2344
    const val S_TASK_REWARD = 2346

    const val ROLE_ID = 0L
    const val ROLE_NAME = 2L
    const val ROLE_LEVEL = 3L
    const val EXP = 4L
    const val GOLD = 6L
    const val HONOR = 7L
    const val DIAMOND = 8L
    const val VIP = 27L
    const val CONTRIBUTION = 30L
    const val LEADER = 100L
    const val VICE = 200L
    const val CAPTAIN = 300L
    const val ELITE = 400L
    const val SENIOR = 500L
    const val MEMBER = 600L
    val POSITIONS = listOf(LEADER, VICE, CAPTAIN, ELITE, SENIOR, MEMBER)
    /** Guild tech 105 = the guild BOSS level. */
    const val MASCOT = 105L
    val TECHS = listOf(102L, 103L, 104L, 105L)
    /** Rows per list page. */
    const val PAGE = 10L
    /** No rejoin cooldown offline (labeled policy). */
    const val REJOIN_CD = 0L
    const val BASE_BADGE = 101L
    const val TASK_PROFILE = "guild_task_state_v1"

    // errors (text 8000000 + code)
    const val ERR_NO_GUILD = 52000
    const val ERR_NO_MEMBER = 52001
    const val ERR_NOT_IN_GUILD = 52002
    const val ERR_OTHER_GUILD = 52003
    const val ERR_APPLIED = 52005
    const val ERR_NO_APPLICATION = 52006
    const val ERR_NOT_YOURS = 52007
    const val ERR_CANNOT_KICK = 52008
    const val ERR_NO_ACCESS = 52009
    const val ERR_EMBLEM_MAX = 52010
    const val ERR_POSITION_HIGHER = 52011
    const val ERR_POSITION_CANNOT = 52012
    const val ERR_CONTRIBUTION = 52013
    const val ERR_LEADER_QUIT = 52015
    const val ERR_FULL = 52016
    const val ERR_TECH_LOCKED = 52017
    const val ERR_RESOURCES = 52018
    const val ERR_TECH_MAX = 52019
    const val ERR_WAR_CLOSED = 52020
    const val ERR_WAR_SIGNED = 52021
    const val ERR_DONATE_MAX = 52025
    const val ERR_BOSS_ALIVE = 52030
    const val ERR_WAGE_CLAIMED = 52035
    const val ERR_REJOIN = 52037
    const val ERR_WORDS = 52042
    const val ERR_LEADER_STAYS = 52034
    const val ERR_UNPAID = 52036
    const val ERR_APPLICATIONS_FULL = 31001
    const val ERR_INVALID = 102
    const val ERR_DIAMONDS = 4000
    const val ERR_TASK_STATE = 70105

    const val WAR_MATCH_HOUR = 19
    /** "No Match Found." (the result mail of a war without an opponent). */
    const val TEXT_NO_MATCH = 17106L

    /** `_copy`: a JSON round trip. */
    private fun copy(value: JObj): JObj = Json.loads(Json.dumps(value)) as JObj

    private fun cstr(raw: ByteArray) = raw + byteArrayOf(0)

    private fun u16(value: Long): ByteArray = WireWriter().u16(maxOf(0, minOf(0xFFFF, value)).toInt()).bytes()

    private fun members(guild: JObj): List<JObj> = guild.arr("members").map { it as JObj }

    private fun rejoin(document: JObj, role: Long): JValue =
        (document["rejoin"] as? JObj ?: JObj())[role.toString()] ?: JInt(0)

    // === the guild record ===============================================================================================

    fun newMember(role: Long, position: Long, now: Long): JObj =
        jobj("role" to role, "position" to position, "contribution" to 0, "joined_at" to now, "gold_day" to null, "gold" to 0,
            "wage_day" to null)

    /** A world guild record; `diamonds` = the guild Diamond pool, `unknown.f70` has no client reader (sent as stored). */
    fun newGuild(guildId: Long, nameRaw: ByteArray, noticeRaw: ByteArray, leader: Long, now: Long): JObj =
        jobj("id" to guildId, "name_hex" to nameRaw.toHexString(), "notice_hex" to noticeRaw.toHexString(), "badge" to BASE_BADGE,
            "popularity" to 0, "resources" to 0, "diamonds" to 0, "techs" to JObj().also { t -> for (x in TECHS) t[x.toString()] = JInt(1) },
            "created_at" to now, "members" to jarr(newMember(leader, LEADER, now)), "applications" to JArr(),
            "war" to jobj("signed_day" to null, "notified_day" to null), "boss" to jobj("day" to null, "resets" to 0),
            "unknown" to jobj("f70" to 0))

    /** (guild id, guild) the participant belongs to, else (0, null). */
    fun guildOf(document: JObj, role: Long): Pair<Long, JObj?> {
        for ((gid, guild) in document.obj("guilds")) {
            if ((guild as JObj).arr("members").any { (it as JObj).long("role") == role }) return PyValues.parseInt(gid).toLong() to guild
        }
        return 0L to null
    }

    fun memberOf(guild: JObj, role: Long): JObj =
        guild.arr("members").map { it as JObj }.firstOrNull { it.long("role") == role }
            ?: throw Acquisition.Rejected("Not a member of this guild", ERR_NOT_YOURS)

    /** Guild level from popularity: 1 + the number of levels whose threshold was reached. */
    fun levelOf(guild: JObj, inputs: DailyInputs): Long {
        var level = 1L
        for (row in inputs.guildLevels()) {
            if (row.long("popularity") != 0L && guild.long("popularity") >= row.long("popularity")) level = maxOf(level, row.long("level") + 1)
        }
        return minOf(level, inputs.guildMaxLevel())
    }

    /** juntuan_dengji[level].113 + juntuan_junhui[badge].106. */
    fun maxMembers(guild: JObj, inputs: DailyInputs): Long =
        inputs.guildLevel(levelOf(guild, inputs)).long("members") + (inputs.guildBadge(guild.long("badge"))?.long("members") ?: 0)

    fun leaderOf(guild: JObj): JObj = guild.arr("members").map { it as JObj }.first { it.long("position") == LEADER }

    /** A position's permission flag from juntuan_quanxian. */
    fun can(position: Long, permission: String, inputs: DailyInputs): Boolean {
        val row = inputs.guildPosition(position)
        return Py.truthy(row) && Py.truthy(row!![permission])
    }

    /** `_require`: the member, when its position holds [permission] (else "No access"). */
    private fun require(guild: JObj, role: Long, permission: String, inputs: DailyInputs): JObj {
        val member = memberOf(guild, role)
        if (!can(member.long("position"), permission, inputs)) throw Acquisition.Rejected("No access", ERR_NO_ACCESS)
        return member
    }

    // === payloads =======================================================================================================

    /**
     * S2306 — no guild: `u32 0, u32 rejoin_cd`; member: `u32 id, u32 badge, cstr name, u16 level, cstr leader, u32
     * resources, u32 popularity, u32 f70, cstr notice, u32 gold donated today, u32 gold cap, u32 position, u16 members,
     * u16 max, u8 salary claimed today, u32 guild Diamonds`.
     */
    fun myGuildPayload(document: JObj, role: Long, people: Map<Long, Participant>, inputs: DailyInputs, now: Long): ByteArray {
        val (gid, guild) = guildOf(document, role)
        if (guild == null) {
            val until = (document["rejoin"] as? JObj)?.get(role.toString())?.let { (it as JInt).toLong() } ?: 0
            return WireWriter().u32(0).u32(maxOf(0, until - now)).bytes()
        }
        val member = memberOf(guild, role)
        val leader = people[leaderOf(guild).long("role")]
        val donated = if (member["gold_day"] == JStr(day(now))) member.long("gold") else 0
        val w = WireWriter().u32(gid).u32(guild.long("badge")).raw(cstr(guild.str("name_hex").hexBytes()))
        w.raw(u16(levelOf(guild, inputs))).raw(cstr(leader?.nameRaw ?: ByteArray(0)))
        w.u32(guild.long("resources")).u32(guild.long("popularity")).u32(guild.obj("unknown").long("f70"))
        w.raw(cstr(guild.str("notice_hex").hexBytes()))
        w.u32(donated).u32(goldCap(people[role], inputs)).u32(member.long("position"))
        w.raw(u16(guild.arr("members").size.toLong())).raw(u16(maxMembers(guild, inputs)))
        val claimed = if (member["wage_day"] == JStr(day(now))) 1 else 0
        return w.u8(claimed).u32(guild.long("diamonds", 0)).bytes()
    }

    /** Daily Gold donation cap: 21,000 × player level (labeled policy). */
    fun goldCap(person: Participant?, inputs: DailyInputs): Long = 21_000L * (person?.level ?: 1)

    private fun <T> page(rows: List<T>, page: Long): Triple<Long, Long, List<T>> {
        val pages = maxOf(1L, -Math.floorDiv(-rows.size.toLong(), PAGE))
        val at = minOf(maxOf(1L, page), pages)
        val from = minOf(rows.size.toLong(), (at - 1) * PAGE).toInt()
        val to = minOf(rows.size.toLong(), at * PAGE).toInt()
        return Triple(at, pages, rows.subList(from, to))
    }

    /**
     * S2308 `u32 guild, u16 page, u16 pages, u8 n, n × (u32 role, cstr name, u16 level, u32 position, u32 accumulated
     * contribution)` — leader first, then by position and contribution.
     */
    fun membersPayload(guildId: Long, guild: JObj, page: Long, people: Map<Long, Participant>, inputs: DailyInputs): ByteArray {
        val rows = members(guild).sortedWith(compareBy<JObj> { it.long("position") }.thenByDescending { it.long("contribution") }
            .thenBy { it.long("joined_at") })
        val (at, pages, chunk) = page(rows, page)
        val w = WireWriter().u32(guildId).raw(u16(at)).raw(u16(pages)).raw(PyDocs.bytes(listOf(chunk.size.toLong())))
        for (m in chunk) {
            val person = people[m.long("role")]
            w.u32(m.long("role")).raw(cstr(person?.nameRaw ?: "?".toByteArray()))
            w.raw(u16(person?.level ?: 1)).u32(m.long("position")).u32(m.long("contribution"))
        }
        return w.bytes()
    }

    /**
     * S2304 `u16 page, u16 pages, u8 n, n × (u32 id, cstr name, u32 badge, u16 level, cstr leader, cstr notice, u16
     * members, u16 max, u8 applied)` — by level then popularity.
     */
    fun guildListPayload(document: JObj, page: Long, role: Long, people: Map<Long, Participant>, inputs: DailyInputs): ByteArray {
        val guilds = document.obj("guilds").entries.map { it.key to (it.value as JObj) }
            .sortedWith(compareBy<Pair<String, JObj>> { -levelOf(it.second, inputs) }.thenByDescending { it.second.long("popularity") }
                .thenBy { PyValues.parseInt(it.first) })
        val (at, pages, chunk) = page(guilds, page)
        val w = WireWriter().raw(u16(at)).raw(u16(pages)).raw(PyDocs.bytes(listOf(chunk.size.toLong())))
        for ((gid, guild) in chunk) {
            val leader = people[leaderOf(guild).long("role")]
            val applied = guild.arr("applications").any { (it as JObj).long("role") == role }
            w.number('I', PyValues.parseInt(gid)).raw(cstr(guild.str("name_hex").hexBytes())).u32(guild.long("badge"))
            w.raw(u16(levelOf(guild, inputs))).raw(cstr(leader?.nameRaw ?: ByteArray(0)))
            w.raw(cstr(guild.str("notice_hex").hexBytes())).raw(u16(guild.arr("members").size.toLong()))
            w.raw(u16(maxMembers(guild, inputs))).raw(PyDocs.bytes(listOf(if (applied) 1L else 0L)))
        }
        return w.bytes()
    }

    /** S2342 `u32 id, u32 badge, cstr name, u16 level, cstr leader, u32 popularity, cstr notice, u16 members, u16 max`. */
    fun otherGuildPayload(guildId: Long, guild: JObj, people: Map<Long, Participant>, inputs: DailyInputs): ByteArray {
        val leader = people[leaderOf(guild).long("role")]
        return WireWriter().u32(guildId).u32(guild.long("badge")).raw(cstr(guild.str("name_hex").hexBytes()))
            .raw(u16(levelOf(guild, inputs))).raw(cstr(leader?.nameRaw ?: ByteArray(0)))
            .u32(guild.long("popularity")).raw(cstr(guild.str("notice_hex").hexBytes()))
            .raw(u16(guild.arr("members").size.toLong())).raw(u16(maxMembers(guild, inputs))).bytes()
    }

    /** S2318 `u8 n, n × (u32 tech, u16 level), u32 resources`. */
    fun techListPayload(guild: JObj): ByteArray {
        val techs = guild.obj("techs").entries.map { (t, lv) -> PyValues.parseInt(t) to lv }
            .sortedWith { a, b -> a.first.compareTo(b.first).let { c -> if (c != 0) c else PyDocs.compare(a.second, b.second) } }
        val w = WireWriter().raw(PyDocs.bytes(listOf(techs.size.toLong())))
        for ((t, lv) in techs) w.number('I', t).number('H', lv)
        return w.u32(guild.long("resources")).bytes()
    }

    /**
     * S2320 `u16 page, u16 pages, u8 n, n × (u32 role, cstr name, u32 level, u32 last login, u32 captain template, u32
     * (unresolved), u64 power)`.
     */
    fun applicantsPayload(guild: JObj, page: Long, people: Map<Long, Participant>, presence: JObj, now: Long, offset: Long = 0): ByteArray {
        val rows = guild.arr("applications").map { it as JObj }.sortedBy { it.long("at") }
        val (at, pages, chunk) = page(rows, page)
        val w = WireWriter().raw(u16(at)).raw(u16(pages)).raw(PyDocs.bytes(listOf(chunk.size.toLong())))
        for (application in chunk) {
            val person = people[application.long("role")]
            val last = if (person != null) Friends.lastLogin(person, presence, offset) else 1
            w.u32(application.long("role")).raw(cstr(person?.nameRaw ?: "?".toByteArray()))
            w.u32(person?.level ?: 1).u32(last).u32(person?.leaderTemplate ?: 0).u32(0)
                .number('Q', if (person != null) person.power ?: BigInteger.ZERO else BigInteger.ZERO)
        }
        return w.bytes()
    }

    /**
     * The S2330 document: the character's own levels (the Castle's `guild_tech_state`) shown only while in a guild,
     * capped by that guild's tech levels; techs never raised start at level 1.
     */
    fun personalTechView(personal: JObj?, guild: JObj?, inputs: DailyInputs): JObj {
        if (!Py.truthy(guild)) return jobj("profile" to "guild_tech_state_v1", "in_guild" to false, "techs" to JArr())
        val levels = LinkedHashMap<JValue, JValue>()
        for (t in ((if (Py.truthy(personal)) personal!! else JObj())["techs"] ?: JArr()) as JArr) levels[t.asArr[0]] = t.asArr[1]
        val techs = JArr()
        val guildTechs = guild!!.obj("techs")
        for (ident in guildTechs.keys.map { PyValues.parseLong(it) }.sorted()) {
            val row = inputs.guildTech(ident)
            if (row == null || row.bool("guild_only")) continue
            techs.add(jarr(ident, levels[JInt(ident)] ?: JInt(1), PyDocs.at(guildTechs, ident.toString())))
        }
        return jobj("profile" to "guild_tech_state_v1", "in_guild" to true, "techs" to techs)
    }

    // === activities (Guild Events tab) ==================================================================================

    /**
     * Guild War cell by the schedule on the device clock (labeled policy): registration until 19:00 (state 1),
     * 19:00–20:00 matching (2), from 20:00 the war (3), from 00:00 to 06:00 "Not yet start" (0).
     */
    fun warState(nowLocalHour: Int): Int = when {
        nowLocalHour in 6 until 19 -> 1
        nowLocalHour == 19 -> 2
        nowLocalHour >= 20 -> 3
        else -> 0
    }

    /** `_day(now)`: the device clock's local day ([Shops.dayOf]). */
    fun day(now: Long): String = Shops.dayOf(now)

    /** `_hour(now)`: the device clock's local hour. */
    fun hour(now: Long): Int = Shops.localDatetime(now).hour

    const val BOSS_OPEN_PROPERTY = 210006L
    const val BOSS_CLOSE_PROPERTY = 210007L

    /** Guild BOSS window 15:00–22:00 on the device clock (properties 210006 / 210007). */
    fun bossOpen(now: Long, inputs: DailyInputs? = null): Boolean {
        val at = Shops.localDatetime(now)
        val second = at.hour * 3600L + at.minute * 60L + at.second
        val start = inputs?.prop(BOSS_OPEN_PROPERTY, 54_000) ?: 54_000
        val end = inputs?.prop(BOSS_CLOSE_PROPERTY, 79_200) ?: 79_200
        return second in start until end
    }

    /** S2332 ×4 (C2183): war `01 state flag`, Maske `02 state`, BOSS `03 open`, tasks `04 state`. */
    fun activityFrames(guild: JObj, now: Long, inputs: DailyInputs? = null): List<Frame> {
        val state = warState(hour(now))
        // the flag is the sign-up during registration; offline there is no opponent, so it stays 0 afterwards
        val signed = if (guild.obj("war")["signed_day"] == JStr(day(now)) && state == 1) 1 else 0
        val maske = if (hour(now) == 19) 1 else 0
        val war = byteArrayOf(1, state.toByte()) + (if (state != 0) byteArrayOf(signed.toByte()) else ByteArray(0))
        return listOf(S_ACTIVITY to war, S_ACTIVITY to byteArrayOf(2, maske.toByte()),
            S_ACTIVITY to byteArrayOf(3, if (bossOpen(now, inputs)) 1 else 0), S_ACTIVITY to byteArrayOf(4, 1))
    }

    /** The day's matching result is due: signed up today, matching began (19:00 local) and nobody was told yet. */
    fun warResultsDue(guild: JObj, now: Long): Boolean {
        val today = day(now)
        val war = guild.obj("war")
        return war["signed_day"] == JStr(today) && hour(now) >= WAR_MATCH_HOUR && war["notified_day"] != JStr(today)
    }

    /** Epoch of today's 19:00 on the device clock. */
    fun matchTime(now: Long): Long {
        val at = Shops.localDatetime(now)
        return now - ((at.hour - WAR_MATCH_HOUR) * 3600L + at.minute * 60L + at.second)
    }

    /**
     * S2338 `u16 level, u8 kills/resets, u32 challenge cd, u32 hp, u32 max hp`: the BOSS level is the Mascot tech level;
     * offline nobody can fight it — full HP, no CD.
     */
    fun bossPayload(guild: JObj, inputs: DailyInputs): ByteArray {
        val level = guild.obj("techs")[MASCOT.toString()] ?: JInt(1)
        val row = inputs.guildBoss(PyDocs.long(level)) ?: JObj()
        val hp = row["hp"] ?: JInt(0)
        return WireWriter().number('H', level).number('B', 0).number('I', 0).number('I', hp).number('I', hp).bytes()
    }

    // === guild operations (edit the world document copy) =================================================================

    fun validateName(raw: ByteArray, document: JObj, inputs: DailyInputs, exclude: String? = null): ByteArray {
        val text = PyText.strip(Utf8Lenient.decodeReplace(raw))
        if (text.isEmpty()) throw Acquisition.Rejected("Please enter the name", ERR_INVALID)
        if (PyText.length(text) > 12 || "\u0000\n\r".any { it in text }) throw Acquisition.Rejected("Inappropriate words", ERR_WORDS)
        val key = PyText.casefold(text)
        for ((gid, guild) in document.obj("guilds")) {
            if (gid != exclude && PyText.casefold(Utf8Lenient.decodeReplace((guild as JObj).str("name_hex").hexBytes())) == key) {
                throw Acquisition.Rejected("That guild name is taken", ERR_WORDS)
            }
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    /** C2153 `cstr name, cstr notice`: the creator leads a new Lv 1 guild (the requirements are charged by the route). */
    fun create(document: JObj, role: Long, nameRaw: ByteArray, noticeRaw: ByteArray, inputs: DailyInputs, now: Long): Long {
        if (guildOf(document, role).second != null) throw Acquisition.Rejected("Already in a guild", ERR_OTHER_GUILD)
        if (PyDocs.compare(rejoin(document, role), JInt(now)) > 0) throw Acquisition.Rejected("Cannot apply Guild yet", ERR_REJOIN)
        val name = validateName(nameRaw, document, inputs)
        val notice = if (noticeRaw.isNotEmpty()) noticeRaw else inputs.text(0x1285).toByteArray(Charsets.UTF_8)
        val gid = document.long("next_id")
        document["next_id"] = JInt(gid + 1)
        document.obj("guilds")[gid.toString()] = newGuild(gid, name, notice.copyOfRange(0, minOf(120, notice.size)), role, now)
        for (guild in document.obj("guilds").values) {          // a founder's open applications elsewhere lapse
            val g = guild as JObj
            g["applications"] = JArr(g.arr("applications").filter { (it as JObj).long("role") != role }.toMutableList())
        }
        return gid
    }

    /** C2155 `u32 guild` → S2312 `00`: offline applications wait for an officer's approval. */
    fun apply(document: JObj, role: Long, guildId: Long, inputs: DailyInputs, now: Long) {
        if (guildOf(document, role).second != null) throw Acquisition.Rejected("Already in a guild", ERR_OTHER_GUILD)
        val guild = document.obj("guilds")[guildId.toString()] as JObj? ?: throw Acquisition.Rejected("Cannot find the Guild", ERR_NO_GUILD)
        if (PyDocs.compare(rejoin(document, role), JInt(now)) > 0) throw Acquisition.Rejected("Cannot apply Guild yet", ERR_REJOIN)
        if (guild.arr("applications").any { (it as JObj).long("role") == role }) throw Acquisition.Rejected("Applied already", ERR_APPLIED)
        if (guild.arr("applications").size >= 50) throw Acquisition.Rejected("Applications are full", ERR_APPLICATIONS_FULL)
        guild.arr("applications").add(jobj("role" to role, "at" to now))
    }

    /** C2159 `u32 role, u8 1 approve / 2 reject` → S2322 `u32 role, u8 result`. */
    fun decide(document: JObj, role: Long, applicant: Long, accept: Boolean, inputs: DailyInputs, now: Long): Long {
        val (gid, guild) = guildOf(document, role)
        if (guild == null) throw Acquisition.Rejected("Not in a Guild yet", ERR_NOT_IN_GUILD)
        require(guild, role, "approve", inputs)
        if (guild.arr("applications").none { (it as JObj).long("role") == applicant }) {
            throw Acquisition.Rejected("Cannot find the application", ERR_NO_APPLICATION)
        }
        guild["applications"] = JArr(guild.arr("applications").filter { (it as JObj).long("role") != applicant }.toMutableList())
        if (accept) {
            if (guildOf(document, applicant).second != null) throw Acquisition.Rejected("The player belongs to another Guild", ERR_OTHER_GUILD)
            if (guild.arr("members").size >= maxMembers(guild, inputs)) throw Acquisition.Rejected("Max Guild members", ERR_FULL)
            guild.arr("members").add(newMember(applicant, MEMBER, now))
            for (other in document.obj("guilds").values) {
                val o = other as JObj
                o["applications"] = JArr(o.arr("applications").filter { (it as JObj).long("role") != applicant }.toMutableList())
            }
        }
        return gid
    }

    /** C2161 `u32 role`: the actor's position needs quanxian 104 (kick) and the target's position 105 (kickable). */
    fun kick(document: JObj, role: Long, target: Long, inputs: DailyInputs, now: Long): Long {
        val (gid, guild) = guildOf(document, role)
        if (guild == null) throw Acquisition.Rejected("Not in a Guild yet", ERR_NOT_IN_GUILD)
        require(guild, role, "kick", inputs)
        val victim = members(guild).firstOrNull { it.long("role") == target }
            ?: throw Acquisition.Rejected("The player is not in your Guild", ERR_NOT_YOURS)
        if (target == role || !can(victim.long("position"), "kickable", inputs)) throw Acquisition.Rejected("Cannot kick this member", ERR_CANNOT_KICK)
        guild.arr("members").remove(victim)
        rejoinDocument(document)[target.toString()] = JInt(now + REJOIN_CD)
        return gid
    }

    /** `document.setdefault("rejoin", {})`. */
    private fun rejoinDocument(document: JObj): JObj = (document["rejoin"] ?: JObj().also { document["rejoin"] = it }) as JObj

    fun quitGuild(document: JObj, role: Long, now: Long): Long {
        val (gid, guild) = guildOf(document, role)
        if (guild == null) throw Acquisition.Rejected("Not in a Guild yet", ERR_NOT_IN_GUILD)
        val member = memberOf(guild, role)
        if (member.long("position") == LEADER && guild.arr("members").size > 1) throw Acquisition.Rejected("Leader cannot quit", ERR_LEADER_QUIT)
        guild.arr("members").remove(member)
        if (guild.arr("members").isEmpty()) document.obj("guilds").remove(gid.toString())   // the last member leaves: dissolved
        rejoinDocument(document)[role.toString()] = JInt(now + REJOIN_CD)
        return gid
    }

    /** C2167 `u32 role`: the leader hands the guild over and becomes a Member (labeled policy). */
    fun transfer(document: JObj, role: Long, target: Long, inputs: DailyInputs): Long {
        val (gid, guild) = guildOf(document, role)
        if (guild == null) throw Acquisition.Rejected("Not in a Guild yet", ERR_NOT_IN_GUILD)
        val actor = require(guild, role, "transfer", inputs)
        val heir = members(guild).firstOrNull { it.long("role") == target }
        if (heir == null || target == role) throw Acquisition.Rejected("The player is not in your Guild", ERR_NOT_YOURS)
        heir["position"] = JInt(LEADER)
        actor["position"] = JInt(MEMBER)
        return gid
    }

    /** `bytes.strip()`: ASCII whitespace off both ends. */
    private fun stripBytes(raw: ByteArray): ByteArray {
        fun space(b: Byte) = b == 0x20.toByte() || b in 0x09.toByte()..0x0d.toByte()
        var start = 0
        var end = raw.size
        while (start < end && space(raw[start])) start++
        while (end > start && space(raw[end - 1])) end--
        return raw.copyOfRange(start, end)
    }

    fun setNotice(document: JObj, role: Long, noticeRaw: ByteArray, inputs: DailyInputs): Long {
        val (gid, guild) = guildOf(document, role)
        if (guild == null) throw Acquisition.Rejected("Not in a Guild yet", ERR_NOT_IN_GUILD)
        require(guild, role, "notice", inputs)
        if (stripBytes(noticeRaw).isEmpty() || noticeRaw.size > 120) throw Acquisition.Rejected("Inappropriate words", ERR_WORDS)
        guild["notice_hex"] = JStr(noticeRaw.toHexString())
        return gid
    }

    /** Rename price: property 3397, which no property table holds, so the price the client shows (500 Diamonds). */
    const val RENAME_PROPERTY = 3397L
    const val RENAME_DEFAULT = 500L
    /** The rename dialog's maximum length. */
    const val RENAME_MAX = 7

    fun renamePrice(inputs: DailyInputs): Long = inputs.prop(RENAME_PROPERTY, RENAME_DEFAULT)

    /** C2205 `cstr name` (quanxian 120): the checks before the character pays. Returns (guild id, the validated name). */
    fun checkRename(document: JObj, role: Long, nameRaw: ByteArray, inputs: DailyInputs): Pair<Long, ByteArray> {
        val (gid, guild) = guildOf(document, role)
        if (guild == null) throw Acquisition.Rejected("Not in a Guild yet", ERR_NOT_IN_GUILD)
        require(guild, role, "rename", inputs)
        if (PyText.length(Utf8Lenient.decodeReplace(nameRaw)) > RENAME_MAX) throw Acquisition.Rejected("Inappropriate words", ERR_WORDS)
        return gid to validateName(nameRaw, document, inputs, exclude = gid.toString())
    }

    fun rename(document: JObj, role: Long, nameRaw: ByteArray, inputs: DailyInputs): Long {
        val (gid, name) = checkRename(document, role, nameRaw, inputs)
        (PyDocs.at(document.obj("guilds"), gid.toString()) as JObj)["name_hex"] = JStr(name.toHexString())
        return gid
    }

    /**
     * C2163 (the positions with the guild-tech permission): the badge moves to juntuan_junhui 111 for junhui[current].103
     * Diamonds paid by the upgrader, who obtains 112 Metals (role 30); the last badge refuses. Returns (guild id, badge row).
     */
    fun emblemStep(document: JObj, role: Long, inputs: DailyInputs): Pair<Long, JObj> {
        val (gid, guild) = guildOf(document, role)
        if (guild == null) throw Acquisition.Rejected("Not in a Guild yet", ERR_NOT_IN_GUILD)
        require(guild, role, "tech", inputs)
        val badge = inputs.guildBadge(guild.long("badge")) ?: JObj()
        if (!Py.truthy(badge["next"])) throw Acquisition.Rejected("Emblem max level", ERR_EMBLEM_MAX)
        return gid to badge
    }

    fun upgradeEmblem(document: JObj, role: Long, inputs: DailyInputs): Long {
        val (gid, badge) = emblemStep(document, role, inputs)
        (PyDocs.at(document.obj("guilds"), gid.toString()) as JObj)["badge"] = PyDocs.at(badge, "next")
        return gid
    }

    /**
     * C2177 `u32 tech` → S2306, S2318, S2330: cost `tech.105 × (L < 51 ? L : 2L − 50)` guild resources, level < guild
     * level, officers only. Returns (guild id, cost).
     */
    fun upgradeTech(document: JObj, role: Long, tech: Long, inputs: DailyInputs): Pair<Long, Long> {
        val (gid, guild) = guildOf(document, role)
        if (guild == null) throw Acquisition.Rejected("Not in a Guild yet", ERR_NOT_IN_GUILD)
        require(guild, role, "tech", inputs)
        val row = inputs.guildTech(tech)
        val techs = guild.obj("techs")
        if (row == null || tech.toString() !in techs) throw Acquisition.Rejected("You cannot upgrade this Tech yet", ERR_TECH_LOCKED)
        val level = techs.long(tech.toString())
        if (level >= levelOf(guild, inputs)) throw Acquisition.Rejected("Max Tech Lv", ERR_TECH_MAX)
        val cost = techCost(row, level)
        if (guild.long("resources") < cost) throw Acquisition.Rejected("Not enough resources", ERR_RESOURCES)
        guild["resources"] = JInt(guild.long("resources") - cost)
        techs[tech.toString()] = JInt(level + 1)
        return gid to cost
    }

    fun techCost(row: JObj, level: Long): Long = row.long("guild_cost") * (if (level < 51) level else 2 * level - 50)

    /** Contribution flow (labeled policy): the member's accumulated contribution, the guild's resources and popularity. */
    fun contribute(guild: JObj, role: Long, amount: Long) {
        val member = memberOf(guild, role)
        member["contribution"] = JInt(member.int("contribution") + BigInteger.valueOf(amount))
        guild["resources"] = JInt(guild.int("resources") + BigInteger.valueOf(amount))
        guild["popularity"] = JInt(guild.int("popularity") + BigInteger.valueOf(amount))
    }

    // === personal actions (character state + the guild document) =========================================================

    /**
     * C2157 `u32 gold, u32 diamonds` → S2314 `u8 result, Reward`. Labeled policy rates: 10,000 Gold = 1 contribution (whole
     * 10,000s), 1 Diamond = 1 contribution and 1 guild Diamond. Returns (points, reward, diamonds).
     */
    fun donateGold(guild: JObj, role: Long, gold: Long, diamonds: Long, owned: Owned, inputs: DailyInputs, now: Long): Triple<Long, JObj, Long> {
        val member = memberOf(guild, role)
        val today = day(now)
        val donated: BigInteger = if (member["gold_day"] == JStr(today)) PyDocs.int(PyDocs.at(member, "gold")) else BigInteger.ZERO
        if (gold < 0 || diamonds < 0 || !(gold != 0L || diamonds != 0L)) throw Acquisition.Rejected("Nothing to donate", ERR_INVALID)
        if (donated + BigInteger.valueOf(gold) > goldCapFor(owned, inputs)) throw Acquisition.Rejected("Donation exceeds max", ERR_DONATE_MAX)
        if (owned.roleBits(GOLD) < BigInteger.valueOf(gold)) throw Acquisition.Rejected("Not enough Gold", 4000)
        if (owned.roleBits(DIAMOND) < BigInteger.valueOf(diamonds)) throw Acquisition.Rejected("Not enough Diamonds", ERR_DIAMONDS)
        if (gold != 0L) owned.roleAdd(GOLD, -gold)
        if (diamonds != 0L) owned.roleAdd(DIAMOND, -diamonds)
        val points = Math.floorDiv(gold, 10_000L) + diamonds
        owned.roleAdd(CONTRIBUTION, points)
        member["gold_day"] = JStr(today)
        member["gold"] = JInt(donated + BigInteger.valueOf(gold))
        contribute(guild, role, points)
        guild["diamonds"] = JInt(PyDocs.int(guild["diamonds"] ?: JInt(0)) + BigInteger.valueOf(diamonds))
        val reward = Acquisition.emptyReward()
        reward["donation"] = JInt(points)
        return Triple(points, reward, diamonds)
    }

    fun goldCapFor(owned: Owned, inputs: DailyInputs): BigInteger = BigInteger.valueOf(21_000) * owned.roleBits(ROLE_LEVEL)

    /**
     * C2201 → S2340 Reward: the position's daily salary = quanxian 115 × guild level (Gold) and quanxian 119 × item 118.
     * Once per device day (52035); a position with neither is unpaid (52036).
     */
    fun claimWage(guild: JObj, role: Long, owned: Owned, inputs: DailyInputs, now: Long): Pair<List<Frame>, JObj> {
        val member = memberOf(guild, role)
        if (member["wage_day"] == JStr(day(now))) throw Acquisition.Rejected("Claimed today", ERR_WAGE_CLAIMED)
        val row = inputs.guildPosition(member.long("position")) ?: JObj()
        val salary = row.long("salary", 0) * levelOf(guild, inputs)
        val chest = Py.truthy(row["wage_item"]) && Py.truthy(row["wage_count"])
        if (salary == 0L && !chest) throw Acquisition.Rejected("Current position is unpaid", ERR_UNPAID)
        val frames = ArrayList<Frame>()
        val reward = Acquisition.emptyReward()
        if (salary != 0L) {
            owned.roleAdd(GOLD, salary)
            frames.add(Acquisition.S_ROLE to Acquisition.roleUpdatePayload(listOf(Triple(GOLD, owned.role(GOLD).long("tag"), owned.roleBits(GOLD)))))
            reward["gold"] = JInt(salary)
        }
        if (chest) {
            frames.add(owned.grantItem(row.long("wage_item"), row.long("wage_count")))
            reward["items"] = jarr(jarr(row.long("wage_item"), row.long("wage_count")))
        }
        member["wage_day"] = JStr(day(now))
        return frames to reward
    }

    // === guild tasks (per character) ====================================================================================

    private fun rng(text: String): PyRandom {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return PyRandom.seeded(BigInteger(1, digest.copyOfRange(0, 8).reversedArray()))
    }

    /** The star refresh's draw: seeded with `gstar|<owner_key>|<now>`. */
    fun starRng(ownerKey: String, now: Long): PyRandom = rng("gstar|$ownerKey|$now")

    /**
     * The character's task board: 4 tasks (one per quest_juntuan 104 group), star 1, no refreshes; renewed at the
     * device's local midnight (labeled policy).
     */
    fun taskDocument(document: JValue?, inputs: DailyInputs, now: Long, ownerKey: String): JObj {
        val today = day(now)
        if (Py.truthy(document) && (document as JObj)["day"] == JStr(today)) return copy(document)
        val rng = rng("guildtask|$ownerKey|$today")
        val tasks = JArr()
        for (group in 1L..4L) {
            val rows = inputs.guildTasks().filter { it.long("group") == group }
            if (rows.isNotEmpty()) tasks.add(jarr(rng.choice(rows).long("id"), 0, 0, 0))
        }
        return jobj("profile" to TASK_PROFILE, "day" to today, "tasks" to tasks, "star" to 1, "refreshes" to 0)
    }

    /**
     * S2344 `u8 n, n × (u32 task, u32 done, u32 progress, u8 state), u8 star, u16 refreshes used, u32 star cd`
     * (`tasks_payload`); the star timer runs to the next local midnight.
     */
    fun tasksPayload(document: JObj, now: Long): ByteArray {
        val tasks = document.arr("tasks")
        val w = WireWriter().raw(PyDocs.bytes(listOf(tasks.size.toLong())))
        for (t in tasks) w.values("IIIB", t.asArr)
        return w.number('B', PyDocs.at(document, "star")).number('H', PyDocs.at(document, "refreshes"))
            .number('I', maxOf(0L, Shops.nextDayStart(now) - now)).bytes()
    }

    /** (exp, gold, contribution, item) of a claimed task: star.102/10000 × quest.109 / 110 × level, … (`task_reward`). */
    class TaskReward(val exp: BigInteger, val gold: BigInteger, val points: BigInteger, val item: Pair<Long, Long>?)

    fun taskReward(task: JObj, star: Long, level: BigInteger, inputs: DailyInputs): TaskReward {
        val mult = inputs.guildTaskStar(star).int("multiplier")
        val tenThousand = BigInteger.valueOf(10_000)
        val exp = PyInt.floorDiv(mult * task.int("exp") * level, tenThousand)
        val gold = PyInt.floorDiv(mult * task.int("gold") * level, tenThousand)
        val points = PyInt.floorDiv(mult * task.int("contribution") * (PyInt.floorDiv(level, BigInteger.valueOf(100)) + BigInteger.ONE), tenThousand)
        return TaskReward(exp, gold, points, if (task.long("item") != 0L) task.long("item") to task.long("item_count") * 3 else null)
    }

    private fun entryOf(document: JObj, task: Long): JArr? = document.arr("tasks").firstOrNull { it.asArr[0] == JInt(task) } as JArr?

    /**
     * C2435 `u8 1, u32 task, u8 n, n × (u32 item template, u32 qty)` → one S68 per listed template with the stack's
     * absolute count, request order; the task's state becomes 2 (ready) when the summed quantity reaches quest.108.
     */
    fun donateToTask(document: JObj, request: JObj, owned: Owned, inputs: DailyInputs): List<Frame> {
        val taskId = request.long("task")
        val entry = entryOf(document, taskId)
        val task = inputs.guildTask(taskId)
        if (entry == null || task == null || task.long("kind") !in listOf(52L, 53L)) throw Acquisition.Rejected("Not a donation task", ERR_TASK_STATE)
        if (entry[3] == JInt(2) || PyDocs.compare(entry[1], JInt(inputs.prop(2000, 5))) >= 0) throw Acquisition.Rejected("Task is not open", ERR_TASK_STATE)
        var total = 0L
        val frames = ArrayList<Frame>()
        for (pair in request.arr("items")) {
            val template = pair.asArr[0].long
            val qty = pair.asArr[1].long
            val item = inputs.item(template)
            if (item == null || inputs.itemCategory(template) != task.long("category")) throw Acquisition.Rejected("Wrong donation item", ERR_INVALID)
            if (qty != 0L) owned.consumeTemplate(template, qty)
            total += qty
            val stacks = owned.items.filter { it.value.template == template }.keys.sorted()
            if (stacks.isNotEmpty()) {
                frames.add(Acquisition.S_ITEM_UPDATE to Acquisition.itemUpdatePayload(listOf(stacks[0] to owned.items.getValue(stacks[0]).count)))
            }
        }
        if (total < task.long("required")) throw Acquisition.Rejected("Not enough donation", ERR_INVALID)
        entry[1] = JInt(PyDocs.int(entry[1]) + BigInteger.ONE)
        entry[2] = JInt(PyDocs.int(entry[2]) + BigInteger.ONE)
        entry[3] = JInt(2)
        return frames
    }

    /**
     * C2443 `u32 task` → S128 EXP, S128 Gold, S68 item, S2346 Reward, S128 Donation, S2344. Returns (frames, {"exp",
     * "gold", "contribution", "item"}).
     */
    fun claimTask(document: JObj, taskId: Long, owned: Owned, inputs: DailyInputs, guild: JObj?, role: Long): Pair<List<Frame>, JObj> {
        val entry = entryOf(document, taskId)
        val task = inputs.guildTask(taskId)
        if (entry == null || task == null || entry[3] != JInt(2)) throw Acquisition.Rejected("Task is not complete", ERR_TASK_STATE)
        val level = owned.roleBits(ROLE_LEVEL)
        val r = taskReward(task, PyDocs.long(PyDocs.at(document, "star")), level, inputs)
        val frames = ArrayList(PlayerLevel.grantExp(owned, r.exp, inputs).first)
        owned.roleAdd(GOLD, r.gold)
        frames.add(Acquisition.S_ROLE to Acquisition.roleUpdatePayload(listOf(Triple(GOLD, owned.role(GOLD).long("tag"), owned.roleBits(GOLD)))))
        val reward = Acquisition.emptyReward()
        reward["exp"] = JInt(r.exp)
        reward["gold"] = JInt(r.gold)
        reward["donation"] = JInt(r.points)
        if (r.item != null) {
            frames.add(owned.grantItem(r.item.first, r.item.second))
            reward["items"] = jarr(jarr(r.item.first, r.item.second))
        }
        frames.add(S_TASK_REWARD to BattleReport.encodeReward(reward))
        owned.roleAdd(CONTRIBUTION, r.points)
        frames.add(Acquisition.S_ROLE to Acquisition.roleUpdatePayload(listOf(Triple(CONTRIBUTION, owned.role(CONTRIBUTION).long("tag"),
            owned.roleBits(CONTRIBUTION)))))
        entry[2] = JInt(0)
        entry[3] = JInt(0)
        if (guild != null) contribute(guild, role, r.points.longValueExact())
        return frames to jobj("exp" to r.exp, "gold" to r.gold, "contribution" to r.points,
            "item" to r.item?.let { jarr(it.first, it.second) })
    }

    /**
     * C2437 `02`: re-roll the star by questjuntuan_star 103 weights; the first property 2001 (10) refreshes are free,
     * later ones cost property 2004 (20) Diamonds (labeled policy).
     */
    fun refreshStar(document: JObj, owned: Owned, inputs: DailyInputs, servedTime: Long, rng: PyRandom): List<Frame> {
        val frames = ArrayList<Frame>()
        if (PyDocs.compare(PyDocs.at(document, "refreshes"), JInt(inputs.prop(2001, 10))) >= 0) {
            val price = inputs.prop(2004, 20)
            if (owned.roleBits(DIAMOND) < BigInteger.valueOf(price)) throw Acquisition.Rejected("Not enough Diamonds", ERR_DIAMONDS)
            owned.roleAdd(DIAMOND, -price)
            frames.addAll(Shops.diamondAchievement(owned, price, servedTime))
            frames.add(Acquisition.S_ROLE to Acquisition.roleUpdatePayload(listOf(Triple(DIAMOND, owned.role(DIAMOND).long("tag"), owned.roleBits(DIAMOND)))))
        }
        val stars = inputs.guildTaskStars()
        val total = stars.sumOf { it.long("weight") }
        if (total <= 0) throw PyValues.ValueError("empty range for randrange()")
        var pick = rng.randrange(total)
        for (s in stars) {
            if (pick < s.long("weight")) {
                document["star"] = JInt(s.long("star"))
                break
            }
            pick -= s.long("weight")
        }
        document["refreshes"] = JInt(PyDocs.int(PyDocs.at(document, "refreshes")) + BigInteger.ONE)
        return frames
    }

    /** C2439 `u32 task` (non-donation tasks): state 0 → 1. */
    fun acceptTask(document: JObj, taskId: Long, inputs: DailyInputs) {
        val entry = entryOf(document, taskId)
        if (entry == null || entry[3] != JInt(0) || PyDocs.compare(entry[1], JInt(inputs.prop(2000, 5))) >= 0) {
            throw Acquisition.Rejected("Task is not open", ERR_TASK_STATE)
        }
        entry[3] = JInt(1)
    }

    // === request codecs =================================================================================================

    fun decodeU32(payload: ByteArray, opcode: Int): Long {
        if (payload.size != 4) throw Acquisition.Rejected("C$opcode is u32")
        return WireReader(payload).u32()
    }

    /** `payload.split(b"\0")` into exactly [count] NUL-terminated strings. */
    fun decodeCstrings(payload: ByteArray, count: Int, opcode: Int): List<ByteArray> {
        val parts = ArrayList<ByteArray>()
        var start = 0
        for (i in payload.indices) {
            if (payload[i] == 0.toByte()) {
                parts.add(payload.copyOfRange(start, i))
                start = i + 1
            }
        }
        parts.add(payload.copyOfRange(start, payload.size))
        if (parts.size != count + 1 || parts.last().isNotEmpty()) throw Acquisition.Rejected("C$opcode is $count cstring(s)")
        return parts.subList(0, count)
    }

    fun decodeTaskDonate(payload: ByteArray): JObj {
        if (payload.size < 6 || payload[0] != 1.toByte()) throw Acquisition.Rejected("C2435 is u8 1, u32 task, u8 n, n × (u32, u32)")
        val r = WireReader(payload)
        r.u8()
        val task = r.u32()
        val n = r.u8()
        if (payload.size != 6 + 8 * n) throw Acquisition.Rejected("C2435 length")
        val items = JArr()
        repeat(n) { items.add(jarr(r.u32(), r.u32())) }
        return jobj("task" to task, "items" to items)
    }
}
