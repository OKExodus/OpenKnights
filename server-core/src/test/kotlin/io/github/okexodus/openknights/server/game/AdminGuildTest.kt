package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.gamedata.TableSource
import io.github.okexodus.openknights.server.store.StateStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AdminGuildTest {
    private class Tables : TableSource {
        override fun names() = listOf("text.csv")
        override fun raw(name: String) = "101,102\n4741,Default notice\n".toByteArray()
    }

    private val inputs = DailyInputs(GameTables(Tables()))
    private val role = 4242L
    private val now = 1_900_000_000L

    private fun current(): StateStore.Current {
        val state = jobj(
            "role_properties" to jarr(
                jobj("id" to 0, "value" to jobj("tag" to 5, "bits" to role)),
                jobj("id" to 3, "value" to jobj("tag" to 5, "bits" to 1)),
            ),
            "items" to JArr(), "heroes" to JArr(), "offline_hero_uids" to JArr(), "formation" to JArr()
        )
        return StateStore.Current(1, "", "", state, ByteArray(0), 0, null, JArr(), emptyList(), JArr(), emptyList(),
            null, null, null, null, null, LinkedHashMap())
    }

    private fun owned(cur: StateStore.Current) = Owned(cur, inputs)

    private fun world(vararg guilds: Pair<String, JObj>): JObj = jobj(
        "profile" to "guilds_world_v1", "next_id" to 10,
        "guilds" to JObj().also { out -> guilds.forEach { out[it.first] = it.second } }
    )

    private fun guild(id: Long, leader: Long, name: String = "Existing") = Guild.newGuild(
        id, name.toByteArray(), "Notice".toByteArray(), leader, now
    )

    @Test
    fun `creation bypasses normal price and level while retaining name checks`() {
        val cur = current()
        val document = world()
        val gid = AdminGuild.createGuild(document, owned(cur), cur, inputs, "Admin Guild".toByteArray(), now)
        assertEquals(10L, gid)
        assertEquals(role, (document.obj("guilds")["10"] as JObj).arr("members")[0].asObj.long("role"))
        assertEquals(Guild.LEADER, (document.obj("guilds")["10"] as JObj).arr("members")[0].asObj.long("position"))
        assertThrows(Acquisition.Rejected::class.java) {
            AdminGuild.createGuild(document, owned(cur), cur, inputs, "admin guild".toByteArray(), now)
        }
    }

    @Test
    fun `creation refuses a character already in a guild`() {
        val cur = current()
        val document = world("1" to guild(1, role))
        assertThrows(Acquisition.Rejected::class.java) {
            AdminGuild.createGuild(document, owned(cur), cur, inputs, "Another".toByteArray(), now)
        }
    }

    @Test
    fun `member leaves successfully and the remaining guild is preserved`() {
        val cur = current()
        val g = guild(1, 4343)
        g.arr("members").add(Guild.newMember(role, Guild.MEMBER, now))
        val document = world("1" to g)
        assertEquals(1L, AdminGuild.leaveGuild(document, owned(cur), cur, inputs, now))
        assertEquals(listOf(4343L), g.arr("members").map { it.asObj.long("role") })
        assertEquals(now + Guild.REJOIN_CD, document.obj("rejoin").long(role.toString()))
    }

    @Test
    fun `leader cannot leave while other members remain`() {
        val cur = current()
        val g = guild(1, role)
        g.arr("members").add(Guild.newMember(4343, Guild.MEMBER, now))
        val document = world("1" to g)
        assertThrows(Acquisition.Rejected::class.java) { AdminGuild.leaveGuild(document, owned(cur), cur, inputs, now) }
        assertEquals(2, (document.obj("guilds")["1"] as JObj).arr("members").size)
    }

    @Test
    fun `only leader can disband and members receive rejoin timestamps`() {
        val cur = current()
        val g = guild(1, role)
        g.arr("members").add(Guild.newMember(4343, Guild.MEMBER, now))
        val document = world("1" to g)
        assertThrows(Acquisition.Rejected::class.java) {
            AdminGuild.disbandGuild(document, owned(cur), cur, inputs, now, confirmed = false)
        }
        AdminGuild.disbandGuild(document, owned(cur), cur, inputs, now, confirmed = true)
        assertFalse("1" in document.obj("guilds").keys)
        assertEquals(now, document.obj("rejoin").long("4242"))
        assertEquals(now, document.obj("rejoin").long("4343"))
    }
}
