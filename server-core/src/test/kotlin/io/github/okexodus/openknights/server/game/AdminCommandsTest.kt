package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.protocol.WireReader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AdminCommandsTest {
    @Test fun `names and numeric arguments accept quotes and leading whitespace stays private`() {
        val command = AdminCommands.parse("  /NEWCHARACTER \"A New Hero\" female Talia".toByteArray())
        assertEquals("newcharacter", command.name)
        assertEquals(listOf("A New Hero", "female", "Talia"), command.args)
        assertEquals(listOf("123", "10"), AdminCommands.parse("/additem \"123\" \"10\"".toByteArray()).args)
        assertTrue(Chat.isCommand(" \t/unknown command".toByteArray()))
        assertFalse(Chat.isCommand("hello /addgold".toByteArray()))
    }

    @Test fun `malformed commands are rejected rather than becoming chat`() {
        for (text in listOf("/unknown", "/newcharacter \"unclosed", "/help\n/addgold 1", "/help\u0000")) {
            assertTrue(AdminCommands.isCommand(text.toByteArray()))
            assertThrows(IllegalArgumentException::class.java) { AdminCommands.parse(text.toByteArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) { AdminCommands.parse(byteArrayOf(47, -1)) }
        assertThrows(IllegalArgumentException::class.java) { AdminCommands.parse(("/help " + "x".repeat(1024)).toByteArray()) }
    }

    @Test fun `help and replies are visible private system lines on every channel`() {
        assertEquals(listOf("/addgold Quantity"), AdminCommands.help(listOf("/ADDGOLD")))
        assertThrows(IllegalArgumentException::class.java) { AdminCommands.help(listOf("missing")) }
        for (channel in 0L..4L) {
            val frame = AdminCommands.replies(channel, listOf("Done")).single()
            assertEquals(Chat.S_CHAT, frame.first)
            val reader = WireReader(frame.second)
            assertEquals(if (channel in 1L..3L) channel.toInt() else 1, reader.u8())
            assertEquals(0, reader.u8())
            assertEquals(0L, reader.u32())
            assertEquals("System", reader.cstring())
            assertEquals("Done", reader.cstring())
            assertEquals(1, reader.u8())
        }
    }
}
