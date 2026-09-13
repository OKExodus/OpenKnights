package io.github.okexodus.openknights.server.session

import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.protocol.PlayerSections
import io.github.okexodus.openknights.protocol.PlayerState
import io.github.okexodus.openknights.protocol.ProtocolException
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.game.Acquisition
import io.github.okexodus.openknights.server.game.Frame
import io.github.okexodus.openknights.server.game.FreshProfile
import io.github.okexodus.openknights.server.game.NotPorted
import io.github.okexodus.openknights.server.store.AuthenticationRejected
import io.github.okexodus.openknights.server.store.StateStore

/** Opcode 6: an int32 code; a nonzero code ends the client's waiting layer and shows text 8000000 + code. */
object TransactionPackets {
    const val INVALID_DATA = 102

    fun errorPayload(code: Int): ByteArray = WireWriter().i32(code).bytes()
}

/**
 * One client connection (`snapshot_server.Session`, release mode). The login service answers the sign-in (C7683 →
 * S7680), the in-game character list (C7713 → S7720) and the choice of a row (C7715 → S7714). The game service
 * authenticates C3 and then serves either the native creation (C289 name, C291 starter) or the selected character:
 * the login repairs, the startup burst, the initialization query set and the routes of the game systems.
 *
 * A part of the game systems that is not ported yet throws [NotPorted]: the request is answered with S6 "Invalid
 * Data" and logged as `not_implemented` — nothing is invented. Every "never fails a login" guard of the reference
 * lets [NotPorted] through.
 */
class Session(val service: Service, val kind: String, private val gamePort: Int) {
    companion object {
        val QUERY_SEQUENCE = listOf(257, 193, 453, 163, 609, 2145, 2241, 2209, 2369, 1089, 3905,
            3841, 239, 3127, 1155, 353, 1157, 3625, 1761, 1153, 2273, 3073)
        /** RequestExerciseInfoList: absent from fresh cold logins (the client skips it without that feature). */
        val OPTIONAL_QUERIES = setOf(1761)
        /** The client's own reconnect after a dropped game connection re-sends the query set without these four. */
        val RECONNECT_OMITTED = setOf(193, 1089, 239, 1153)
        /** The frames the stat model reads back (what this session served). */
        val STAT_FRAMES = setOf(548, 320, 2848, 2880, 3745, 2240)
    }

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
    /** Initialization is complete: every query of the set arrived (the completion batch was served). */
    var queriesSent = false
        private set
    /** The wire role id of the entered character (pushes are addressed by it); set by the social login frames. */
    var socialRole: Long? = null
        internal set
    /** S14 epoch the client runs on − service time. */
    var clockOffset: Long = 0
        private set
    /** The open creation ticket of the in-game select. */
    var creating: Tickets.Ticket? = null
        private set
    private var queryIndex = 0
    private var savedAfterQueries: List<Frame>? = null
    private var jewelryFrames: List<Frame>? = null
    private var trainingPage: Int? = null
    private val seenQueries = LinkedHashSet<Int>()
    val statFrames = LinkedHashMap<Int, ByteArray>()
    private var freshCharacter: Boolean? = null

    private fun log(event: String, vararg fields: Pair<String, Any?>) = service.log.log(event, *fields)

    private fun errorName(e: Throwable) = when (e) {
        is AuthenticationRejected -> "AuthenticationRejected"
        is FreshProfile.CreationRejected -> "CreationRejected"
        else -> "ValueError"
    }

    /** `f"{type(exc).__name__}: {exc}"` of the "never fails" guards. */
    private fun described(e: Exception) = "${e.javaClass.simpleName}: ${e.message}"

    fun handle(opcode: Int, payload: ByteArray): List<Frame> {
        if (closed) return emptyList()
        return try {
            authenticatedHandle(opcode, payload)
        } catch (e: NotPorted) {
            notImplemented(e.message ?: "", opcode, payload.size, close = !queriesSent)
        } catch (e: IllegalArgumentException) {
            closed = true
            loggedIn = false
            stateStore = null
            val detail = if (e is AuthenticationRejected) emptyArray() else arrayOf("reason" to (e.message ?: "").take(160))
            log("authentication_rejected", "service" to kind, "opcode" to opcode, "error" to errorName(e), *detail)
            if (kind == "login") listOf(7680 to byteArrayOf(1)) else listOf(6 to TransactionPackets.errorPayload(TransactionPackets.INVALID_DATA))
        }
    }

