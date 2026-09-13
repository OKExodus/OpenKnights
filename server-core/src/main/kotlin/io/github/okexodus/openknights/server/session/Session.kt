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
import io.github.okexodus.openknights.server.game.Friends
import io.github.okexodus.openknights.server.game.Guild
import io.github.okexodus.openknights.server.game.Mail
import io.github.okexodus.openknights.server.game.SocialRoutes
import io.github.okexodus.openknights.server.game.HeroEvolution
import io.github.okexodus.openknights.server.game.LeaderRepair
import io.github.okexodus.openknights.server.game.Owned
import io.github.okexodus.openknights.server.game.SecondaryTeam
import io.github.okexodus.openknights.server.game.SweepFeatures
import io.github.okexodus.openknights.server.game.SystemSeeds
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.server.game.AltTeam
import io.github.okexodus.openknights.server.game.Claims
import io.github.okexodus.openknights.server.game.DailyRoutes
import io.github.okexodus.openknights.server.game.Goals
import io.github.okexodus.openknights.server.game.Py
import io.github.okexodus.openknights.server.game.Recharge
import io.github.okexodus.openknights.server.game.Shops
import io.github.okexodus.openknights.server.game.Summon
import io.github.okexodus.openknights.server.game.VipQuest
import io.github.okexodus.openknights.server.game.WorldParticipants
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

    /**
     * Before the S18: a new day clears the VIP daily-reward flag and a new VIP level sets the AP / Energy buy counts.
     * One audited revision, only when needed.
     */
    private fun vipDailyReset(current: StateStore.Current): StateStore.Current {
        val inputs = service.inputs
        val policy = deploymentPolicy()
        if (policy == null || current.state.obj("subsystems")["vip"].let { it == null || it == io.github.okexodus.openknights.exact.JNull }) return current
        val now = service.clock.now()
        return try {
            if (Claims.vipResetNeeded(current, inputs, now) == null && !VipQuest.tailNeedsUpdate(current, inputs)) return current
            val (result, plan) = stateStore!!.acquisitionTransaction("vip_daily_reset", characterId!!, policy, inputs, "local-service",
                "Daily VIP reset before the login S18", detailExtra = jobj("now_epoch" to now, "day_clock" to "service_utc")) { owned, cur ->
                Claims.planVipReset(owned, inputs, cur, now)
            }
            log("transaction_committed", "action" to "vip_daily_reset", "character_id" to characterId, "revision" to result["revision"],
                "vip_block" to plan["vip_block_after"])
            stateStore!!.read()
        } catch (e: Exception) {      // a login must not fail on this side system
            guard(e)
            log("vip_daily_reset_error", "character_id" to characterId, "error" to described(e))
            current
        }
    }

    /**
     * Before the S18: store what a new day starts (timed-gift chain start, the day's bounty board, the salary flag …);
     * one audited revision only when something must change.
     */
    private fun dailyLoginRefresh(current: StateStore.Current): StateStore.Current {
        val inputs = service.inputs
        val policy = deploymentPolicy() ?: return current
        val now = service.clock.now()
        return try {
            val seeds = seeds(current)
            val served = servedTime(current, now)
            var social: JObj? = null
            if (service.world != null) social = SocialRoutes.friendsRefreshData(current, socialContext(clockOffsetOf(current)), now)
            // A quest document without its claimed list is backfilled once from the save's quest_claim history.
            val quests = current.document("quest_state")
            val questClaims = if (quests == null || quests == io.github.okexodus.openknights.exact.JNull || (quests as JObj)["claimed"] == null) {
                stateStore!!.historyValues("quest_claim", "$.request.quest").map { (it as Number).toLong() }
            } else null
            if (!DailyRoutes.refreshNeeded(current, seeds, inputs, now, characterId!!, served, social, questClaims)) return current
            val (result, plan) = stateStore!!.acquisitionTransaction("daily_login_refresh", characterId!!, policy, inputs, "local-service",
                "Daily systems: new-day state before the login S18",
                detailExtra = jobj("now_epoch" to now, "served_time" to served, "day_clock" to "service_local")) { owned, cur ->
                DailyRoutes.refreshPlan(owned, cur, seeds, inputs, now, characterId!!, served, social, questClaims)
            }
            log("transaction_committed", "action" to "daily_login_refresh", "character_id" to characterId, "revision" to result["revision"],
                "changed" to plan.data.keys.filter { it.endsWith("_after") || it == "title_reward_flag" }.sortedWith(io.github.okexodus.openknights.exact.Json.CodePointOrder))
            stateStore!!.read()
        } catch (e: Exception) {      // a login must not fail on this side system
            guard(e)
            log("daily_login_refresh_error", "character_id" to characterId, "error" to described(e))
            current
        }
    }

    private fun worldContext(): DailyRoutes.WorldContext =
        DailyRoutes.WorldContext(service.world, service.auth.registry, service.powerOf, emptyList()) { ctx, current ->
            val world = ctx.world
            val registry = ctx.registry
            if (world == null || registry == null) {
                if (current == null) emptyList()
                else listOf(WorldParticipants.characterParticipant(jobj("character_id" to null, "created_at_utc" to ""), current, ctx.powerOf))
            } else WorldParticipants.worldParticipants(world, registry, ctx.powerOf)
        }

    /** Owned login burst frames of the daily / social systems; never fails a login. */
    private fun dailyLoginFrames(current: StateStore.Current, now: Long): List<Frame> = try {
        DailyRoutes.loginBurstFrames(current, seeds(current), service.inputs, now, servedTime(current, now))
    } catch (e: Exception) {
        guard(e)
        log("daily_login_frames_error", "character_id" to characterId, "error" to described(e))
        emptyList()
    }

    private fun seeds(current: StateStore.Current) = SystemSeeds.seedsFor(service.freshSystems, current)

    /** Before the S18: bag capacities below the Warehouse limit are raised (one audited revision, only when needed). */
    private fun warehouseCapacityRepair(current: StateStore.Current): StateStore.Current {
        val inputs = service.inputs
        val policy = deploymentPolicy() ?: return current
        return try {
            if (!SweepFeatures.capacityRepairNeeded(current.state, inputs)) return current
            val (result, plan) = stateStore!!.acquisitionTransaction("warehouse_capacity", characterId!!, policy, inputs, "local-service",
                "Bag capacities up to the Warehouse limit before the login S18",
                detailExtra = jobj("contract" to "docs/SWEEP_FEATURES_CONTRACT.md")) { owned, _ -> SweepFeatures.planCapacityRepair(owned, inputs) }
            log("transaction_committed", "action" to "warehouse_capacity", "character_id" to characterId, "revision" to result["revision"],
                "item_capacity_after" to plan["item_capacity_after"], "login" to true)
            stateStore!!.read()
        } catch (e: Exception) {      // a login must not fail on this side system
            guard(e)
            log("warehouse_capacity_repair_error", "character_id" to characterId, "error" to described(e))
            current
        }
    }

    /**
     * Before the S18, in-game-created characters only: a leader whose super-class digit disagrees with its evolve row
     * gets the row's digit and stats (one audited revision, only when needed).
     */
    private fun leaderDigitRepair(current: StateStore.Current): StateStore.Current {
        if (current.characterProfile == null) return current
        val loader = service.evolutionInputs
        val inputs = service.inputs
        val policy = deploymentPolicy() ?: return current
        return try {
            val store = stateStore!!
            val pairs = store.historyValues("evolve_leader_hero", "$.old_template").map { (it as? Number)?.toLong() }
                .zip(store.historyValues("evolve_leader_hero", "$.new_template").map { (it as? Number)?.toLong() })
            val reached = LeaderRepair.superReachedTemplates(pairs)
            if (LeaderRepair.repairTarget(Owned(current, inputs), loader, reached) == null) return current
            val (result, plan) = store.acquisitionTransaction("leader_digit_repair", characterId!!, policy, inputs, "local-service",
                "Leader super-class digit to its evolve row before the login S18",
                detailExtra = jobj("contract" to "server/leader_repair.py")) { owned, _ -> LeaderRepair.planRepair(owned, loader, reached) }
            log("transaction_committed", "action" to "leader_digit_repair", "character_id" to characterId, "revision" to result["revision"],
                "template_before" to plan["template_before"], "template_after" to plan["template_after"], "login" to true)
            store.read()
        } catch (e: Exception) {      // a login must not fail on this repair
            guard(e)
            log("leader_digit_repair_error", "character_id" to characterId, "error" to described(e))
            current
        }
    }

    /** Before the startup: the `goal_refresh` revision when something changes; (current, [S3108] or []). */
    private fun goalsLogin(initial: StateStore.Current): Pair<StateStore.Current, List<Frame>> {
        if (!sweepEnabled()) return initial to emptyList()
        var current = initial
        val inputs = service.inputs
        val now = service.clock.now()
        val seeds = seeds(current)
        val power = service.powerOf
        var payload: ByteArray?
        try {
            val (result, plan) = stateStore!!.acquisitionTransaction("goal_refresh", characterId!!, deploymentPolicy(), inputs, "local-service",
                "Goals: seed / due days before the login", detailExtra = jobj("now_epoch" to now, "contract" to "docs/GOALS_CONTRACT.md")) { owned, cur ->
                Goals.planLogin(owned, cur, seeds, inputs, now, power)
            }
            payload = (plan["s3108_hex"] as io.github.okexodus.openknights.exact.JStr).value.hexBytes()
            current = stateStore!!.read()
            log("transaction_committed", "action" to "goal_refresh", "character_id" to characterId, "revision" to result["revision"],
                "seeded" to plan["seeded"], "changed_rows" to plan["changed_rows"])
        } catch (unchanged: Goals.Unchanged) {
            payload = unchanged.payload
        } catch (e: Exception) {      // a login must not fail on this side system
            guard(e)
            log("goals_login_error", "character_id" to characterId, "error" to described(e))
            return current to emptyList()
        }
        return current to (if (payload == null) emptyList() else listOf(Goals.S_LIST to payload))
    }

    /** S2880 (totems) and S548 (album) in the startup burst where live has them; never fails a login. */
    private fun sweepStartup(packets: List<Frame>, current: StateStore.Current): List<Frame> {
        if (!sweepEnabled()) return packets
        val frames: SweepFeatures.StartupFrames
        val placed: List<Frame>
        try {
            frames = SweepFeatures.startupFrames(current, seeds(current))
            placed = SweepFeatures.placeStartupFrames(packets, frames.totems, frames.album)
        } catch (e: Exception) {
            guard(e)
            log("sweep_startup_frames_error", "character_id" to characterId, "error" to described(e))
            return packets
        }
        log("sweep_startup_frames", "character_id" to characterId, "provenance" to frames.provenance,
            "totems_bytes" to frames.totems.size, "album_bytes" to frames.album.size)
        return placed
    }

    /** S3745 right after the S18 (the live position) for an in-game-created character: its alternate team. */
    private fun altTeamStartup(initial: List<Frame>, current: StateStore.Current): List<Frame> {
        if (initial.any { it.first == 3745 }) return initial
        val payload = try {
            AltTeam.infoPayload(current, AltTeam.openRows(service.inputs))
        } catch (e: Exception) {
            guard(e)
            log("alt_team_startup_skipped", "character_id" to characterId, "error" to described(e))
            return initial
        }
        val index = initial.indexOfFirst { it.first == 18 }
        if (index < 0) return initial
        return initial.subList(0, index + 1) + listOf(3745 to payload) + initial.subList(index + 1, initial.size)
    }

    /** S2240 for the owned leader hero, generated from the character's current heroes. */
    private fun leaderInfoReply(): List<Frame> {
        val store = stateStore ?: return emptyList()
        val info: JObj?
        try {
            val heroes = SecondaryTeam.ownedHeroes(store.read().state)
            info = service.evolutionInputs.leaderInfo(heroes)
        } catch (e: Exception) {      // a catalog defect must not drop the login
            guard(e)
            log("leader_info_error", "character_id" to characterId, "error" to described(e))
            return emptyList()
        }
        if (info == null) {
            log("leader_info_absent", "character_id" to characterId)
            return emptyList()
        }
        log("leader_info_served", "character_id" to characterId, "leader_uid" to info["uid"], "template" to info["template"],
            "progress_key" to info["progress_key"])
        return listOf(2240 to HeroEvolution.leaderInfoPayload(info.long("uid"), info.long("progress_key")))
    }

    /** Owned login frames of the acquisition systems (live: S354 free-draw timers in the login burst). */
    private fun acquisitionLoginFrames(): List<Frame> {
        if (stateStore == null || characterId == null || deploymentPolicy() == null) return emptyList()
        val inputs = service.inputs
        val current: StateStore.Current
        val now: Long
        val remaining: List<Long>
        try {
            current = stateStore!!.read()
            now = service.clock.now()
            val stored = current.document(StateStore.SUMMON_STATE)
            val document = if (Py.truthy(stored)) stored as JObj else Summon.initialDocument(current, now)
            remaining = Summon.freeCdRemaining(document, now)
        } catch (e: Exception) {      // a login must not fail on this side system
            guard(e)
            log("acquisition_login_frames_error", "character_id" to characterId, "error" to described(e))
            return emptyList()
        }
        log("summon_free_cd_served", "character_id" to characterId, "remaining" to remaining,
            "stored" to (current.document(StateStore.SUMMON_STATE).let { it != null && it != io.github.okexodus.openknights.exact.JNull }))
        val frames = mutableListOf<Frame>(Summon.S_FREE_CD to Summon.freeCdPayload(remaining[0], remaining[1]))
        if (Recharge.hasCharged(current.state, current.document("recharge_ledger"))) {
            // a character that has topped up gets S1248 00 at login: the "2x Bonus" badges stay off (labeled policy)
            frames.add(Recharge.S_CHARGED to byteArrayOf(0))
        }
        try {
            val vipBlock = current.state.obj("subsystems")["vip"]
            if (vipBlock != null && vipBlock != io.github.okexodus.openknights.exact.JNull) {
                frames += Claims.buyCountFrames((vipBlock as JObj).arr("wire_values"), inputs)
            }
            frames += dailyLoginFrames(current, now)
            frames.add(Claims.S_EVENT_UPDATE to Claims.cardStatePayload(current.document("month_cards"), Shops.dayOf(now)))
        } catch (e: Exception) {
            guard(e)
            log("claims_login_frames_error", "character_id" to characterId, "error" to described(e))
        }
        val catalog = service.acquisitionCatalog
        if (catalog != null && Py.truthy(catalog["lucky"])) {
            // Live: the Lucky Shop S3170 arrives unsolicited in every login burst.
            val (entries, left, _) = Shops.luckyView(catalog, current.document("lucky_state"), now, poolPolicy = service.policyAllows("lucky_refresh"))
            frames.add(Shops.S_LUCKY_INFO to Shops.encodeLuckyInfo(entries, left, left, catalog.obj("lucky").long("flag")))
        }
        return frames
    }

    /** After-query login frames of the sweep features (S1824 temporary VIP, S3904); never fails a login. */
    private fun sweepLoginFrames(): List<Frame> {
        if (!sweepEnabled()) return emptyList()
        return try {
            val current = stateStore?.read()
            SweepFeatures.afterQueryFrames(service.inputs, current, service.clock.now())
        } catch (e: Exception) {
            guard(e)
            log("sweep_login_frames_error", "character_id" to characterId, "error" to described(e))
            emptyList()
        }
    }

    /** Replies to the initialization queries the daily systems own (S320, S322, S2720, S1154, S1152, S3296). */
    private fun dailyQueryFrames(): List<Frame> {
        if (stateStore == null || characterId == null || deploymentPolicy() == null) return emptyList()
        return try {
            val current = stateStore!!.read()
            val seeds = seeds(current)
            val now = service.clock.now()
            DailyRoutes.loginQueryFrames(current, seeds, service.inputs, now, worldContext(), characterId!!, trainingPage?.toLong())
        } catch (e: Exception) {
            guard(e)
            log("daily_query_frames_error", "character_id" to characterId, "error" to described(e))
            emptyList()
        }
    }

    private fun socialContext(clockOffset: Long? = null): SocialRoutes.SocialContext =
        SocialRoutes.SocialContext(service.world, service.auth.registry, service.inputs,
            pushFn = { role, frames -> service.pushToRole(role, this, frames) }, clockOffset = clockOffset ?: this.clockOffset)

    /** S14 epoch the client runs on minus service time (release: the served S14 is the device clock itself). */
    private fun clockOffsetOf(current: StateStore.Current): Long {
        val now = service.clock.now()
        return servedTime(current, now) - now
    }

    /** The server time the client was given (release: the device clock). */
    private fun servedTime(current: StateStore.Current, now: Long): Long = now

    /** Replies to the social initialization queries and the presence update (online + S398 to the friends watching). */
    private fun socialLoginFrames(): List<Frame> {
        if (stateStore == null || service.world == null || deploymentPolicy() == null) return emptyList()
        return try {
            val current = stateStore!!.read()
            val ctx = socialContext()
            val now = service.clock.now()
            val role = SocialRoutes.roleOf(current)
            socialRole = role
            SocialRoutes.setPresence(ctx, role, true, now)
            for (watcher in SocialRoutes.friendWatchers(ctx, role)) {
                service.pushToRole(watcher, listOf(Friends.S_ONLINE to WireWriter().u32(role).bytes()), origin = this)
            }
            val frames = SocialRoutes.loginFrames(current, ctx, now)
            log("social_login", "character_id" to characterId, "role" to role, "now_epoch" to now,
                "reply_opcodes" to frames.map { it.first }, "clock_offset" to ctx.clockOffset)
            frames
        } catch (e: Exception) {      // a login must not fail on this side system
            guard(e)
            log("social_login_frames_error", "character_id" to characterId, "error" to described(e))
            emptyList()
        }
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
        if (opcode !in DailyRoutes.QUERIES) group(opcode, "daily system")
        val packets = try {
            val now = service.clock.now()
            val current = stateStore!!.read()
            DailyRoutes.queryReply(opcode, payload, current, seeds(current), service.inputs, now, worldContext(), characterId!!)
        } catch (e: IllegalArgumentException) {
            val code = (e as? Acquisition.Rejected)?.code ?: 102
            log("rejected_daily", "character_id" to characterId, "opcode" to opcode, "reason" to e.message, "error_code" to code)
            return listOf(6 to TransactionPackets.errorPayload(code))
        } catch (e: Exception) {      // a local defect must not drop the authenticated session
            guard(e)
            log("daily_internal_error", "character_id" to characterId, "opcode" to opcode, "error" to described(e))
            return listOf(6 to TransactionPackets.errorPayload(102))
        }
        log("daily_query_served", "character_id" to characterId, "opcode" to opcode, "reply_opcodes" to packets.map { it.first })
        return packets
    }

    /** Social requests (guild, friends, chat, mail); only the three initialization queries are ported yet. */
    private fun socialRoute(opcode: Int, payload: ByteArray): List<Frame> {
        var now: Long? = null
        val packets: List<Frame>
        val ctx: SocialRoutes.SocialContext
        val served: Long
        try {
            if (!queriesSent) throw Acquisition.Rejected("Complete initialization queries first")
            deploymentPolicy() ?: throw Acquisition.Rejected("The social layer needs a store-backed character with a deployment policy")
            if (opcode !in setOf(Guild.C_MY_GUILD, Friends.C_PENDING, Mail.C_LIST)) group(opcode, "social system")
            now = service.clock.now()
            val current = stateStore!!.read()
            ctx = socialContext()
            served = servedTime(current, now)
            packets = SocialRoutes.dispatch(opcode, payload, current, ctx, now)
        } catch (e: IllegalArgumentException) {
            val code = (e as? Acquisition.Rejected)?.code ?: 102
            log("rejected_social", "character_id" to characterId, "opcode" to opcode, "reason" to e.message, "error_code" to code,
                "now_epoch" to now)
            return listOf(6 to TransactionPackets.errorPayload(code))
        } catch (e: Exception) {      // a local defect must not drop the authenticated session
            guard(e)
            log("social_internal_error", "character_id" to characterId, "opcode" to opcode, "error" to described(e))
            return listOf(6 to TransactionPackets.errorPayload(102))
        }
        log("social_served", "character_id" to characterId, "opcode" to opcode, "reply_opcodes" to packets.map { it.first },
            "now_epoch" to now, "served_time" to served, "online" to service.onlineRoles(), "clock_offset" to ctx.clockOffset)
        return packets
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

    /** The connection ended: presence offline with the time, S400 to the friends watching this player. */
    fun socialLogout() {
        val role = socialRole ?: return
        service.world ?: return
        try {
            val others = service.liveGameSessions.keys.filter { it !== this && it.socialRole == role && !it.closed }
            if (others.isNotEmpty()) return
            val ctx = socialContext()
            val now = service.clock.now()
            SocialRoutes.setPresence(ctx, role, false, now)
            log("social_logout", "character_id" to characterId, "role" to role, "now_epoch" to now)
            for (watcher in SocialRoutes.friendWatchers(ctx, role)) {
                service.pushToRole(watcher, listOf(Friends.S_OFFLINE to WireWriter().u32(role).bytes()), origin = this)
            }
        } catch (e: Exception) {
            guard(e)
            log("social_logout_error", "character_id" to characterId, "error" to described(e))
        }
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
