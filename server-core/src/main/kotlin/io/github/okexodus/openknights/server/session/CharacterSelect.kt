package io.github.okexodus.openknights.server.session

import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.protocol.PlayerState
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.AccountRegistry
import io.github.okexodus.openknights.server.store.StateStore
import io.github.okexodus.openknights.server.store.WorldDirectory

/**
 * The in-game character list (`character_select.py`): the entry screen's server list (login-server S7720) lists the
 * signed-in account's world characters plus "Create a character". Choosing a character routes (C7715 → S7714) its own
 * wire id; choosing the create row routes a short-lived creation ticket. Row ids come from the client's
 * `server_list.csv` rows labelled "Click to log in".
 */
class CharacterSelect(
    val offers: List<Long>,
    rowIds: List<Int>? = null,
    val announcement: String = "",
    val createRowLabel: String = CREATE_ROW_LABEL,
    clock: () -> Long = { io.github.okexodus.openknights.exact.Now.epoch() },
) {
    companion object {
        const val CLICK_TO_LOG_IN = "880010000"
        const val CREATE_ROW_ID = 999
        const val ROW_ID_BASE = 1000
        const val CREATE_ROW_LABEL = "+ Create a character"
        const val TICKET_FIRST = 0x7F000001L
        const val TICKET_TTL = 1800
        const val WIRE_ID_MAX = 0x7FFFFFFFL
        const val BADGE_NONE = 0
        const val BADGE_NEW = 1
        const val BADGE_HOT = 2
        const val ROLE_LEVEL = 3
        val STARTER_LABEL = mapOf(40001001L to "Warrior", 40004001L to "Mage", 40007001L to "Hunter")
        /** The leader's class by base id (class change keeps the tier): 40001–40003 Warrior, 40004–40006 Mage, 40007–40009 Hunter. */
        val CLASS_OF_BASE: Map<Long, String> = (40001L..40003L).associateWith { "Warrior" } + (40004L..40006L).associateWith { "Mage" } +
            (40007L..40009L).associateWith { "Hunter" }
        /** S18 login_mode 1 (5 bytes): the native creation dialog. */
        val MODE_1: ByteArray = byteArrayOf(0, 1, 0, 0, 0)

        /** Sorted `server_list` ids whose label is "Click to log in" with no secondary text. */
        fun labeledRowIds(tables: GameTables): List<Int> =
            tables.table("server_list").rows
                .filter { it.field("102") == CLICK_TO_LOG_IN && it.field("103").isNullOrEmpty() }
                .map { it.field("101")!!.toInt() }.sorted().filter { it in 1 until 0xFFFF }

        /** S7720: u16 count; per row u16 id, u32 0, cstring name, u8 1, u8 badge; cstring announcement; u16 last id. */
        fun serverList(rows: List<Row>, lastId: Int, announcement: String): ByteArray {
            val w = WireWriter().u16(rows.size)
            for (row in rows) {
                val raw = row.name.toByteArray(Charsets.UTF_8)
                require(!raw.contains(0.toByte()) && row.id in 1 until 0xFFFF && row.badge in 0..2) { "Invalid server-list row" }
                w.u16(row.id).u32(0).cstring(raw).u8(1).u8(row.badge)
            }
            w.cstring(announcement.toByteArray(Charsets.UTF_8)).u16(lastId)
            return w.bytes()
        }

        fun rowLabel(member: JObj, level: Long, leaderTemplate: Long?): String {
            val kind = if (member.strOrNull("kind") != "fresh") "Saved"
            else CLASS_OF_BASE[(leaderTemplate ?: 0) / 1000] ?: STARTER_LABEL[(member["starter"] as? io.github.okexodus.openknights.exact.JInt)?.value?.toLong()] ?: "Saved"
            return "${member.str("name")}  Lv$level  $kind"
        }

        /** A mode-2 creation reply: the three offered starters. */
        fun mode2Payload(offers: List<Long>): ByteArray = WireWriter().u8(0).u32(2).also { w -> offers.forEach { w.u32(it) } }.bytes()
    }

    class Row(val id: Int, val name: String, val badge: Int)

    val tickets = Tickets(clock)
    val createRowId: Int
    val characterRowIds: List<Int>?

    init {
        require(offers.size == 3) { "Exactly the three captured starter offers are expected" }
        require(!announcement.contains(0.toChar()) && !createRowLabel.contains(0.toChar()) && createRowLabel.isNotEmpty()) { "Invalid announcement or create-row label" }
        if (!rowIds.isNullOrEmpty()) { createRowId = rowIds.last(); characterRowIds = rowIds.dropLast(1) }
        else { createRowId = CREATE_ROW_ID; characterRowIds = null }
    }

    /** The list row id of the world's row number (null past the labelled ids). */
    fun characterRowId(worldRowNumber: Long): Int? {
        val ids = characterRowIds ?: return (ROW_ID_BASE + worldRowNumber).toInt()
        return if (worldRowNumber in 1..ids.size.toLong()) ids[(worldRowNumber - 1).toInt()] else null
    }

    /** Active world characters owned by the account: (row id, member, current save). */
    fun ownedRows(world: WorldDirectory?, registry: AccountRegistry, accountId: String): List<Triple<Int, JObj, StateStore.Current>> {
        if (world == null) return emptyList()
        val numbers = world.rowNumbers()
        val out = ArrayList<Triple<Int, JObj, StateStore.Current>>()
        for (member in world.characters()) {
            if (member.str("account_id") != accountId) continue
            val rowId = characterRowId(numbers.getValue(member.str("entry_id"))) ?: continue
            if (rowId >= 0xFFFF) continue
            val current = registry.resolveStateStore(member.str("character_id"), accountId).read()
            out.add(Triple(rowId, member, current))
        }
        return out
    }

    fun level(current: StateStore.Current): Long = PlayerState.role(current.state, ROLE_LEVEL).long("bits")

    fun leaderTemplate(current: StateStore.Current): Long? {
        for (hero in current.state.arr("heroes")) for (field in (hero as io.github.okexodus.openknights.exact.JArr)) {
            val f = field as JObj
            if (f.long("id") == 1L) {
                val bits = ((f.obj("value")["bits"]) as? io.github.okexodus.openknights.exact.JInt)?.value?.toLong() ?: 0
                if (bits / 1000 in CLASS_OF_BASE) return bits
            }
        }
        return null
    }
}

/** In-memory creation tickets (service lifetime); a ticket belongs to one session. */
class Tickets(private val clock: () -> Long) {
    class Ticket(val sessionId: String, val accountId: String, var characterId: String?, val expires: Long, var name: String? = null, var gender: Int? = null)

    private val tickets = LinkedHashMap<Long, Ticket>()
    private var next = CharacterSelect.TICKET_FIRST

    @Synchronized
    fun issue(sessionId: String, accountId: String): Long {
        val now = clock()
        for ((wire, ticket) in tickets) if (ticket.sessionId == sessionId && ticket.characterId == null && now < ticket.expires) return wire
        val wire = next
        require(wire <= CharacterSelect.WIRE_ID_MAX) { "No creation tickets left in this service lifetime" }
        next++
        tickets[wire] = Ticket(sessionId, accountId, null, now + CharacterSelect.TICKET_TTL)
        return wire
    }

    @Synchronized
    fun get(wire: Long, sessionId: String): Ticket? {
        val ticket = tickets[wire] ?: return null
        return if (ticket.sessionId != sessionId || clock() >= ticket.expires) null else ticket
    }
}
