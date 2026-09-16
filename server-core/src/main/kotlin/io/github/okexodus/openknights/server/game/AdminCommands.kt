package io.github.okexodus.openknights.server.game

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Private, local-player command syntax. Never dispatch these through a shared chat or bot event bus. */
object AdminCommands {
    data class Command(val name: String, val args: List<String>)

    val usage = linkedMapOf(
        "additem" to "/additem ItemID Quantity", "addhero" to "/addhero HeroID",
        "addgold" to "/addgold Quantity", "adddiamonds" to "/adddiamonds Quantity",
        "setlevel" to "/setlevel Level", "setvip" to "/setvip Level", "completequest" to "/completequest [QuestID]",
        "setcastle" to "/setcastle Level", "setwarehouse" to "/setwarehouse Level",
        "levelhero" to "/levelhero Slot Level", "evolvehero" to "/evolvehero Slot",
        "createguild" to "/createguild \"Name\"", "leaveguild" to "/leaveguild",
        "disbandguild" to "/disbandguild [confirm]",
        "newcharacter" to "/newcharacter \"Name\" male|female Jansen|Rhee|Talia",
        "formation" to "/formation", "help" to "/help [Command]",
    )

    fun isCommand(raw: ByteArray): Boolean = raw.dropWhile { it == 32.toByte() || it in 9.toByte()..13.toByte() }
        .firstOrNull() == '/'.code.toByte()

    fun parse(raw: ByteArray): Command {
        require(raw.size <= 1024) { "Command is too long." }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString().trim()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw Acquisition.Rejected("Command must be valid text.")
        }
        require(text.startsWith('/')) { "Commands start with /." }
        require(text.none { it == '\u0000' || it == '\n' || it == '\r' }) { "Enter one command at a time." }
        val tokens = ArrayList<String>()
        val token = StringBuilder()
        var quoted = false
        var started = false
        for (ch in text) {
            when {
                ch == '"' || ch == '\u201c' || ch == '\u201d' -> { quoted = !quoted; started = true }
                ch.isWhitespace() && !quoted -> if (started) {
                    tokens.add(token.toString()); token.setLength(0); started = false
                }
                else -> { token.append(ch); started = true }
            }
        }
        require(!quoted) { "Close the quotation mark around the name." }
        if (started) tokens.add(token.toString())
        val name = tokens.first().removePrefix("/").lowercase(Locale.ROOT)
        require(name in usage) { "Unknown command. Use /help." }
        return Command(name, tokens.drop(1))
    }

    fun help(args: List<String>): List<String> {
        require(args.size <= 1) { usage.getValue("help") }
        if (args.isEmpty()) return listOf("Commands affect this character. Use /help Command.") + usage.values
        val name = args.single().removePrefix("/").lowercase(Locale.ROOT)
        return listOf(usage[name] ?: throw Acquisition.Rejected("Unknown command. Use /help."))
    }

    /** Replies use the visible channel but are delivered only on the issuing session. */
    fun replies(channel: Long, lines: List<String>): List<Frame> = lines.map {
        Chat.S_CHAT to Chat.linePayload(if (channel in 1L..3L) channel else Chat.WORLD, false, 0,
            Chat.SYSTEM_NAME, it.toByteArray(Charsets.UTF_8), gm = true)
    }
}
