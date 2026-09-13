package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.game.WorldParticipants.Participant

/**
 * Guilds (`guild.py`), the parts entering the game reads: which guild a participant belongs to and the S2306 "my
 * guild" frame, plus the guild war's result timing. Guild records live in the world document `guilds`.
 */
object Guild {
    const val C_MY_GUILD = 2145
    const val S_MY_GUILD = 2306
    const val LEADER = 100L
    const val ERR_NOT_YOURS = 52007
    const val WAR_MATCH_HOUR = 19
    /** "No Match Found." (the result mail of a war without an opponent). */
    const val TEXT_NO_MATCH = 17106L

    private fun cstr(raw: ByteArray) = raw + byteArrayOf(0)

    private fun u16(value: Long): ByteArray = WireWriter().u16(maxOf(0, minOf(0xFFFF, value)).toInt()).bytes()

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

    fun maxMembers(guild: JObj, inputs: DailyInputs): Long =
        inputs.guildLevel(levelOf(guild, inputs)).long("members") + (inputs.guildBadge(guild.long("badge"))?.long("members") ?: 0)

    fun leaderOf(guild: JObj): JObj = guild.arr("members").map { it as JObj }.first { it.long("position") == LEADER }

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
        val donated = if (member["gold_day"] == io.github.okexodus.openknights.exact.JStr(day(now))) member.long("gold") else 0
        val w = WireWriter().u32(gid).u32(guild.long("badge")).raw(cstr(guild.str("name_hex").hexBytes()))
        w.raw(u16(levelOf(guild, inputs))).raw(cstr(leader?.nameRaw ?: ByteArray(0)))
        w.u32(guild.long("resources")).u32(guild.long("popularity")).u32(guild.obj("unknown").long("f70"))
        w.raw(cstr(guild.str("notice_hex").hexBytes()))
        w.u32(donated).u32(goldCap(people[role], inputs)).u32(member.long("position"))
        w.raw(u16(guild.arr("members").size.toLong())).raw(u16(maxMembers(guild, inputs)))
        val claimed = if (member["wage_day"] == io.github.okexodus.openknights.exact.JStr(day(now))) 1 else 0
        return w.u8(claimed).u32(guild.long("diamonds", 0)).bytes()
    }

    /** Daily Gold donation cap: 21,000 × player level (labeled policy). */
    fun goldCap(person: Participant?, inputs: DailyInputs): Long = 21_000L * (person?.level ?: 1)

    /** `shops.day_of` in release: the device clock's local day. */
    fun day(now: Long): String = DeviceClock.active!!.localDay(now)

    private fun hour(now: Long): Int = DeviceClock.active!!.localDateTime(now).hour

    /** The day's matching result is due: signed up today, matching began (19:00 local) and nobody was told yet. */
    fun warResultsDue(guild: JObj, now: Long): Boolean {
        val today = day(now)
        val war = guild.obj("war")
        return war["signed_day"] == io.github.okexodus.openknights.exact.JStr(today) && hour(now) >= WAR_MATCH_HOUR &&
            war["notified_day"] != io.github.okexodus.openknights.exact.JStr(today)
    }

    /** Epoch of today's 19:00 on the device clock. */
    fun matchTime(now: Long): Long {
        val at = DeviceClock.active!!.localDateTime(now)
        return now - ((at.hour - WAR_MATCH_HOUR) * 3600L + at.minute * 60L + at.second)
    }
}
