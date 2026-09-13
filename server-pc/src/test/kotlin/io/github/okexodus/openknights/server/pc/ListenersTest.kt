package io.github.okexodus.openknights.server.pc

import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.gamedata.TableSource
import io.github.okexodus.openknights.protocol.FrameDecoder
import io.github.okexodus.openknights.protocol.Frames
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.session.CharacterSelect
import io.github.okexodus.openknights.server.session.Service
import io.github.okexodus.openknights.server.session.ServiceLog
import io.github.okexodus.openknights.server.store.AccountRegistry
import io.github.okexodus.openknights.server.store.DataRoot
import io.github.okexodus.openknights.server.store.JdbcSqlDriver
import io.github.okexodus.openknights.server.store.LocalAuth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Path

/** The three listeners over real loopback sockets: the sign-in page and API, then the login hop. */
class ListenersTest {
    @TempDir
    lateinit var tmp: Path

    private fun service(): Service {
        val driver = JdbcSqlDriver()
        val root = DataRoot(tmp.resolve("root"), driver).open()
        val generation = root.unbornStaging()
        Service.ownerRegistry(generation, driver)
        val auth = LocalAuth(AccountRegistry(generation.resolve(DataRoot.REGISTRY), driver, strictPaths = true)).also { it.deviceOwner = Service.OWNER }
        val tables = GameTables(object : TableSource {
            override fun names() = listOf("server_list.csv")
            override fun raw(name: String) = "101,102,103\n10001,880010000,\n10002,880010000,\n".toByteArray()
        })
        val select = CharacterSelect(listOf(1, 2, 3), CharacterSelect.labeledRowIds(tables), "Hello", "+ Create a character")
        return Service(driver, ServiceLog(echo = false), DeviceClock(null), tables, null, auth, null, select, root, generation)
    }

    private fun http(port: Int, request: String): Pair<Int, String> {
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.getOutputStream().write(request.toByteArray())
            val text = s.getInputStream().readAllBytes().toString(Charsets.UTF_8)
            val status = text.substringAfter(' ').substringBefore(' ').toInt()
            return status to text.substringAfter("\r\n\r\n")
        }
    }

    @Test
    fun `sign in over HTTP, then the login hop over TCP`() {
        val service = service()
        Listeners(service, loginPort = 0, gamePort = 0, authPort = 0).start().use { listeners ->
            val port = listeners.boundAuthPort
            val (pageStatus, page) = http(port, "GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n")
            assertEquals(200, pageStatus)
            assertTrue(page.contains("OpenKnights"))
            assertEquals(400, http(port, "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n").first)
            val (status, body) = http(port, "POST /api/device HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}")
            assertEquals(200, status)
            val reply = Json.loads(body).asObj
            assertEquals(listOf("token", "ingame_select", "expires_at_utc", "device"), reply.keys.toList())
            val token = reply.str("token")
            val (recharge, _) = http(port, "POST /api/recharge HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${("{\"token\": \"$token\"}").length}\r\n\r\n{\"token\": \"$token\"}")
            assertEquals(409, recharge)

            Socket(InetAddress.getLoopbackAddress(), listeners.boundLoginPort).use { s ->
                val decoder = FrameDecoder()
                fun exchange(opcode: Int, payload: ByteArray): Pair<Int, ByteArray> {
                    s.getOutputStream().write(Frames.encode(opcode, payload))
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = s.getInputStream().read(buffer)
                        val frames = decoder.feed(buffer.copyOf(n))
                        if (frames.isNotEmpty()) return frames[0].opcode to frames[0].payload
                    }
                }
                val signIn = WireWriter().cstring("d".toByteArray()).cstring(token.toByteArray()).u16(1)
                    .cstring(ByteArray(0)).cstring(ByteArray(0)).cstring(ByteArray(0)).cstring(ByteArray(0)).bytes()
                assertEquals(7680, exchange(7683, signIn).first)
                val list = exchange(7713, "1".toByteArray() + byteArrayOf(0) + "2".toByteArray() + byteArrayOf(0))
                assertEquals(7720, list.first)
                assertEquals(CharacterSelect.serverList(listOf(CharacterSelect.Row(10002, "+ Create a character", 1)), 10002, "Hello").toList(),
                    list.second.toList())
            }
        }
    }
}
