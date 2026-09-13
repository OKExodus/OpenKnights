package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.AccountRegistry
import io.github.okexodus.openknights.server.store.StateStore
import io.github.okexodus.openknights.server.store.WorldDirectory
import java.math.BigInteger

/**
 * The daily routes' login parts (`daily_routes.py`): the documents with their seeds, the one audited refresh
 * revision before the S18 (`refresh_needed` dry run on a copy, `refresh_plan` inside the transaction), the unsolicited
 * login burst (S1760 Event Hall set, S1824, Hidden Training pushes, S3234) and the replies to the initialization
 * queries this module owns. The action planners (`planner_for`) belong to the daily actions port.
 */
object DailyRoutes {
    /** Opcode → action of the committed daily requests (`ACTIONS`, reference order). */
    val ACTIONS: Map<Int, String> = linkedMapOf(1635 to "event_palace_visit", 1637 to "event_palace_claim", 1641 to "event_magic_pie",
        3077 to "event_combine", 1665 to "event_exchange", 1091 to "daily_check_in", 1093 to "daily_time_gift", 481 to "daily_salary",
        2541 to "daily_mission_gift", 2371 to "royal_door_daily", 2373 to "royal_door_level_up", 2375 to "royal_door_donate",
        261 to "quest_claim", 259 to "bounty_task", 263 to "bounty_task", 265 to "bounty_task", 269 to "bounty_task", 271 to "bounty_task",
        273 to "bounty_task", 275 to "bounty_task", 423 to "arena_reward", 161 to "castle_collect", 545 to "castle_building",
        97 to "castle_tech", 2179 to "castle_guild_tech", 739 to "castle_transmute", 737 to "castle_alchemy_refresh",
        769 to "castle_buy_slot", 745 to "castle_work", 749 to "castle_release", 781 to "castle_guard", 1763 to "training_create_room",
        1767 to "training_seat", 1769 to "training_password", 1777 to "training_claim", 1779 to "training_add_time",
        1643 to "forge_smith", 3747 to "forge_craft", 3731 to "forge_no_cd", 3753 to "forge_no_cd", 2113 to "explore_task",
        2115 to "explore_task", 2117 to "explore_task", 2123 to "explore_task", 2119 to "explore_claim", 2121 to "explore_task",
        2125 to "explore_task", 3723 to "card_sacrifice", 3725 to "card_sacrifice", 3727 to "card_sacrifice")

    /** The daily queries (no state change). */
    val QUERIES = listOf(1089, 2539, 2369, 2377, 3073, 417, 421, 2471, 163, 777, 753, 755, 751, 1761, 1765, 1771, 1773, 1775)

    /** Requests that may change an unequipped (opcode-3072) jewel. */
    val JEWEL_OPCODES = listOf(3747, 3727, 1665)

    /** Requests answered with an S6 code and no change. */
    val REFUSALS: Map<Int, Int> = linkedMapOf(3075 to 25000, 425 to 15004, 771 to 21001, 773 to 21001, 775 to 21001)

    val OPCODES: List<Int> = ACTIONS.keys.toList() + QUERIES + REFUSALS.keys.toList()

    /** Initialization queries answered from owned state, in the live reply order of the post-query batch. */
    val LOGIN_QUERY_ORDER = listOf(257, 2369, 1089, 3073)

    const val C_CROSS_ARENA = 2471
    const val S_CROSS_ARENA = 2820
    val CROSS_CLOSED = byteArrayOf(0)

