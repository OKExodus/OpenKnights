package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.server.store.StateStore
import io.github.okexodus.openknights.server.store.WorldDirectory
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.gamedata.TableSource
import io.github.okexodus.openknights.server.DeviceClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.math.BigInteger

class LeaderboardsTest {
    private fun current(level: Long, stage: Long, medal: Long): StateStore.Current {
        val fields = listOf(3L to level, 13L to stage, 18L to medal).map { (id, value) -> jobj("id" to id, "value" to jobj("bits" to value)) }
        return StateStore.Current(1, "", "", jobj("role_properties" to fields), ByteArray(0), 0, null, JArr(), emptyList(), JArr(), emptyList(),
            null, null, null, null, null, emptyMap())
    }

    @Test
    fun `personal categories show their values and retain paging and my rank`() {
        val people = (1L..10L).map { id -> WorldParticipants.Participant(id, "character", "P$id".toByteArray(), id,
            reputation = 20 - id, power = BigInteger.valueOf(100 - id), leaderTemplate = 880000L + id, created = "same") }
        val states = people.associate { it.participantId to current(it.level, 700 + it.level, 200 + it.level) }
        val weekly = people.associate { it.participantId to mapOf("collect" to it.level, "helper" to it.level, "praise" to it.level) }
        for (type in listOf(2L, 5L, 7L, 8L, 9L, 10L)) {
            val rows = Leaderboards.playerRows(type, people, states, weekly) { cur -> BigInteger.valueOf(cur.state.arr("role_properties")[0].asObj.obj("value").long("bits") * 100) }
            val first = WorldDirectory.buildRankReply(type, 1, rows, 1)
            assertEquals(2, first.totalPages.toInt())
            assertEquals((10L downTo 3L).toList(), first.entries.map { it.roleId })
            assertEquals(10L, first.myRank)
            val second = WorldDirectory.buildRankReply(type, 2, rows, 1)
            assertEquals(listOf(2L, 1L), second.entries.map { it.roleId })
            val top = first.entries.first()
            assertEquals(when (type) { 2L -> 710L; 5L -> 210L; 7L -> 880010L; else -> 10L }, top.valueB)
            assertEquals(BigInteger.valueOf(if (type == 7L) 1000 else 90), top.valueA)
        }
        val legacy = people.map { WorldDirectory.participantRankRow(it) }
        assertEquals(1L, WorldDirectory.buildRankReply(6, 1, legacy, 1).entries.first().roleId)
        assertEquals(1L, WorldDirectory.buildRankReply(4, 1, legacy, 1).entries.first().roleId)
        for (type in listOf(3L, 11L, 99L)) assertEquals(0, WorldDirectory.buildRankReply(type, 1, legacy, 1).entries.size)
    }

    @Test
    fun `guild level uses members power to break ties and points remain zero`() {
        val inputs = DailyInputs(GameTables(object : TableSource {
            override fun names() = listOf("juntuan_dengji.csv")
            override fun raw(name: String) = "101,102,112\n1,1,10\n2,2,100\n3,3,1000\n".toByteArray()
        }))
        val people = listOf(WorldParticipants.Participant(101, "character", "A".toByteArray(), 20, power = BigInteger.valueOf(500)),
            WorldParticipants.Participant(102, "character", "B".toByteArray(), 10, power = BigInteger.valueOf(900)))
        fun guild(id: Long, role: Long, popularity: Long) = jobj("id" to id, "name_hex" to "41", "created_at" to id,
            "popularity" to popularity, "members" to jarr(jobj("role" to role)))
        val doc = jobj("guilds" to jobj("1" to guild(1, 101, 10), "2" to guild(2, 102, 10)))
        val rows = Leaderboards.guildRows(13, doc, people, inputs)
        val reply = WorldDirectory.buildRankReply(13, 1, rows, Guild.guildOf(doc, 101).first)
        assertEquals(listOf(2L, 1L), reply.entries.map { it.roleId })
        assertEquals(listOf(2L, 2L), reply.entries.map { it.level })
        assertEquals(2L, reply.myRank)
        assertEquals(listOf(BigInteger.ONE, BigInteger.ONE), reply.entries.map { it.valueA }, "display members, not the Power used to break ties")
        assertEquals(listOf(10L, 10L), reply.entries.map { it.valueB }, "display guild popularity")
        val scores = WorldDirectory.buildRankReply(12, 1, Leaderboards.guildRows(12, doc, people, inputs), 1)
        assertEquals(listOf(BigInteger.ZERO, BigInteger.ZERO), scores.entries.map { it.valueA }, "unimplemented Guild War points stay zero")
        assertEquals(listOf(0L, 0L), scores.entries.map { it.valueB })
    }

    @Test
    fun `weekly ranks count collections and credit helpers and praise receivers`() {
        val previous = DeviceClock.active
        try {
            DeviceClock.active = DeviceClock(null, { 0L }, { -5 * 3600 })
            val now = Instant.parse("2026-09-15T12:00:00Z").epochSecond
            assertEquals(Instant.parse("2026-09-14T05:00:00Z").epochSecond, Leaderboards.weekStart(now))
            fun collect(at: String) = jobj("action" to "castle_collect", "role_id" to 101,
                "timestamp_utc" to at, "detail" to jobj("types" to listOf(1, 2, 4)))
            fun event(action: String, at: String, target: Long) = jobj("action" to action,
                "timestamp_utc" to at, "detail" to jobj("target" to target, "pair" to listOf(101, target)))
            val collections = listOf(collect("2026-09-14T05:00:00Z"), collect("2026-09-14T04:59:59Z"),
                collect("2026-09-15T12:00:01Z"))
            val events = listOf(event("friend_praise", "2026-09-15T11:00:00Z", 202),
                event("friend_praise", "2026-09-15T11:30:00Z", 202),
                event("campaign_helper", "2026-09-15T11:00:00", 303),
                event("campaign_helper", "2026-09-13T12:00:00Z", 303))
            assertEquals(mapOf(
                101L to mapOf("collect" to 3L, "helper" to 0L, "praise" to 0L),
                202L to mapOf("collect" to 0L, "helper" to 0L, "praise" to 2L),
                303L to mapOf("collect" to 0L, "helper" to 1L, "praise" to 0L)
            ), Leaderboards.weeklyCounts(collections, events, now))
            val nextMonday = Instant.parse("2026-09-21T05:00:00Z").epochSecond
            assertEquals(emptyMap<Long, Map<String, Long>>(), Leaderboards.weeklyCounts(collections, events, nextMonday))
        } finally { DeviceClock.active = previous }
    }
}
