package io.github.okexodus.openknights.server.session

import io.github.okexodus.openknights.protocol.PlayerState
import io.github.okexodus.openknights.protocol.ProtocolException
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.AuthenticationRejected
import io.github.okexodus.openknights.server.store.StateStore

/** Opcode 6: an int32 code; a nonzero code ends the client's waiting layer and shows text 8000000 + code. */
object TransactionPackets {
    const val INVALID_DATA = 102

    fun errorPayload(code: Int): ByteArray = WireWriter().i32(code).bytes()
}

/**
 * The client's game-login query set (`Session.QUERY_SEQUENCE`): each known query is accepted once, in any order;
 * initialisation is complete when every non-optional query has arrived, or — the client's own reconnect after a
 * dropped connection — every query except the [RECONNECT_OMITTED] four when none of those four came.
 */
class QuerySet {
    companion object {
        val QUERY_SEQUENCE = listOf(257, 193, 453, 163, 609, 2145, 2241, 2209, 2369, 1089, 3905,
            3841, 239, 3127, 1155, 353, 1157, 3625, 1761, 1153, 2273, 3073)
        /** RequestExerciseInfoList: absent from fresh cold logins (the client skips it without that feature). */
        val OPTIONAL_QUERIES = setOf(1761)
        val RECONNECT_OMITTED = setOf(193, 1089, 239, 1153)
    }

    val seen = LinkedHashSet<Int>()
    var complete = false
        private set
    var viaReconnectSet = false
        private set

    /** Accept one query; true when it completes the set. A repeated or unknown opcode is not a query. */
    fun accept(opcode: Int, payload: ByteArray): Boolean {
        if (complete || opcode !in QUERY_SEQUENCE || opcode in seen) return false
        if ((opcode == 1761 && payload.size != 2) || (opcode != 1761 && payload.isNotEmpty())) {
            throw ProtocolException("Unexpected payload for initial query $opcode")
        }
        seen.add(opcode)
        val required = QUERY_SEQUENCE.toSet() - OPTIONAL_QUERIES
        val reconnect = required - RECONNECT_OMITTED
        if (!seen.containsAll(required) && !(seen.containsAll(reconnect) && seen.intersect(RECONNECT_OMITTED).isEmpty())) return false
        viaReconnectSet = !seen.containsAll(required)
        complete = true
        return true
    }

    fun isQuery(opcode: Int) = opcode in QUERY_SEQUENCE
}

/**
 * One client connection (`snapshot_server.Session`, release mode). The login service answers the sign-in (C7683 →
 * S7680), the in-game character list (C7713 → S7720) and the choice of a row (C7715 → S7714); the game service
 * authenticates C3 (session token, wire identity, ownership) and answers the heartbeat (C7 → S14 + S8).
 *
 * Entering the game (the startup of a selected character), character creation and every game system come with the
 * port of the game systems: until then such a request is answered with S6 "Invalid Data" and logged as
 * `not_implemented` — nothing is invented and nothing changes.
 */
class Session(val service: Service, val kind: String, private val gamePort: Int) {
    var loggedIn = false
        private set
    var closed = false
        private set
    var token: String? = null
        private set
    var characterId: String? = null
        private set
    var stateStore: StateStore? = null
        private set
    var wireAccountId: Long? = null
        private set
    var sessionId: String? = null
        private set
    var accountId: String? = null
        private set
    /** The wire role id of the entered character (pushes are addressed by it). */
    var role: Long? = null
        private set
    val queries = QuerySet()

    private fun log(event: String, vararg fields: Pair<String, Any?>) = service.log.log(event, *fields)