    /**
     * World-level reads for the daily systems (a world-less session sees the birth defaults). `powerOf(current)`: the
     * characters' Power in the participant lists (the service passes the universal Power; else the world's bound one).
     * `participantsOf`: the world participant list builder (`world_participants`, ported with the social systems).
     */
    class WorldContext(val world: WorldDirectory? = null, val registry: AccountRegistry? = null,
                       powerOf: ((StateStore.Current) -> BigInteger?)? = null, val bots: List<Any> = emptyList(),
                       private val participantsOf: ((WorldContext, StateStore.Current?) -> List<Any>)? = null) {
        val powerOf: ((StateStore.Current) -> BigInteger?)? = powerOf ?: world?.powerOf

        /** (revision, document) of a world document; the birth default without a world; a missing one is refused. */
        fun document(name: String): Pair<Long?, JObj> {
            if (world == null) {
                val template = WorldDirectory.WORLD_DOCUMENTS.firstOrNull { it.first == name }?.second ?: throw PyDocs.KeyError("'$name'")
                return null to PyDocs.shallow(template).also { it["born_at_utc"] = JStr("unborn") }
            }
            val found = world.document(name) ?: throw Acquisition.Rejected("World document $name is missing (migrate the world)")
            return found.first to found.second
        }

        /**
         * Add Royal Door EXP to the world's Door (`raise_door_exp`): level-ups by lv_yijiezhimen col 102. A world write
         * after the character's commit (labeled policy), retried when the document changed meanwhile. Returns (level
         * before, level after); (null, null) without a world or EXP.
         */
        fun raiseDoorExp(amount: Long, inputs: DailyInputs, actor: String = "local-service", attempts: Int = 3): Pair<Long?, Long?> {
            if (world == null || amount <= 0) return null to null
            repeat(attempts) {
                val (revision, door) = document("royal_door")
                val before = PyDocs.long(PyDocs.at(door, "level"))
                val after = Daily.doorAfter(door, amount, inputs)
                val level = after.long("level")
                try {
                    world.putDocument("royal_door", after, revision!!, actor, "royal_door_exp",
                        jobj("added" to amount, "level_before" to before, "level_after" to level))
                    return before to level
                } catch (e: IllegalArgumentException) {
                    // changed meanwhile: read again
                }
            }
            throw PyValues.ValueError("Royal Door EXP could not be written (world document kept changing)")
        }

        /** World characters + bots (`WorldContext.participants`). */
        fun participants(current: StateStore.Current? = null): List<Any> =
            participantsOf?.invoke(this, current) ?: throw NotPorted("world_participants (the participant list of the daily routes)")
    }

    /** (payload, provenance) of the first seed frame of an opcode (of an S1760 type) that decodes; else (null, null). */
    private fun seed(seeds: SystemSeeds.SeedFrames?, opcode: Int, eventType: Int? = null, decoder: ((ByteArray) -> Unit)? = null): Pair<ByteArray?, JObj?> {
        if (seeds == null) return null to null
        seeds.all(opcode).forEachIndexed { index, payload ->
            if (eventType != null && !(payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == eventType)) return@forEachIndexed
            if (decoder != null) {
                try {
                    decoder(payload)
                } catch (e: IllegalArgumentException) {
                    return@forEachIndexed
                } catch (e: IndexOutOfBoundsException) {
                    return@forEachIndexed
                }
            }
            return payload to seeds.provenance(opcode, index)
        }
        return null to null
    }

    // --- documents with their seeds ---------------------------------------------------------------------------------------

    private fun checkMonth(payload: ByteArray) {
        if (payload.size < 5 || payload.size != 5 + (payload[4].toInt() and 0xFF)) throw PyValues.ValueError("Not an S1152 month frame")
    }

    /**
     * The personal Guild Tech list (S2330): the character's own levels (stored, else the seed frame), shown only while
     * the character is in a world guild, capped by that guild's tech levels.
     */
    fun guildTechDocument(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, worldCtx: WorldContext? = null, inputs: DailyInputs? = null): JObj {
        val stored = PyDocs.get(current, "guild_tech_state")
        val document: JObj = if (stored != null) stored as JObj else {
            val (payload, provenance) = seed(seeds, Castle.S_GUILD_TECH, decoder = { Castle.decodeGuildTech(it) })
            Castle.decodeGuildTech(payload ?: byteArrayOf(0)).also { it["seed"] = provenance ?: JNull }
        }
        if (worldCtx == null || worldCtx.world == null || inputs == null) return document
        val (_, guilds) = worldCtx.document("guilds")
        val guild = Guild.guildOf(if (Py.truthy(guilds)) guilds else jobj("guilds" to JObj()), PyDocs.long(ownId(current))).second
        return Guild.personalTechView(document, guild, inputs)
    }

    /** Hero Set Out slots: stored, else seeded once from the S2274 seed frame. */
    fun exploreDocument(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, now: Long): JObj {
        PyDocs.get(current, "explore_state")?.let { return it as JObj }
        val (payload, provenance) = seed(seeds, HiddenTraining.S_EXPLORE_SLOTS, decoder = { HiddenTraining.decodeSlots(it) })
        return HiddenTraining.exploreDocument(null, payload, now, provenance)
    }

