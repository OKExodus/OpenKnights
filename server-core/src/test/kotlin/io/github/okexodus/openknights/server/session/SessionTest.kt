package io.github.okexodus.openknights.server.session

import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.gamedata.TableSource
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.store.AccountRegistry
import io.github.okexodus.openknights.server.store.DataRoot
import io.github.okexodus.openknights.server.store.JdbcSqlDriver
import io.github.okexodus.openknights.server.store.LocalAuth
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SessionTest {
    @TempDir
    lateinit var tmp: Path

    private val driver = JdbcSqlDriver()

    /** A made-up `server_list` table: ids 10001-10004 labelled "Click to log in", one with secondary text. */
    private val tables = GameTables(object : TableSource {
        override fun names() = listOf("server_list.csv")
        override fun raw(name: String) = ("101,102,103\n10001,880010000,\n10002,880010000,\n10003,880010000,x\n10004,880010000,\n" +
            "10005,123,\n").toByteArray()
    })

    private fun service(): Pair<Service, String> {
        val root = DataRoot(tmp.resolve("root"), driver).open()
        val generation = root.unbornStaging()
        Service.ownerRegistry(generation, driver)
        val auth = LocalAuth(AccountRegistry(generation.resolve(DataRoot.REGISTRY), driver, strictPaths = true)).also { it.deviceOwner = Service.OWNER }
        val select = CharacterSelect(listOf(40001001, 40004001, 40007001), CharacterSelect.labeledRowIds(tables), "Welcome", "+ Create a character")
        val clock = DeviceClock(null, { 1_800_000_000L }, { 0 })
        val service = Service(driver, ServiceLog(echo = false).also { it.keep = true }, clock, tables, null, auth, null, select, root, generation)
        return service to auth.deviceLogin().token
    }

    private fun cstr(text: String) = text.toByteArray() + byteArrayOf(0)

    private fun signIn(token: String) = WireWriter().cstring("device".toByteArray()).cstring(token.toByteArray()).u16(1)
        .cstring(ByteArray(0)).cstring(ByteArray(0)).cstring(ByteArray(0)).cstring(ByteArray(0)).bytes()

    @Test
    fun `the character list and its encodings match the reference`() {
        val doc = Json.loads(javaClass.getResourceAsStream("/vectors/session.json")!!.use { it.readBytes() }).asObj
        var checked = 0
        for (v in doc.arr("vectors")) {
            val case = v.asObj
            val rows = case.arr("rows").map { r ->
                val row = r.asObj
                val label = CharacterSelect.rowLabel(row.obj("member"), row.long("level"), (row["leader"] as? JInt)?.value?.toLong())
                assertEquals(row.str("label"), label)
                CharacterSelect.Row(row.long("id").toInt(), label, row.long("badge").toInt())
            }
            assertEquals(case.str("payload_hex"), CharacterSelect.serverList(rows, case.long("last_id").toInt(), case.str("announcement")).toHexString())
            checked++
        }
        for (v in doc.arr("mode_2")) {
            val case = v.asObj
            assertEquals(case.str("payload_hex"), CharacterSelect.mode2Payload(case.arr("offers").map { (it as JInt).value.toLong() }).toHexString())
        }
        for (v in doc.arr("errors")) {
            val case = v.asObj
            assertEquals(case.str("payload_hex"), TransactionPackets.errorPayload(case.long("code").toInt()).toHexString())
        }
        assertTrue(checked > 50)
        assertEquals(listOf(10001, 10002, 10004), CharacterSelect.labeledRowIds(tables))
    }

    @Test
    fun `the login hop signs in, lists only the create row before the first character and routes a creation ticket`() {
        val (service, token) = service()
        val login = Session(service, "login", 19121)
        assertEquals(listOf(7680), login.handle(7683, signIn(token)).map { it.first })
        val list = login.handle(7713, cstr("1") + cstr("0.1.0.0")).single()
        assertEquals(7720, list.first)
        // The create row takes the last labelled id; the announcement follows; the last selection is the create row.
        assertEquals(CharacterSelect.serverList(listOf(CharacterSelect.Row(10004, "+ Create a character", CharacterSelect.BADGE_NEW)), 10004, "Welcome")
            .toHexString(), list.second.toHexString())
        val routed = login.handle(7715, WireWriter().u16(10004).bytes() + cstr("1")).single()
        assertEquals(7714, routed.first)
        val r = WireReader(routed.second)
        assertEquals(0, r.u8())
        assertEquals("127.0.0.1", r.cstring())
        assertEquals(19121L, r.u32())
        assertEquals(CharacterSelect.TICKET_FIRST, r.u32())
        assertEquals(2, r.remaining)
        // A row that is not in the list: result 1 ("The server does not exist"), the session stays open.
        assertArrayEquals(byteArrayOf(1), login.handle(7715, WireWriter().u16(10001).bytes() + cstr("1")).single().second)
        assertFalse(login.closed)
        // The game hop with the ticket opens the native creation: S18 mode 1 + S2976, then C289 (name) and C291 (starter).
        val game = Session(service, "game", 19121)
        val c3 = WireWriter().u32(CharacterSelect.TICKET_FIRST).cstring("Android".toByteArray()).cstring("m".toByteArray()).u8(0)
            .cstring(token.toByteArray()).u8(0).bytes()
        assertEquals(listOf(18, 2976), game.handle(3, c3).map { it.first })
        assertEquals(listOf(8), game.handle(7, ByteArray(0)).map { it.first })
        val named = game.handle(289, cstr("Tester") + WireWriter().u8(1).u32(0).bytes())
        assertEquals(listOf(18, 2976), named.map { it.first })
        assertEquals(CharacterSelect.mode2Payload(listOf(40001001, 40004001, 40007001)).toHexString(), named[0].second.toHexString())
        // a starter that was not offered: S6 102, the dialog stays open
        assertEquals(listOf(6), game.handle(291, WireWriter().u32(1).bytes()).map { it.first })
        assertFalse(game.closed)
        // this service has no character factory: the creation itself is not available, answered and closed
        assertEquals(listOf(6), game.handle(291, WireWriter().u32(40004001).bytes()).map { it.first })
        assertTrue(game.closed)
        assertTrue(service.log.events.any { it.str("event") == "not_implemented" })
    }

    @Test
    fun `a malformed name request closes the creation connection`() {
        val (service, token) = service()
        val login = Session(service, "login", 19121)
        login.handle(7683, signIn(token))
        login.handle(7715, WireWriter().u16(10004).bytes() + cstr("1"))
        val game = Session(service, "game", 19121)
        val c3 = WireWriter().u32(CharacterSelect.TICKET_FIRST).cstring("Android".toByteArray()).cstring("m".toByteArray()).u8(0)
            .cstring(token.toByteArray()).u8(0).bytes()
        game.handle(3, c3)
        assertEquals(listOf(6), game.handle(289, cstr("Tester") + byteArrayOf(1)).map { it.first })
        assertTrue(game.closed)
    }

    @Test
    fun `a bad token or an unexpected request closes the connection with the reference's refusal`() {
        val (service, token) = service()
        val login = Session(service, "login", 19121)
        assertArrayEquals(byteArrayOf(1), login.handle(7683, signIn("A".repeat(43))).single().second)
        assertTrue(login.closed)
        assertTrue(login.handle(7713, ByteArray(0)).isEmpty())
        val other = Session(service, "login", 19121)
        other.handle(7683, signIn(token))
        assertEquals(7680, other.handle(9999, ByteArray(0)).single().first)
        assertTrue(other.closed)
        val game = Session(service, "game", 19121)
        val unknownWire = WireWriter().u32(12_345_678).cstring("a".toByteArray()).cstring("b".toByteArray()).u8(0).cstring(token.toByteArray()).u8(0).bytes()
        assertEquals(listOf(6), game.handle(3, unknownWire).map { it.first })
        assertTrue(service.log.events.any { it.str("event") == "authentication_rejected" })
    }

    @Test
    fun `pushes reach only initialised game sessions of the addressed participant`() {
        val (service, _) = service()
        val idle = Session(service, "game", 19121)
        val received = ArrayList<Int>()
        service.liveGameSessions[idle] = { frames -> received.addAll(frames.map { it.first }) }
        assertEquals(0, service.pushToRole(12_345_678, listOf(480 to ByteArray(0))))
        assertTrue(received.isEmpty())
        var settled = 0
        service.settleHooks.add { reason, _ -> if (reason == "heartbeat") settled++ }
        service.settle("heartbeat")
        assertEquals(1, settled)
    }
}