    fun handle(opcode: Int, payload: ByteArray): List<Pair<Int, ByteArray>> {
        if (closed) return emptyList()
        return try {
            authenticatedHandle(opcode, payload)
        } catch (e: IllegalArgumentException) {
            closed = true
            loggedIn = false
            stateStore = null
            val detail = if (e is AuthenticationRejected) emptyArray() else arrayOf("reason" to (e.message ?: "").take(160))
            log("authentication_rejected", "service" to kind, "opcode" to opcode, "error" to errorName(e), *detail)
            if (kind == "login") listOf(7680 to byteArrayOf(1)) else listOf(6 to TransactionPackets.errorPayload(TransactionPackets.INVALID_DATA))
        }
    }

    private fun errorName(e: Throwable) = when (e) {
        is AuthenticationRejected -> "AuthenticationRejected"
        is ProtocolException -> "ValueError"
        else -> "ValueError"
    }

    private fun notImplemented(feature: String, opcode: Int, payloadBytes: Int, close: Boolean): List<Pair<Int, ByteArray>> {
        log("not_implemented", "service" to kind, "opcode" to opcode, "feature" to feature, "payload_bytes" to payloadBytes,
            "character_id" to characterId)
        if (close) closed = true
        return listOf(6 to TransactionPackets.errorPayload(TransactionPackets.INVALID_DATA))
    }

    private fun authenticatedHandle(opcode: Int, payload: ByteArray): List<Pair<Int, ByteArray>> {
        if (!loggedIn) {
            val reader = WireReader(payload)
            val presented: String
            var wireId = 0L
            if (kind == "login" && opcode == 7683) {
                reader.cstringBytes()                 // device identity, not authorization
                presented = reader.cstring()
                reader.u16()
                repeat(4) { reader.cstringBytes() }
            } else if (kind == "game" && opcode == 3) {
                wireId = reader.u32()
                reader.cstringBytes(); reader.cstringBytes()   // OS spelling and model
                reader.u8()                                    // reconnect marker
                presented = reader.cstring()
                reader.u8()                                    // VIP hint; never authority
            } else {
                throw AuthenticationRejected("Authenticate first")
            }
            if (reader.offset != payload.size) throw AuthenticationRejected("Malformed login")
            val selected = service.auth.authenticate(presented)
            sessionId = selected.sessionId
            accountId = selected.accountId
            if (kind == "login" && selected.characterId == null) {
                // In-game character select: the entry screen chooses.
                token = presented
                characterId = null
                stateStore = null
                loggedIn = true
                return listOf(7680 to byteArrayOf(0))
            }
            var id = selected.characterId
            if (kind == "game" && id == null) {
                val ticket = service.select.tickets.get(wireId, selected.sessionId)
                if (ticket != null && ticket.characterId == null) {
                    token = presented
                    log("character_create_started", "account_id" to selected.accountId, "ticket" to wireId)
                    return notImplemented("character creation (the native Create / Select-a-Hero screens)", opcode, payload.size, close = true)
                }
                id = if (ticket != null) ticket.characterId else {
                    val member = service.world?.characters()?.firstOrNull {
                        it.long("wire_account_id") == wireId && it.str("account_id") == selected.accountId
                    } ?: throw AuthenticationRejected("Invalid wire identity")
                    member.str("character_id")
                }
            }
            val store = service.auth.resolveCharacter(presented, id!!)
            val current = store.read()
            val expectedWire = current.characterProfile?.obj("document")?.long("wire_account_id") ?: service.capturedWireAccountId
            if (kind == "game" && wireId != expectedWire) throw AuthenticationRejected("Invalid wire identity")
            stateStore = store
            wireAccountId = expectedWire
            token = presented
            characterId = id
            loggedIn = true
            if (kind == "login") return listOf(7680 to byteArrayOf(0))
            role = PlayerState.role(current.state, 0).long("bits")
            return notImplemented("entering the game (the startup of the selected character)", opcode, payload.size, close = true)
        }
        // Every later request revalidates the session (expiry, revocation, selection, ownership, save integrity).
        if (characterId == null) service.auth.authenticate(token!!)
        else stateStore = service.auth.resolveCharacter(token!!, characterId!!)
        return if (kind == "login") loginRequest(opcode, payload) else gameRequest(opcode, payload)
    }