    /** Login-burst pushes of Hidden Training: S1760 type 5 Blacksmith cooldowns, S2274 Set Out slots, S3726 Crafting. */
    fun trainingLoginFrames(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, now: Long): List<Frame> {
        val forge = PyDocs.get(current, "forge_state")
        return listOf(HiddenTraining.smithCdFrame(forge, now),
            HiddenTraining.S_EXPLORE_SLOTS to HiddenTraining.slotsPayload(exploreDocument(current, seeds, now), now),
            HiddenTraining.craftCdFrame(forge, now))
    }

    /** S228 `01`, S2330 personal Guild Tech list, S226 next collect costs. */
    fun castleLoginFrames(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, inputs: DailyInputs, now: Long, worldCtx: WorldContext? = null): List<Frame> {
        val vip = PyDocs.long(PyDocs.roleStrict(current.state, Castle.VIP_LEVEL))
        val document = Castle.castleDocument(PyDocs.get(current, "castle_state"), now)
        return listOf(Castle.S_COLLECT_BONUS to byteArrayOf(1),
            Castle.S_GUILD_TECH to Castle.guildTechPayload(guildTechDocument(current, seeds, worldCtx, inputs)),
            Castle.S_COLLECT_COST to Castle.collectCostPayload(document, inputs, vip))
    }

    fun palaceDocument(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?): JObj {
        val stored = PyDocs.get(current, "palace_state")
        if (Py.truthy(stored)) return stored as JObj
        val (payload, provenance) = seed(seeds, EventHall.S_EVENT_UPDATE, EventHall.T_PALACE)
        return EventHall.seedPalace(payload, provenance)
    }

    fun signDocument(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, now: Long): JObj {
        PyDocs.get(current, "sign_in_state")?.let { return it as JObj }
        val (payload, provenance) = seed(seeds, Daily.S_SIGN_MONTH, decoder = { checkMonth(it) })
        return Daily.seedSignIn(payload, now, provenance)
    }

    fun questDocument(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?): JObj {
        PyDocs.get(current, "quest_state")?.let { return it as JObj }
        val (payload, provenance) = seed(seeds, Quests.S_QUESTS, decoder = { Quests.decodeQuests(it) })
        return Quests.seedQuests(payload, provenance)
    }

    fun boardDocument(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, now: Long): JObj? {
        PyDocs.get(current, "bounty_board")?.let { return it as JObj }
        val (payload, provenance) = seed(seeds, Quests.S_BOARD, decoder = { Quests.decodeBoard(it) })
        return if (payload != null) Quests.seedBoard(payload, now, provenance) else null
    }

    private fun role(current: StateStore.Current, fieldId: Long, default: Long = 0): JValue? = PyDocs.role(current.state, fieldId, JInt(default))

    private fun level(state: JObj): Long = PyDocs.long(PyDocs.role(state, Quests.ROLE_LEVEL, JInt(1)))

    private fun ownId(current: StateStore.Current): JValue = role(current, 0) ?: JNull

    // --- queries (no state change) -----------------------------------------------------------------------------------------

    /** Training room queries: C1761 list (u16 page, whose value the list ignores), C1771 invite (no reply). */
    fun trainingQuery(opcode: Int, payload: ByteArray, current: StateStore.Current, inputs: DailyInputs, now: Long): List<Frame> {
        val state = current.state
        val document = PyDocs.get(current, "training_state")
        when (opcode) {
            HiddenTraining.C_ROOM_LIST -> {
                if (payload.size != 2) throw Acquisition.Rejected("C1761 is u16 page")
                return HiddenTraining.roomListReply(document, state, inputs, now)
            }
            HiddenTraining.C_ROOM_ENTER -> throw NotPorted("hidden_training.enter_reply (C1765)")
            HiddenTraining.C_ROOM_INVITE -> {
                if (payload.size != 4) throw Acquisition.Rejected("C1771 is u32 room")
                return emptyList()
            }
            HiddenTraining.C_ROOM_KICK -> {
                if (payload.size != 8) throw Acquisition.Rejected("C1773 is u32 room + u32 player")
                throw NotPorted("hidden_training.kick_reply (C1773)")
            }
        }
        if (payload.isNotEmpty()) throw Acquisition.Rejected("C1775 has no payload")
        throw NotPorted("hidden_training.preview_reply (C1775)")
    }

