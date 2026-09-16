package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.PyText
import io.github.okexodus.openknights.exact.Utf8Lenient
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.game.WorldParticipants.Participant

/**
 * In-game chat (`chat.py`): world, guild and private channels; the hook for the future `/commands`. C449 `u8 channel,
 * cstr target, cstr text` → S480 `u8 channel, u8 outgoing, u32 sender id, cstr sender, cstr text, u8 gm` to every
 * online recipient (channel 0 system, 1 world, 2 private, 3 guild; each client list keeps the last 20 lines). A line
 * starting with "/" never reaches a channel or the history; only the sender gets a system line with the GM flag.
 */
object Chat {
    const val C_CHAT = 449
    const val S_CHAT = 480
    const val S_BROADCAST = 768
    const val SYSTEM = 0L
    const val WORLD = 1L
    const val PRIVATE = 2L
    const val GUILD = 3L
    /** The client keeps 20 lines per list. */
    const val HISTORY = 20
    /** Property: 60 (GetMaxChatLength). */
    const val MAX_TEXT = 702
    const val ERR_TOO_LONG = 19000
    const val ERR_NO_PLAYER = 14000
    const val ERR_SELF = 13000
    val SYSTEM_NAME: ByteArray = "System".toByteArray(Charsets.US_ASCII)

    /** A participant's name as the name lookups key it: `name_raw.decode("utf-8", "replace").casefold()`. */
    fun nameKey(nameRaw: ByteArray): String = PyText.casefold(Utf8Lenient.decodeReplace(nameRaw))

    /** A decoded C449 (`decode_chat`'s dictionary). */
    class Request(val channel: Long, val target: ByteArray, val text: ByteArray)

    /** C449 `u8 channel, cstr target, cstr text`. */
    fun decodeChat(payload: ByteArray): Request {
        if (payload.size < 3 || payload.last() != 0.toByte()) throw Acquisition.Rejected("C449 is u8 channel, cstr target, cstr text")
        val channel = (payload[0].toInt() and 0xFF).toLong()
        val parts = Mail.splitBytes(payload.copyOfRange(1, payload.size))
        if (parts.size != 3 || parts.last().isNotEmpty()) throw Acquisition.Rejected("C449 is u8 channel, cstr target, cstr text")
        return Request(channel, parts[0], parts[1])
    }

    fun linePayload(channel: Long, outgoing: Boolean, senderId: Long, senderRaw: ByteArray, textRaw: ByteArray, gm: Boolean = false): ByteArray =
        WireWriter().u8(channel.toInt()).u8(if (outgoing) 1 else 0).u32(senderId).raw(senderRaw).raw(byteArrayOf(0))
            .raw(textRaw).raw(byteArrayOf(0)).u8(if (gm) 1 else 0).bytes()

    fun storedLine(channel: Long, senderId: Long, senderRaw: ByteArray, textRaw: ByteArray, now: Long, target: JObj? = null, gm: Boolean = false): JObj =
        jobj("channel" to channel, "sender" to senderId, "name_hex" to senderRaw.toHexString(), "text_hex" to textRaw.toHexString(),
            "at" to now, "target" to target, "gm" to gm)

    /** S480 of a stored line for a viewer (private lines: outgoing from the viewer's side shows the partner). */
    fun replay(line: JObj, viewer: Long): Frame {
        val gm = Py.truthy(line["gm"])
        if (line.long("channel") == PRIVATE && line.long("sender") == viewer) {
            val target = line.obj("target")
            return S_CHAT to linePayload(PRIVATE, true, target.long("id"), target.str("name_hex").hexBytes(), line.str("text_hex").hexBytes(), gm)
        }
        return S_CHAT to linePayload(line.long("channel"), false, line.long("sender"), line.str("name_hex").hexBytes(), line.str("text_hex").hexBytes(), gm)
    }

    /** `_append`: the line at the end, the last [HISTORY] kept. */
    private fun append(lines: JArr, line: JObj) {
        lines.add(line)
        while (lines.size > HISTORY) lines.removeAt(0)
    }

    fun isCommand(textRaw: ByteArray): Boolean = AdminCommands.isCommand(textRaw)

    /** The `/command` hook (not implemented yet): the reply the sender sees. */
    fun commandReply(textRaw: ByteArray): ByteArray {
        val space = textRaw.indexOf(' '.code.toByte())
        val name = Utf8Lenient.decodeReplace(if (space < 0) textRaw else textRaw.copyOfRange(0, space))
        return "$name: admin commands are not available yet".toByteArray(Charsets.UTF_8)
    }