    /** (rows, last id, row targets) for this login session's in-game character list. */
    private fun selectRows(): Triple<List<CharacterSelect.Row>, Int, Map<Int, io.github.okexodus.openknights.exact.JObj?>> {
        val select = service.select
        val rows = ArrayList<CharacterSelect.Row>()
        val targets = LinkedHashMap<Int, io.github.okexodus.openknights.exact.JObj?>()
        // The client's "All" tab shows the wire order reversed, so the create row goes first to appear last on screen.
        if (characterId == null && service.creationEnabled) {
            rows.add(CharacterSelect.Row(select.createRowId, select.createRowLabel, CharacterSelect.BADGE_NEW))
            targets[select.createRowId] = null
        }
        for ((rowId, member, current) in select.ownedRows(service.world, service.auth.registry, accountId!!)) {
            if (characterId != null && member.str("character_id") != characterId) continue
            rows.add(CharacterSelect.Row(rowId, CharacterSelect.rowLabel(member, select.level(current), select.leaderTemplate(current)), CharacterSelect.BADGE_NONE))
            targets[rowId] = member
        }
        val preferred = if (characterId == null) service.auth.preferred(token!!) else characterId
        val last = targets.entries.firstOrNull { it.value?.str("character_id") == preferred && preferred != null }?.key
            ?: targets.entries.firstOrNull { it.value != null }?.key ?: select.createRowId
        return Triple(rows, last, targets)
    }

    private fun loginRequest(opcode: Int, payload: ByteArray): List<Pair<Int, ByteArray>> {
        val reader = WireReader(payload)
        when (opcode) {
            7713 -> {
                reader.cstringBytes(); reader.cstringBytes()
                if (reader.offset != payload.size) throw AuthenticationRejected("Malformed server list request")
                val (rows, last, _) = selectRows()
                return listOf(7720 to CharacterSelect.serverList(rows, last, service.select.announcement))
            }
            7715 -> {
                val serverId = reader.u16()
                reader.cstringBytes()
                if (reader.offset != payload.size) throw AuthenticationRejected("Invalid server selection")
                val (_, _, targets) = selectRows()
                if (serverId !in targets) {
                    // A stale row (e.g. a deleted character): result 1 shows "The server does not exist".
                    log("stale_character_row", "account_id" to accountId, "row" to serverId)
                    return listOf(7714 to byteArrayOf(1))
                }
                val member = targets[serverId]
                val wire = member?.long("wire_account_id") ?: service.select.tickets.issue(sessionId!!, accountId!!)
                log("character_selected_in_game", "account_id" to accountId, "row" to serverId,
                    "character_id" to member?.str("character_id"), "create" to (member == null))
                // The two final strings are resource metadata, not session tokens.
                val w = WireWriter().u8(0).cstring("127.0.0.1".toByteArray()).u32(gamePort.toLong()).u32(wire).raw(byteArrayOf(0, 0))
                return listOf(7714 to w.bytes())
            }
        }
        throw AuthenticationRejected("Unexpected login request")
    }

    private fun gameRequest(opcode: Int, payload: ByteArray): List<Pair<Int, ByteArray>> {
        if (opcode == 3) {
            // The client resends C3 every 5 s until an S18 arrives; a duplicate on an authenticated connection is ignored.
            log("duplicate_game_login_ignored", "character_id" to characterId)
            return emptyList()
        }
        if (opcode == 7) {
            if (payload.isNotEmpty()) throw ProtocolException("Malformed heartbeat")
            service.settle("heartbeat")
            return listOf(14 to service.clock.s14(), 8 to ByteArray(0))
        }
        return notImplemented("game request", opcode, payload.size, close = false)
    }

    /** The connection ended (the reference logs the presence change of a game session here). */
    fun disconnected() {
        if (kind == "game" && characterId != null && queries.complete) log("social_logout", "character_id" to characterId)
    }
}