    fun queryReply(opcode: Int, payload: ByteArray, current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, inputs: DailyInputs,
                   now: Long, worldCtx: WorldContext, ownerKey: String): List<Frame> {
        if (opcode in listOf(HiddenTraining.C_ROOM_LIST, HiddenTraining.C_ROOM_ENTER, HiddenTraining.C_ROOM_INVITE, HiddenTraining.C_ROOM_KICK,
                HiddenTraining.C_TRAIN_PREVIEW)) return trainingQuery(opcode, payload, current, inputs, now)
        if (payload.isNotEmpty()) throw Acquisition.Rejected("C$opcode has no payload")
        when (opcode) {
            Daily.C_SIGN_QUERY -> {
                val (document, _) = Daily.signRoll(signDocument(current, seeds, now), now, inputs)
                return Daily.signFrames(document, now)
            }
            Daily.C_MISSION_INFO -> return listOf(Daily.S_MISSION_INFO to Daily.missionPayload(Daily.missionView(PyDocs.get(current, "daily_mission_state"), now)))
            Daily.C_DOOR_INFO, Daily.C_DOOR_DONATED -> {
                val (_, door) = worldCtx.document("royal_door")
                val document = Daily.doorView(PyDocs.get(current, "royal_door_state"), now, PyDocs.at(door, "born_at_utc"))
                return if (opcode == Daily.C_DOOR_INFO) listOf(Daily.S_DOOR_INFO to Daily.doorPayload(document, door))
                    else listOf(Daily.S_DOOR_DONATED to Daily.donatedPayload(document))
            }
            Daily.C_COMEBACK_QUERY -> return listOf(Daily.S_COMEBACK_INFO to Daily.COMEBACK_INACTIVE)
            C_CROSS_ARENA -> return listOf(S_CROSS_ARENA to CROSS_CLOSED)
            Castle.C_COLLECT_COST -> {
                val vip = PyDocs.long(PyDocs.roleStrict(current.state, Castle.VIP_LEVEL))
                val document = Castle.castleDocument(PyDocs.get(current, "castle_state"), now)
                return listOf(Castle.S_COLLECT_COST to Castle.collectCostPayload(document, inputs, vip))
            }
            Castle.C_ALCHEMY_INFO -> {
                val (values, _) = Castle.alchemyView(current.state, PyDocs.obj(current, "castle_state"), inputs, now)
                return listOf(Castle.alchemyFrame(values))
            }
            Castle.C_CATCH_LIST -> throw NotPorted("castle.catch_list_payload (C753, the world participants)")
            Castle.C_RESCUE_LIST -> return listOf(Castle.S_RESCUE_LIST to Castle.rescueListPayload())
            Castle.C_SERVANT_CHECK -> return emptyList()
            417, 421 -> throw NotPorted("arena queries (C417 / C421, the world participants and the arena ladder)")
        }
        throw Acquisition.Rejected("Not a daily query")
    }

    /**
     * Replies to the initialization queries this module owns, in the live batch order: S228, S2330, S320, S322, S226,
     * S2720, S1154, S1152, [S2112 when the client sent C1761], S3296. Rolls done here are display-only.
     */
    fun loginQueryFrames(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, inputs: DailyInputs, now: Long, worldCtx: WorldContext,
                         ownerKey: String, trainingPage: Long? = null): List<Frame> {
        val quests = Quests.refreshStates(questDocument(current, seeds), inputs, level(current.state))
        val (board, _) = Quests.boardRoll(boardDocument(current, seeds, now), inputs, now, ownerKey)
        val (bonus, guildTech, collectCost) = castleLoginFrames(current, seeds, inputs, now, worldCtx)
        val frames = mutableListOf(bonus, guildTech, Quests.S_QUESTS to Quests.questsPayload(quests), Quests.S_BOARD to Quests.boardPayload(board, now), collectCost)
        for (opcode in listOf(Daily.C_DOOR_INFO, Daily.C_SIGN_QUERY)) frames += queryReply(opcode, ByteArray(0), current, seeds, inputs, now, worldCtx, ownerKey)
        if (trainingPage != null) frames += trainingQuery(HiddenTraining.C_ROOM_LIST, WireWriter().number('H', trainingPage).bytes(), current, inputs, now)
        frames += queryReply(Daily.C_COMEBACK_QUERY, ByteArray(0), current, seeds, inputs, now, worldCtx, ownerKey)
        return frames
    }