    private fun notImplemented(feature: String, opcode: Int, payloadBytes: Int, close: Boolean): List<Frame> {
        log("not_implemented", "service" to kind, "opcode" to opcode, "feature" to feature, "payload_bytes" to payloadBytes,
            "character_id" to characterId)
        if (close) closed = true
        return listOf(6 to TransactionPackets.errorPayload(TransactionPackets.INVALID_DATA))
    }

    /** Rethrow [NotPorted] from inside a "never fails" guard (it must reach [handle]). */
    private fun guard(e: Exception) { if (e is NotPorted) throw e }

    private fun authenticatedHandle(opcode: Int, payload: ByteArray): List<Frame> {
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
            var ticket: Tickets.Ticket? = null
            if (kind == "game" && id == null) {
                ticket = service.select.tickets.get(wireId, selected.sessionId)
                if (ticket != null && ticket.characterId == null) {
                    // Native creation: mode-1 status, then C289 / C291.
                    token = presented
                    creating = ticket
                    loggedIn = true
                    log("character_create_started", "account_id" to selected.accountId, "ticket" to wireId)
                    return listOf(18 to CharacterSelect.MODE_1, 2976 to ByteArray(0))
                }
                id = if (ticket != null) ticket.characterId else {
                    // no world before the first character: no identity but a creation ticket exists
                    val world = service.world ?: throw AuthenticationRejected("Invalid wire identity")
                    val member = world.characters().firstOrNull {
                        it.long("wire_account_id") == wireId && it.str("account_id") == selected.accountId
                    } ?: throw AuthenticationRejected("Invalid wire identity")
                    member.str("character_id")
                }
            }
            val store = service.auth.resolveCharacter(presented, id!!)
            var current = store.read()
            // Each world character has its own wire identity, checked only after token validation.
            val expectedWire = FreshProfile.wireAccountId(current, service.capturedWireAccountId)
            if (kind == "game" && wireId != expectedWire && ticket == null) throw AuthenticationRejected("Invalid wire identity")
            stateStore = store
            wireAccountId = expectedWire
            token = presented
            characterId = id
            loggedIn = true
            if (kind == "login") return listOf(7680 to byteArrayOf(0))
            current = vipDailyReset(current)
            current = dailyLoginRefresh(current)
            current = warehouseCapacityRepair(current)
            current = leaderDigitRepair(current)
            return enter(current, selected.accountId)
        }
        if (creating != null) return creationStep(opcode, payload)
        // Every later request revalidates the session (expiry, revocation, selection, ownership, save integrity).
        if (characterId == null) service.auth.authenticate(token!!)
        else stateStore = service.auth.resolveCharacter(token!!, characterId!!)
        return if (kind == "login") loginRequest(opcode, payload) else gameRequest(opcode, payload)
    }

    // --- entering the game ---------------------------------------------------------------------------------------

    /** The generated startup of the selected (or just created) character. */
    private fun enter(initialCurrent: StateStore.Current, accountId: String): List<Frame> {
        val (current, goalFrames) = goalsLogin(initialCurrent)
        val clockPayload = service.clock.s14()          // release: device epoch + GMT offset
        var initial = FreshProfile.freshStartup(current, characterId!!, clockPayload)
        savedAfterQueries = emptyList()
        initial = sweepStartup(initial, current) + goalFrames   // S3108 after S2976, before the queries (live)
        if (current.characterProfile != null) {
            // fresh (in-game created) characters: the guide-locked S18 at every login
            initial = initial.map { (op, data) -> op to (if (op == 18) guideLockedS18(data) else data) }
            initial = altTeamStartup(initial, current)
        }
        val sent = initial.firstOrNull { it.first == 14 && it.second.size == 8 }?.second
        clockOffset = if (sent != null) WireReader(sent).u32() - service.clock.now() else 0
        jewelryFrames = FreshProfile.jewelryList(current)
        log("character_loaded", "character_id" to characterId, "account_id" to accountId,
            "character_kind" to (if (current.characterProfile != null) "fresh" else "derived"),
            "revision" to current.revision, "init_items" to current.state.arr("items").size,
            "extra_items" to current.inventoryItems.size, "payload_sha256" to current.payloadSha256,
            "inventory_sha256" to current.inventorySha256, "bootstrap_policy" to "owned_state_audited",
            "secondary_team_sha256" to current.secondaryTeam?.str("payload_sha256"),
            "clock_policy" to "device_clock_high_water_v1",
            "god_skills_sha256" to current.godSkills?.str("document_sha256"))
        statFrames.clear()
        return rememberFrames(initial)
    }

    /** Every login serves login_mode 10000 (the client's locked "all guides done" state; the save keeps its own). */
    private fun guideLockedS18(data: ByteArray): ByteArray = try {
        CharacterSelect.creationSessionPayload(data)
    } catch (e: IllegalArgumentException) {      // never fail a login on this: serve the saved S18 unchanged
        log("guide_lock_skipped", "character_id" to characterId, "reason" to e.message)
        data
    }

    private fun rememberFrames(frames: List<Frame>): List<Frame> {
        for ((op, data) in frames) {
            if (op in STAT_FRAMES) statFrames[op] = data.copyOf()
            else if (op == 2884 && 2880 in statFrames && data.size == 4 && statFrames.getValue(2880).size >= 4) {
                val totems = statFrames.getValue(2880)
                statFrames[2880] = totems.copyOfRange(0, totems.size - 4) + data   // C2529 lineup change: S2880 ends with u32 lineup
            }
        }
        return frames
    }

    // --- the systems' login parts (each never fails a login; ported with their modules) ------------------------------

    /** The deployment preflight of the selected character: fresh characters are bound by their own profile. */
    private fun deploymentPolicy(): FreshProfile.DeploymentPolicy? {
        val store = stateStore ?: return null
        val profile = store.read().characterProfile ?: return null
        return FreshProfile.DeploymentPolicy(profile)
    }

    private fun isFreshCharacter(): Boolean {
        freshCharacter?.let { return it }
        val fresh = try { stateStore?.read()?.characterProfile != null } catch (e: Exception) { guard(e); false }
        freshCharacter = fresh
        return fresh
    }

    private fun sweepEnabled(): Boolean = stateStore != null && characterId != null && deploymentPolicy() != null

    /** Before the S18: a new day clears the VIP daily-reward flag, a new VIP level sets the buy counts. */
    private fun vipDailyReset(current: StateStore.Current): StateStore.Current {
        deploymentPolicy() ?: return current
        if (current.state.obj("subsystems")["vip"].let { it == null || it == io.github.okexodus.openknights.exact.JNull }) return current
        throw NotPorted("claims vip daily reset")
    }

    /** Before the S18: store what a new day starts (daily systems). */
    private fun dailyLoginRefresh(current: StateStore.Current): StateStore.Current {
        deploymentPolicy() ?: return current
        throw NotPorted("daily_routes login refresh")
    }

    /** Before the S18: bag capacities below the Warehouse limit are raised. */
    private fun warehouseCapacityRepair(current: StateStore.Current): StateStore.Current {
        deploymentPolicy() ?: return current
        throw NotPorted("sweep_features warehouse capacity repair")
    }

    /** Before the S18, in-game-created characters only: the leader's super-class digit repair. */
    private fun leaderDigitRepair(current: StateStore.Current): StateStore.Current {
        if (current.characterProfile == null) return current
        deploymentPolicy() ?: return current
        throw NotPorted("leader_repair login repair")
    }

    /** The Goals' login revision; returns (current, [S3108] or []). */
    private fun goalsLogin(current: StateStore.Current): Pair<StateStore.Current, List<Frame>> {
        if (!sweepEnabled()) return current to emptyList()
        throw NotPorted("goals login")
    }

    /** S2880 (totems) and S548 (album) in the startup burst. */
    private fun sweepStartup(packets: List<Frame>, current: StateStore.Current): List<Frame> {
        if (!sweepEnabled()) return packets
        throw NotPorted("sweep_features startup frames")
    }

    /** S3745 right after the S18 for an in-game-created character: its alternate team. */
    private fun altTeamStartup(initial: List<Frame>, current: StateStore.Current): List<Frame> {
        if (initial.any { it.first == 3745 }) return initial
        throw NotPorted("alt_team startup")
    }

    /** S2240 for the owned leader hero. */
    private fun leaderInfoReply(): List<Frame> {
        stateStore ?: return emptyList()
        throw NotPorted("leader info (S2240)")
    }

    private fun acquisitionLoginFrames(): List<Frame> {
        if (stateStore == null || characterId == null || deploymentPolicy() == null) return emptyList()
        throw NotPorted("acquisition login frames")
    }

    private fun sweepLoginFrames(): List<Frame> {
        if (!sweepEnabled()) return emptyList()
        throw NotPorted("sweep_features after-query frames")
    }

    private fun dailyQueryFrames(): List<Frame> {
        if (stateStore == null || characterId == null || deploymentPolicy() == null) return emptyList()
        throw NotPorted("daily_routes login query frames")
    }

    private fun socialLoginFrames(): List<Frame> {
        if (stateStore == null || service.world == null || deploymentPolicy() == null) return emptyList()
        throw NotPorted("social login frames")
    }

    // --- creation ------------------------------------------------------------------------------------------------

    /** C289 / C291 of the native creation. */
    private fun creationStep(opcode: Int, payload: ByteArray): List<Frame> {
        val ticket = creating!!
        service.auth.authenticate(token!!)
        val select = service.select
        if (opcode == 7) {
            if (payload.isNotEmpty()) throw IllegalArgumentException("Malformed heartbeat")
            return listOf(8 to ByteArray(0))
        }
        try {
            if (opcode == 289) {
                val request = CharacterSelect.decodeNameRequest(payload)
                val name = CharacterSelect.validateNewName(service.world, request.nameRaw, request.gender)
                ticket.name = name
                ticket.gender = request.gender.toInt()
                log("character_create_named", "account_id" to accountId, "gender" to request.gender, "inviter" to request.inviter)
                return listOf(18 to CharacterSelect.mode2Payload(select.offers), 2976 to ByteArray(0))
            }
            if (opcode == 291) {
                val starter = CharacterSelect.decodeStarterRequest(payload)
                if (ticket.name == null || starter !in select.offers) throw FreshProfile.CreationRejected("Choose one of the three offered starter heroes")
                val factory = service.characterFactory ?: throw NotPorted("character creation (no character factory)")
                val created = factory(accountId!!, ticket.name!!, ticket.gender!!, starter, "in-game-create")
                ticket.characterId = created.str("character_id")
                creating = null
                characterId = created.str("character_id")
                stateStore = service.auth.resolveCharacter(token!!, characterId!!)
                var current = stateStore!!.read()
                wireAccountId = FreshProfile.wireAccountId(current, service.capturedWireAccountId)
                log("character_created_in_game", "account_id" to accountId, "character_id" to characterId,
                    "starter" to starter, "wire_account_id" to wireAccountId)
                // The first session of a new character is a login like any other.
                current = vipDailyReset(current)
                current = dailyLoginRefresh(current)
                return enter(current, accountId!!)
            }
        } catch (e: FreshProfile.CreationRejected) {
            log("character_create_rejected", "account_id" to accountId, "opcode" to opcode, "reason" to e.message)
            return select.rejection(opcode, e)
        }
        log("unsupported_request_while_creating", "opcode" to opcode, "payload_bytes" to payload.size)
        return emptyList()
    }

    // --- the login service -----------------------------------------------------------------------------------------

    /** (rows, last id, row targets) for this login session's in-game character list. */
    private fun selectRows(): Triple<List<CharacterSelect.Row>, Int, Map<Int, JObj?>> {
        val select = service.select
        val rows = ArrayList<CharacterSelect.Row>()
        val targets = LinkedHashMap<Int, JObj?>()
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

    private fun loginRequest(opcode: Int, payload: ByteArray): List<Frame> {
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

    // --- the game service ------------------------------------------------------------------------------------------

    private fun gameRequest(opcode: Int, payload: ByteArray): List<Frame> = rememberFrames(gameRequestInner(opcode, payload))

    private fun group(opcode: Int, what: String): Nothing = throw NotPorted("$what (opcode $opcode)")

    private fun gameRequestInner(opcode: Int, payload: ByteArray): List<Frame> {
        if (opcode == 3) {
            // The client resends C3 every 5 s until an S18 arrives; a duplicate on an authenticated connection is ignored.
            log("duplicate_game_login_ignored", "character_id" to characterId)
            return emptyList()
        }
        if (opcode == 7) {
            if (payload.isNotEmpty()) throw IllegalArgumentException("Malformed heartbeat")
            service.settle("heartbeat")
            return listOf(14 to service.clock.s14(), 8 to ByteArray(0))
        }
        if ((opcode == 3777 || opcode == 3779) && isFreshCharacter()) group(opcode, "alternate team")
        if (opcode == 3779 || opcode == 3777) group(opcode, "secondary team (derived characters)")
        if (opcode == 69) group(opcode, "hero Fortify")
        if (opcode == 81) group(opcode, "gear Fortify")
        if (opcode == 71) group(opcode, "hero evolution")
        if (opcode == 2083) group(opcode, "leader evolution")
        if (opcode == 91 || opcode == 93 || opcode == 2641) group(opcode, "EXP-item Fortify")
        if (opcode == 2561) group(opcode, "leader class change")
        if (opcode == 1569) group(opcode, "rename")
        if (opcode == 1537) group(opcode, "gift code")
        if (opcode == 643 || opcode == 645) group(opcode, "roulette rank")
        if (opcode in setOf(2049, 2629, 2593, 2817)) group(opcode, "gear / jewelry evolve")
        if (opcode in Routes.FORMATION) group(opcode, "formation")
        if (opcode in setOf(3693, 3713, 3721, 2497, 3907)) group(opcode, "hero cards")
        if (opcode == 705) group(opcode, "rank list")
        if (opcode in Routes.ACQUISITION) group(opcode, "acquisition")
        if (opcode in Routes.DAILY && (queriesSent || opcode !in QUERY_SEQUENCE)) return dailyRoute(opcode, payload)
        if (opcode in Routes.SOCIAL && (queriesSent || opcode !in QUERY_SEQUENCE) &&
            (opcode !in QUERY_SEQUENCE || service.world != null)) return socialRoute(opcode, payload)
        if (opcode == 1127) {
            // The activity query: the same owned activity section as the S18.
            return try {
                if (!queriesSent || payload.isNotEmpty()) throw IllegalArgumentException("Activity query requires completed initialization and an empty request")
                val current = stateStore!!.read()
                listOf(1184 to PlayerSections.encodeSection("game_activities", current.state.obj("subsystems").obj("game_activities")))
            } catch (e: IllegalArgumentException) {
                log("rejected_activity_query", "character_id" to characterId, "reason" to e.message, "error_code" to 102)
                listOf(6 to TransactionPackets.errorPayload(102))
            }
        }
        if (opcode in Routes.SWEEP) group(opcode, "sweep feature")
        if (opcode in Routes.GOALS) group(opcode, "goals")
        if (opcode in Routes.CAMPAIGN) group(opcode, "campaign")
        if (opcode == 3809) group(opcode, "lineup view")
        Routes.IGNORED[opcode]?.let { feature ->
            if (stateStore != null) {
                log("ignored_request", "character_id" to characterId, "opcode" to opcode, "feature" to feature, "payload_bytes" to payload.size)
                return emptyList()
            }
        }
        Routes.UNIMPLEMENTED_WAITING[opcode]?.let { feature ->
            // The client waits for a reply to these; an S6 with a non-zero code ends its waiting layer. Nothing changes.
            log("unimplemented_feature", "character_id" to characterId, "opcode" to opcode, "feature" to feature, "payload_bytes" to payload.size)
            return listOf(6 to TransactionPackets.errorPayload(102))
        }
        val empty = Routes.EMPTY_MODE_REPLIES[opcode]
        if (empty != null && stateStore != null && queriesSent) {
            log("mode_empty_reply", "character_id" to characterId, "opcode" to opcode, "payload_bytes" to payload.size,
                "reply_opcodes" to empty.map { it.first })
            return empty.map { (op, data) -> op to data.copyOf() }
        }
        val mode = Routes.COMBAT_MODE[opcode]
        if (mode != null && stateStore != null && queriesSent) {
            log("combat_mode_refused", "character_id" to characterId, "opcode" to opcode, "feature" to mode,
                "payload_bytes" to payload.size, "error_code" to Routes.COMBAT_REFUSAL_CODE)
            return listOf(6 to TransactionPackets.errorPayload(Routes.COMBAT_REFUSAL_CODE))
        }
        val combat = Routes.COMBAT[opcode]
        if (combat != null && stateStore != null) {
            // Combat waits for its phase: every battle start is refused at once; nothing changes.
            log("combat_refused", "character_id" to characterId, "opcode" to opcode, "feature" to combat,
                "payload_bytes" to payload.size, "error_code" to Routes.COMBAT_REFUSAL_CODE)
            return listOf(6 to TransactionPackets.errorPayload(Routes.COMBAT_REFUSAL_CODE))
        }
        return legacyHandle(opcode, payload)
    }

    /** Daily requests: commit first, then the live reply order. Only the query replies are ported so far. */
    private fun dailyRoute(opcode: Int, payload: ByteArray): List<Frame> {
        try {
            if (!queriesSent) throw Acquisition.Rejected("Complete initialization queries first")
            deploymentPolicy() ?: throw Acquisition.Rejected("Daily systems need a store-backed character with a deployment policy")
        } catch (e: IllegalArgumentException) {
            val code = (e as? Acquisition.Rejected)?.code ?: 102
            log("rejected_daily", "character_id" to characterId, "opcode" to opcode, "reason" to e.message, "error_code" to code)
            return listOf(6 to TransactionPackets.errorPayload(code))
        }
        if (opcode in Routes.DAILY_QUERIES) group(opcode, "daily query reply")
        group(opcode, "daily system")
    }

    /** Social requests (guild, friends, chat, mail). */
    private fun socialRoute(opcode: Int, payload: ByteArray): List<Frame> {
        try {
            if (!queriesSent) throw Acquisition.Rejected("Complete initialization queries first")
            deploymentPolicy() ?: throw Acquisition.Rejected("The social layer needs a store-backed character with a deployment policy")
        } catch (e: IllegalArgumentException) {
            val code = (e as? Acquisition.Rejected)?.code ?: 102
            log("rejected_social", "character_id" to characterId, "opcode" to opcode, "reason" to e.message, "error_code" to code,
                "now_epoch" to null)
            return listOf(6 to TransactionPackets.errorPayload(code))
        }
        group(opcode, "social system")
    }

    /** The initialization query set and the fall-through (the reference's legacy handler, game service). */
    private fun legacyHandle(opcode: Int, payload: ByteArray): List<Frame> {
        if (loggedIn && !queriesSent) {
            // The same query set arrives in different orders; each known query is accepted once, in any order, and
            // initialization is complete when every non-optional query has arrived (or the reconnect set).
            if (opcode in QUERY_SEQUENCE && opcode !in seenQueries) {
                if ((opcode == 1761 && payload.size != 2) || (opcode != 1761 && payload.isNotEmpty())) {
                    throw IllegalArgumentException("Unexpected payload for initial query $opcode")
                }
                seenQueries.add(opcode)
                queryIndex++
                var replies: List<Frame> = emptyList()
                if (opcode == 1761) trainingPage = WireReader(payload).u16()   // answered with the batch
                if (opcode == 2241) replies = leaderInfoReply()
                val required = QUERY_SEQUENCE.toSet() - OPTIONAL_QUERIES
                val reconnect = required - RECONNECT_OMITTED
                if (!seenQueries.containsAll(required) && !(seenQueries.containsAll(reconnect) && seenQueries.intersect(RECONNECT_OMITTED).isEmpty())) {
                    return replies
                }
                if (!seenQueries.containsAll(required)) log("reconnect_query_set", "character_id" to characterId, "omitted" to RECONNECT_OMITTED.sorted())
                queriesSent = true
                val base = savedAfterQueries ?: emptyList()
                // The completing query also carries the jewelry list (the Fortify-Jewelry "Select Main Cards").
                val jewelry = jewelryFrames ?: emptyList()
                return replies + base + jewelry + acquisitionLoginFrames() + sweepLoginFrames() + dailyQueryFrames() + socialLoginFrames()
            }
        }
        log("unsupported_request", "service" to kind, "opcode" to opcode, "payload_bytes" to payload.size)
        return emptyList()
    }

    /** The connection ended: presence offline and S400 to the friends watching this player. */
    fun socialLogout() {
        socialRole ?: return
        service.world ?: return
        throw NotPorted("presence offline at the connection close (social_logout)")
    }

    /** The connection ended (the reference logs the presence change of a game session here). */
    fun disconnected() {
        if (kind != "game") return
        try {
            socialLogout()
        } catch (e: NotPorted) {
            log("not_implemented", "service" to kind, "feature" to (e.message ?: ""), "character_id" to characterId)
        }
    }
}