    /** `bytes.strip()`: the ASCII whitespace (space, \t, \n, \r, \v, \f) removed at both ends. */
    private fun stripBytes(raw: ByteArray): ByteArray {
        fun space(b: Byte) = b == 0x20.toByte() || b in 0x09.toByte()..0x0d.toByte()
        var start = 0
        var end = raw.size
        while (start < end && space(raw[start])) start++
        while (end > start && space(raw[end - 1])) end--
        return raw.copyOfRange(start, end)
    }

    /** One delivery of [send]: to the sender (`recipient` null, not [world]), to every online player ([world]) or to one role. */
    class Delivery(val recipient: Long?, val frame: Frame, val world: Boolean = false)

    /**
     * One chat line (`send`). Returns (deliveries, stored line or null); the caller resolves the recipients to open
     * sessions (world: every online player, guild: the guild's members, private: the target and the sender's echo) and
     * applies the blacklists.
     */
    fun send(chatDoc: JObj, request: Request, sender: Participant?, guildId: Long, guildMembers: List<Long>,
             peopleByName: Map<String, Participant>, inputs: DailyInputs, now: Long): Pair<List<Delivery>, JObj?> {
        val text = request.text
        if (stripBytes(text).isEmpty()) throw Acquisition.Rejected("Empty message", ERR_TOO_LONG)
        if (PyText.length(Utf8Lenient.decodeReplace(text)) > inputs.prop(MAX_TEXT, 60)) throw Acquisition.Rejected("Contents are too long", ERR_TOO_LONG)
        if (isCommand(text)) {
            // answered on the channel it was typed in (a channel-0 reply stays invisible), labeled "GM"
            val channel = if (request.channel in listOf(WORLD, GUILD, PRIVATE)) request.channel else SYSTEM
            return listOf(Delivery(null, S_CHAT to linePayload(channel, false, 0, SYSTEM_NAME, commandReply(text), gm = true))) to null
        }
        val me = sender!!.participantId
        val name = sender.nameRaw
        val channel = request.channel
        if (channel == WORLD) {
            val line = storedLine(WORLD, me, name, text, now)
            append(chatDoc.arr("world"), line)
            return listOf(Delivery(null, S_CHAT to linePayload(WORLD, false, me, name, text), world = true)) to line
        }
        if (channel == GUILD) {
            if (guildId == 0L) throw Acquisition.Rejected("Not in a Guild yet", 52002)
            val line = storedLine(GUILD, me, name, text, now)
            val guilds = chatDoc.obj("guild")
            append((guilds[guildId.toString()] as? JArr) ?: JArr().also { guilds[guildId.toString()] = it }, line)
            val frame = S_CHAT to linePayload(GUILD, false, me, name, text)
            return guildMembers.map { Delivery(it, frame) } to line
        }
        if (channel == PRIVATE) {
            val target = peopleByName[nameKey(request.target)]
                ?: throw Acquisition.Rejected("Can't find the player", ERR_NO_PLAYER)
            if (target.participantId == me) throw Acquisition.Rejected("You cannot send this to yourself.", ERR_SELF)
            val line = storedLine(PRIVATE, me, name, text, now, target = jobj("id" to target.participantId, "name_hex" to target.nameRaw.toHexString()))
            val lines = chatDoc.obj("private")
            append((lines[target.participantId.toString()] as? JArr) ?: JArr().also { lines[target.participantId.toString()] = it }, line)
            return listOf(Delivery(target.participantId, S_CHAT to linePayload(PRIVATE, false, me, name, text)),
                Delivery(null, S_CHAT to linePayload(PRIVATE, true, target.participantId, target.nameRaw, text))) to line
        }
        throw Acquisition.Rejected("Unknown chat channel", 102)
    }

    /**
     * Lines replayed after the mail list at login: the world history, the guild's history, the private lines to the
     * viewer. A line whose sender the viewer has blocked ([blocked] = the viewer's mail blacklist, name hexes — the list
     * the live push checks) is skipped on every channel; the viewer's own lines always replay.
     */
    fun loginHistory(chatDoc: JObj, viewer: Long, guildId: Long, blocked: Collection<String> = emptyList()): List<Frame> {
        val names = blocked.toSet()
        fun shown(line: JObj) = line.long("sender") == viewer || (line["name_hex"] as? io.github.okexodus.openknights.exact.JStr)?.value !in names
        val frames = ArrayList<Frame>()
        chatDoc.arr("world").forEach { if (shown(it as JObj)) frames.add(replay(it, viewer)) }
        if (guildId != 0L) ((chatDoc.obj("guild")[guildId.toString()] as? JArr) ?: JArr()).forEach { if (shown(it as JObj)) frames.add(replay(it, viewer)) }
        ((chatDoc.obj("private")[viewer.toString()] as? JArr) ?: JArr()).forEach { if (shown(it as JObj)) frames.add(replay(it, viewer)) }
        return frames
    }
}