    /** The campaign document (claimed star boxes, reset day, regen anchors), seeded once from the seed S3234. */
    fun campaignDocument(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?): JObj {
        PyDocs.get(current, "campaign_state")?.let { return it as JObj }
        val payload = seeds?.first(Campaign.S_BOXES)
        return Campaign.newDocument(payload, if (payload != null) seeds.provenance(Campaign.S_BOXES) else jobj("source" to "empty"))
    }

    /**
     * Unsolicited login frames: the S1760 Event Hall set (+ S1824), then the Hidden Training pushes with the campaign's
     * claimed star boxes S3234 before S3726.
     */
    fun loginBurstFrames(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, inputs: DailyInputs, now: Long, servedTime: Long): List<Frame> {
        val training = trainingLoginFrames(current, seeds, now)
        val boxes = Campaign.S_BOXES to Campaign.boxesPayload(campaignDocument(current, seeds))
        val at = training.indexOfFirst { it.first == HiddenTraining.S_CRAFT_CD }.let { if (it < 0) training.size else it }
        return EventHall.loginFrames(current, seeds, inputs, now, servedTime) + training.subList(0, at) + listOf(boxes) + training.subList(at, training.size)
    }

    // --- login refresh (one audited revision before the S18, only when something must be stored) -------------------------

    /** Whether the login refresh has anything to store (a dry run on a copy of the read; title promotion is skipped). */
    fun refreshNeeded(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, inputs: DailyInputs, now: Long, ownerKey: String,
                      servedTime: Long? = null, social: JObj? = null, questClaims: List<Long>? = null): Boolean =
        refresh(null, PyDocs.deepCopy(current), seeds, inputs, now, ownerKey, servedTime, social, questClaims) != null

    /** Local event definitions at login: reconcile the S18 activity list and count a new login day. */
    internal fun eventsRefresh(state: JObj, current: StateStore.Current, now: Long, servedTime: Long?): JObj? {
        val definitions = Events.ACTIVE
        if (servedTime == null || PyDocs.get(state.obj("subsystems"), "game_activities") == null) return null
        val activities = state.obj("subsystems").obj("game_activities")
        fun eventState(): JObj = PyDocs.shallow(PyDocs.get(current, "event_state")?.takeIf { Py.truthy(it) }?.let { it as JObj }
            ?: jobj("profile" to "event_state_v1"))
        if (!Py.truthy(definitions["activities"])) {
            val rolled = Events.rollRouletteWindow(activities, definitions, servedTime)
            if (Py.truthy(definitions["retain_captured"]) || !Py.truthy(activities.obj("first_list")["entries"])) {
                return if (rolled) eventState() else null
            }
            Events.reconcile(state, definitions, servedTime)
            return eventState()
        }
        val vip = PyDocs.roleStrict(state, 27)
        val changed = Events.reconcile(state, definitions, servedTime, mapOf("vip_level" to vip))
        val document = eventState()
        val today = Shops.dayOf(now)
        if (PyDocs.get(document, "login_day") != JStr(today)) {
            Events.advance(state, definitions, "login_days", 1, servedTime)
            document["login_day"] = JStr(today)
            return document
        }
        return if (changed) document else null
    }

    /** Castle at login: unlocked buildings and reachable techs join, the recruits' and Transmute countdowns refresh. */
    internal fun castleRefresh(state: JObj, current: StateStore.Current, inputs: DailyInputs, now: Long): JObj? {
        val subsystems = state.obj("subsystems")
        if (PyDocs.get(subsystems, "alchemy") == null) return null
        val sections = listOf("buildings", "technologies", "alchemy", "servants")
        fun dump(): String = PyDocs.sortedDump(JArr((sections.map { PyDocs.at(subsystems, it) } + PyDocs.at(state, "servant_messages")).toMutableList()))
        val before = dump()
        Castle.unlockBuildings(state, inputs, level(state))
        Castle.listNewTechs(state, inputs)
        var document = Castle.castleDocument(PyDocs.get(current, "castle_state"), now)
        document = Castle.servantsView(state, document, now, inputs).first
        val (values, timers) = Castle.alchemyView(state, document, inputs, now)
        subsystems.obj("alchemy")["wire_values"] = values
        document["alchemy"] = timers
        val after = dump()
        if (after == before && PyDocs.get(current, "castle_state") != null) return null
        return document
    }

