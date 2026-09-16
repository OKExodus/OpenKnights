package io.github.okexodus.openknights.server.session

import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.server.game.VipQuest
import io.github.okexodus.openknights.server.game.HeroStats
import io.github.okexodus.openknights.server.game.Acquisition
import io.github.okexodus.openknights.server.game.Castle
import io.github.okexodus.openknights.server.game.Guild
import io.github.okexodus.openknights.server.game.SocialRoutes
import io.github.okexodus.openknights.protocol.PlayerState
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.game.Chat
import io.github.okexodus.openknights.server.game.long
import io.github.okexodus.openknights.server.store.JdbcSqlDriver
import io.github.okexodus.openknights.server.store.StateStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Local integration proof uses the maintainer's APK and release data, never shipped in the repository. */
class AdminSessionIntegrationTest {
    @TempDir lateinit var temp: Path

    @Test fun `authenticated commands mutate only the issuing save and never shared chat`() {
        val originals = System.getenv("OPENKNIGHTS_ORIGINALS")
        val data = System.getenv("OPENKNIGHTS_RELEASE_DATA")
        assumeTrue(originals != null && data != null, "Local APK and release data are required")
        val service = Service.release(JdbcSqlDriver(), temp.resolve("root"), Path.of(originals!!, "com.enjoygame.hero2d.apk"),
            Path.of(data!!), ServiceLog(echo = false).also { it.keep = true })
        try {
            val token = service.auth.deviceLogin().token
            val account = service.auth.authenticate(token).accountId
            val first = service.characterFactory!!(account, "AdminTest", 0, 40001001L, "local-client")
            val second = service.characterFactory!!(account, "Observer", 1, 40004001L, "local-client")
            fun enter(created: JObj): Session {
                val session = Session(service, "game", 19121)
                session.handle(3, WireWriter().u32(created.long("wire_account_id")).cstring("Android".toByteArray())
                    .cstring("test".toByteArray()).u8(0).cstring(token.toByteArray()).u8(0).bytes())
                for (opcode in Session.QUERY_SEQUENCE.filter { it !in Session.OPTIONAL_QUERIES }) session.handle(opcode, ByteArray(0))
                assertTrue(session.queriesSent, "Session must finish initialization: ${service.log.events.takeLast(12)}")
                assertFalse(session.closed)
                return session
            }
            val game = enter(first)
            val observer = enter(second)
            val pushes = ArrayList<Pair<Int, ByteArray>>()
            service.liveGameSessions[observer] = { pushes.addAll(it) }
            val store = game.stateStore!!
            val chatBefore = service.world!!.document("chat")
            fun command(text: String): List<Pair<Int, ByteArray>> = game.handle(Chat.C_CHAT,
                WireWriter().u8(1).cstring(ByteArray(0)).cstring(text.toByteArray()).bytes())
            fun text(frames: List<Pair<Int, ByteArray>>) = frames.filter { it.first == Chat.S_CHAT }.joinToString("\n") {
                WireReader(it.second).run { u8(); u8(); u32(); cstring(); cstring() }
            }
            assertFalse(store.read().adminCommandsUsed)
            assertTrue(text(command("Hello")).contains("Reach level"))
            assertFalse(store.read().adminCommandsUsed)
            assertTrue(text(command("/missing")).contains("Unknown"))
            assertFalse(store.read().adminCommandsUsed)
            assertTrue(text(command("/help addgold")).contains("/addgold"))
            assertTrue(store.read().adminCommandsUsed)
            val before = PlayerState.role(store.read().state, 6).long("bits")
            val gold = command("/addgold 100")
            assertTrue(gold.any { it.first == 128 }, text(gold))
            assertEquals(before + 100, PlayerState.role(store.read().state, 6).long("bits"))
            fun succeeds(commandText: String, message: String) {
                val result = text(command(commandText))
                assertTrue(result.contains(message), "$commandText: $result")
            }
            succeeds("/adddiamonds 100", "Added 100")
            val item = store.read().state.arr("items").first().asObj.arr("wire_values")[1].long
            succeeds("/additem $item 1", "Added 1")
            succeeds("/addhero 525001", "base form")
            succeeds("/formation", "Slot 1")
            succeeds("/setlevel 30", "set to 30")
            succeeds("/setcastle 5", "set to 5")
            succeeds("/setwarehouse 5", "set to 5")
            succeeds("/levelhero 1 10", "level 10")
            succeeds("/evolvehero 1", "Evolved")
            assertEquals(1, store.historyValues("admin_command", "$.admin_leader_new_template").size)
            val evolved = store.read().state.arr("heroes").first().asArr
            val template = Acquisition.heroValues(evolved).getValue(1)!!.long
            HeroStats.resolveProfile(evolved, service.inputs.heroStatInputs(template))
            succeeds("/completequest", "Claim its reward normally")
            for (vip in listOf(10L, 0L, 1L, 10L)) {
                succeeds("/setvip $vip", "VIP set to $vip")
                val current = store.read()
                assertEquals(vip, PlayerState.role(current.state, 27).long("bits"))
                val points = PlayerState.role(current.state, 28).long("bits")
                assertEquals(vip, io.github.okexodus.openknights.server.game.Recharge.vipLevelFor(java.math.BigInteger.valueOf(points), service.inputs))
                if (vip > 0) assertTrue(io.github.okexodus.openknights.server.game.Recharge.vipLevelFor(java.math.BigInteger.valueOf(points - 1), service.inputs) < vip)
                else assertEquals(0, points)
                assertFalse(VipQuest.tailNeedsUpdate(current, service.inputs), "VIP quest eligibility must refresh immediately")
            }
            succeeds("/createguild CmdGuild", "Created guild")
            assertTrue(Guild.guildOf(service.world!!.document("guilds")!!.second, SocialRoutes.roleOf(store.read())).first > 0)
            succeeds("/disbandguild", "confirm")
            succeeds("/disbandguild confirm", "disbanded")
            assertEquals(0L, Guild.guildOf(service.world!!.document("guilds")!!.second, SocialRoutes.roleOf(store.read())).first)
            val revision = store.read().revision
            succeeds("/setlevel 0", "positive")
            succeeds("/leaveguild", "Not in")
            succeeds("/addhero 40001001", "only one leader")
            assertEquals(revision, store.read().revision, "Rejected commands must not write the save")
            for ((name, gender, starter) in listOf(Triple("NewJansen", "male", "Jansen"), Triple("NewRhee", "female", "Rhee"), Triple("NewTalia", "female", "Talia"))) {
                val result = command("/newcharacter \"$name\" $gender $starter")
                assertTrue(text(result).contains("Created"), text(result))
                val made = service.auth.registry.listCharacters().single { it.name == name }
                assertTrue(service.auth.registry.resolveStateStore(made.characterId).read().adminCommandsUsed)
            }
            assertEquals(chatBefore, service.world!!.document("chat"))
            assertTrue(pushes.isEmpty(), "Command activity must not reach another session")
            assertFalse(observer.stateStore!!.read().adminCommandsUsed)
        } finally { service.close() }
    }
}