    private fun rouletteRefresh(state: JObj, current: StateStore.Current, now: Long, servedTime: Long?): JObj? {
        val activities = PyDocs.get(state.obj("subsystems"), "game_activities")
        val roulette = if (Py.truthy(activities)) PyDocs.get(activities as JObj, "roulette") else null
        if (roulette == null || servedTime == null) return null
        val values = (roulette as JObj).arr("wire_values")
        val today = Shops.dayOf(now)
        val stored = PyDocs.get(current, "roulette_day")
        var document = PyDocs.shallow(if (Py.truthy(stored)) stored as JObj else JObj())
        var changed = false
        if (document.isEmpty()) {
            document = jobj("profile" to "roulette_day_v1", "day" to today)
            changed = true
        } else if (PyDocs.get(document, "day") != JStr(today)) {
            values[3] = JInt(0)
            document["day"] = JStr(today)
            changed = true
        }
        if (PyDocs.compare(values[2], JInt(minOf(Events.MAX_END, servedTime + Events.PERMANENT_SECONDS / 2))) < 0) {
            values[2] = JInt(minOf(Events.MAX_END, servedTime + Events.PERMANENT_SECONDS))
            changed = true
        }
        return if (changed) document else null
    }

    /**
     * Stacks of the currency placeholder items granted as bag items are paid into their currencies at login. Returns
     * [[template, count]] (a dry run — `owned` null — only reports).
     */
    private fun currencyItemRepair(owned: Owned?, current: StateStore.Current, inputs: DailyInputs): JArr {
        val view = owned ?: Owned(current, inputs)
        val found = LinkedHashMap<Long, Long>()
        for (entry in view.items.values) {
            if (entry.template in Acquisition.CURRENCY_ITEM_ROLE && entry.count > 0 && entry.timed == 0L) found[entry.template] = (found[entry.template] ?: 0L) + entry.count
        }
        for (template in found.keys.toList()) {
            try {
                view.role(Acquisition.CURRENCY_ITEM_ROLE.getValue(template))
            } catch (e: Acquisition.Rejected) {
                found.remove(template)
            }
        }
        if (owned != null) {
            for ((template, count) in found) {
                owned.consumeTemplate(template, count)
                owned.roleAdd(Acquisition.CURRENCY_ITEM_ROLE.getValue(template), count)
            }
        }
        return JArr(found.entries.sortedBy { it.key }.mapTo(ArrayList()) { jarr(it.key, it.value) })
    }

    private fun rebirthShopRefresh(state: JObj, current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, inputs: DailyInputs, now: Long, ownerKey: String): JObj? {
        val seedPayload = seeds?.first(RebirthShop.S_LIST)
        val stored = PyDocs.get(current, "rebirth_shop")
        val (document, changed) = RebirthShop.view(if (Py.truthy(stored)) stored!!.deepCopy() as JObj else null, inputs, state, now, ownerKey,
            seedPayload, if (seedPayload != null) seeds.provenance(RebirthShop.S_LIST) else null)
        return if (changed) document else null
    }

    private fun campaignRefresh(owned: Owned?, state: JObj, current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, inputs: DailyInputs, now: Long): JObj? {
        if (PyDocs.get(state.obj("subsystems"), "stages") == null) return null
        val stored = PyDocs.get(current, "campaign_state")
        val document = campaignDocument(current, seeds).deepCopy()
        var changed = stored == null
        changed = Campaign.dayRoll(state, document, now) || changed
        val view = owned ?: Owned(current, inputs)
        fun snapshot(): List<Pair<BigInteger, JValue?>?> = Campaign.REGEN.map { r ->
            try { view.roleBits(r.valueRole) to PyDocs.get(document, r.anchorKey) } catch (e: Acquisition.Rejected) { null }
        }
        val before = snapshot()
        Campaign.regen(view, document, inputs, now)
        changed = changed || snapshot() != before
        if (Campaign.advanceCurStage(view, inputs)) {
            document["cur_stage_released_at"] = JInt(now)
            changed = true
        }
        return if (changed) document else null
    }

    private fun refresh(owned: Owned?, current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, inputs: DailyInputs, now: Long, ownerKey: String,
                        servedTime: Long?, social: JObj?, questClaims: List<Long>?): JObj? {
        val changes = JObj()
        val state = owned?.state ?: current.state
        // docs/SOCIAL_CONTRACT.md §3: the S18 friends section and MaxFriend follow the world (recorded as given)
        if (social != null && SocialRoutes.refreshFriends(state, social)) changes["friends_refresh"] = social
        eventsRefresh(state, current, now, servedTime)?.let { changes["event_state_after"] = it }
        val (sign, signChanged) = Daily.signRoll(signDocument(current, seeds, now), now, inputs)
        if (signChanged || PyDocs.get(current, "sign_in_state") == null) changes["sign_in_state_after"] = sign
        val boardBefore = boardDocument(current, seeds, now)
        val (board, boardChanged) = Quests.boardRoll(boardBefore, inputs, now, ownerKey)
        if (boardChanged || PyDocs.get(current, "bounty_board") == null) changes["bounty_board_after"] = board
        val quests = questDocument(current, seeds).deepCopy()
        var met = Quests.refreshOwned(quests, inputs, state)
        val lvl = level(state)
        met = Quests.backfillClaimed(quests, inputs, questClaims, lvl, current.characterProfile != null) || met
        if (Quests.unlock(quests, inputs, lvl)) {
            Quests.refreshStates(quests, inputs, lvl)
            Quests.refreshOwned(quests, inputs, state)
            met = true
        }
        if (PyDocs.get(current, "quest_state") == null || met) changes["quest_state_after"] = quests
        if (PyDocs.get(current, "explore_state") == null && seeds != null) changes["explore_state_after"] = exploreDocument(current, seeds, now)
        val flag = Daily.salaryFlag(PyDocs.get(current, "salary_state"), now)
        if (PyDocs.at(current.state, "title_reward_flag") != JInt(flag)) changes["title_reward_flag"] = JInt(flag)
        castleRefresh(state, current, inputs, now)?.let { changes["castle_state_after"] = it }
        campaignRefresh(owned, state, current, seeds, inputs, now)?.let { changes["campaign_state_after"] = it }
        rebirthShopRefresh(state, current, seeds, inputs, now, ownerKey)?.let { changes["rebirth_shop_after"] = it }
        val converted = currencyItemRepair(owned, current, inputs)
        if (converted.isNotEmpty()) changes["currency_items_converted"] = converted
        rouletteRefresh(state, current, now, servedTime)?.let { changes["roulette_day_after"] = it }
        if (owned != null && Prestige.promote(owned, inputs).isNotEmpty()) changes["title_promoted"] = owned.role(Prestige.ROLE_TITLE)["bits"] ?: JNull
        val buffs = PyDocs.get(current, "buff_state")
        if (buffs != null) {
            val section = Acquisition.buffsSection(buffs, now)
            val subsystems = state.obj("subsystems")
            if (PyDocs.sortedDump(PyDocs.get(subsystems, "buffs")) != PyDocs.sortedDump(section)) {
                subsystems["buffs"] = section
                val after = PyDocs.shallow(buffs as JObj)
                val ends = JObj()
                for ((k, v) in PyDocs.at(buffs, "ends") as JObj) if (PyDocs.compare(v, JInt(now)) > 0) ends[k] = v
                after["ends"] = ends
                changes["buff_state_after"] = after
            }
        }
        if (changes.isEmpty()) return null
        if (owned != null && "title_reward_flag" in changes) owned.state["title_reward_flag"] = changes.getValue("title_reward_flag")
        return changes
    }

    /** The planner of `daily_login_refresh`: the refresh's changes (any), labeled `preservation_policy_daily_reset`. */
    fun refreshPlan(owned: Owned, current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, inputs: DailyInputs, now: Long, ownerKey: String,
                    servedTime: Long? = null, social: JObj? = null, questClaims: List<Long>? = null): Plan {
        val data = refresh(owned, current, seeds, inputs, now, ownerKey, servedTime, social, questClaims) ?: JObj()
        data["evidence_class"] = JStr("preservation_policy_daily_reset")
        return Plan(data, emptyList())
    }

    /** The S6 code of a refused daily request. */
    fun refusal(opcode: Int): Int = REFUSALS.getValue(opcode)
}
