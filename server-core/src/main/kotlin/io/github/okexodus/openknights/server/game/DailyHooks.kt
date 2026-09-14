package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.StateStore
import java.math.BigInteger

/**
 * Counters of the daily systems advanced by other actions (`daily_hooks.py`, docs/DAILY_CONTRACT.md §6 / §4). After a
 * counted action commits, the session runs one follow-up revision `daily_counters` that advances the story quests
 * (S320), the Daily Mission, the Royal Door tasks of the day (Essence, S2728, then the world's Door EXP), the Quest
 * Rewards board (S326), accepted guild tasks (S2344), the achievements / medals and the Goals (S3106).
 *
 * Labeled policy: the counters commit as their own audited revision right after the action (the live server sends the
 * same frames inside the action's reply); only documents that already exist are advanced.
 */
object DailyHooks {
    /** One counter event `(event, units, param)`; recorded in the history as `[event, units, param]`. */
    data class Event(val event: String, val units: Long, val param: Long?) {
        fun json(): JArr = jarr(event, units, param ?: JNull)
    }

    /** Summon lot → quest col 109 (Normal / Super / Supreme Summon). */
    val SUMMON_QUEST_KIND = mapOf(1L to 12L, 2L to 13L, 3L to 14L)
    /** Daily Mission rows (dailyactivities.csv). */
    val MISSION = mapOf("fortify_hero_items" to 101L, "fortify_hero" to 101L, "fortify_gear_items" to 102L, "fortify_gear" to 102L,
        "summon_supreme" to 103L, "transmute" to 107L, "castle_collect_gold" to 108L,
        "stage_win_1" to 105L, "stage_win_2" to 105L, "stage_win_3" to 105L)
    /** Royal Door tasks (quest_yijiezhimen.csv col 102). */
    val DOOR_TASK = mapOf("fortify_hero_items" to 4L, "fortify_hero" to 4L, "fortify_gear_items" to 5L, "fortify_gear" to 5L,
        "gear_tier_up" to 6L, "castle_collect" to 7L, "summon_supreme" to 8L, "stage_win_1" to 1L, "stage_win_2" to 1L, "stage_win_3" to 1L)
    /** quest.csv col 109 counter kinds per event. */
    val QUEST_KINDS: Map<String, List<Long>> = mapOf("fortify_hero_items" to listOf(21L), "fortify_hero" to listOf(21L),
        "fortify_gear_items" to listOf(20L), "power_up" to listOf(45L),
        "castle_collect_gold" to listOf(17L, 38L), "castle_collect_honor" to listOf(18L), "castle_collect_runes" to listOf(19L),
        "building_evolve" to listOf(15L), "tech_evolve" to listOf(16L), "transmute" to listOf(27L, 37L), "recruit_action" to listOf(29L),
        "fortify_gear" to listOf(20L), "friend_praise" to listOf(11L, 35L), "friend_add" to listOf(33L), "item_use" to listOf(48L),
        "stage_win_1" to listOf(56L), "stage_win_2" to listOf(58L), "stage_win_3" to listOf(57L), "stage_count" to listOf(46L),
        "defeat_monster" to listOf(22L))
    /** Events whose param must match quest.csv col 110. */
    val PARAM_EVENTS = setOf("item_use", "stage_count")
    /** Actions after which the owned-state quest kinds are re-read from the save. */
    val OWNED_STATE_ACTIONS = setOf("fortify_hero", "fortify_equipment", "fortify_items_hero", "fortify_items_gear", "evolve_hero",
        "evolve_leader_hero", "evolve_gear", "formation", "acquire_summon", "acquire_item_use",
        "acquire_choose_box", "acquire_merge", "acquire_fuse", "acquire_shop_buy", "rebirth_evolve",
        "rebirth_fortify", "card_sacrifice", "mail_claim", "training_claim", "forge_smith", "forge_craft",
        "explore_claim", "event_combine", "event_exchange", "evolve_jewelry", "goal_claim")
    /** Level-state quests (param = building / tech id). */
    val STATE_KINDS = mapOf("building_level" to 2L, "tech_level" to 25L)
    val COLLECT_EVENTS = mapOf(1L to "castle_collect_gold", 2L to "castle_collect_honor", 4L to "castle_collect_runes")
    const val S_DOOR_BANNER = 2728
    /** City building of the Royal Door; role property 3 = player level. */
    const val DOOR_BUILDING = 13L
    const val ROLE_LEVEL = 3L
    /** Quest Rewards board kinds that non-combat actions complete. */
    val BOARD_KINDS = mapOf("fortify_jewelry" to 63L, "door_task" to 59L)
    /** Local event-ladder sources of these counters. */
    val EVENT_SOURCE = mapOf("summon_supreme" to "summon_supreme", "summon_lot1" to "summon_any", "summon_lot2" to "summon_any",
        "summon_lot3" to "summon_any", "castle_collect_gold" to "castle_collect_gold",
        "castle_collect_honor" to "castle_collect_honor", "castle_collect_runes" to "castle_collect_runes",
        "battle_won" to "battles_won")

    /** `plan.get(key, default)` as a Long. */
    private fun num(plan: JObj, key: String, default: Long): Long = plan[key]?.let { PyDocs.long(it) } ?: default

    private fun summonKind(event: String): Long? = SUMMON_QUEST_KIND[PyValues.parseLong(event.takeLast(1))]

    /**
     * Accepted guild tasks (state 1) count the same events as the story quests (`_count_guild_tasks`); at col 108 a task
     * turns ready (state 2) and one of the day's times is used. Returns the changed document or null.
     */
    private fun countGuildTasks(document: JValue?, events: List<Event>, inputs: DailyInputs, now: Long): JObj? {
        if (!Py.truthy(document) || (document as JObj)["day"] != JStr(Shops.dayOf(now))) return null
        val doc = document.deepCopy()
        var changed = false
        for (e in events) {
            if (e.units <= 0) continue
            val kinds: List<Long?> = if (e.event.startsWith("summon_lot")) listOf(summonKind(e.event)) else QUEST_KINDS[e.event] ?: emptyList()
            for (t in doc.arr("tasks")) {
                val entry = t.asArr
                val task = inputs.guildTask(PyDocs.long(entry[0]))
                if (task == null || entry[3] != JInt(1) || task.long("kind") !in kinds) continue
                entry[2] = JInt(PyDocs.int(entry[2]) + BigInteger.valueOf(e.units))
                if (PyDocs.compare(entry[2], JInt(maxOf(1L, task.long("required")))) >= 0) {
                    entry[2] = task["required"]!!
                    entry[3] = JInt(2)
                    entry[1] = JInt(PyDocs.int(entry[1]) + BigInteger.ONE)
                }
                changed = true
            }
        }
        return if (changed) doc else null
    }

    /**
     * The events of one committed action plan (`events_for`; empty when it counts for nothing): the daily counters'
     * events, then the events only the Goals count. `packets` = the action's reply frames where the plan has them.
     */
    fun eventsFor(action: String, plan: JObj, packets: List<Frame> = emptyList()): List<Event> =
        counterEvents(action, plan) + Goals.actionEvents(action, plan, packets)

    private fun counterEvents(action: String, plan: JObj): List<Event> {
        if (action == "acquire_summon" && plan.containsKey("lot")) {
            val lot = PyDocs.long(plan["lot"])
            val events = arrayListOf(Event("summon_lot$lot", num(plan, "count", 0), null))
            if (lot == 3L) events.add(Event("summon_supreme", num(plan, "count", 0), null))
            return events
        }
        if (action == "acquire_shop_buy") return listOf(Event("shop_buy", num(plan, "quantity", 0), plan["item"]?.takeIf { it != JNull }?.let { PyDocs.long(it) }))
        if (action == "fortify_items_hero" || action == "fortify_items_gear") {
            var taken = 0L
            for (c in (plan["consumed"] as? JArr) ?: JArr()) taken += num(c.asObj, "taken", 0)
            return listOf(Event(if (action == "fortify_items_hero") "fortify_hero_items" else "fortify_gear_items", taken, null))
        }
        if (action == "fortify_hero") return listOf(Event("fortify_hero", maxOf(1L, ((plan["material_uids"] as? JArr) ?: JArr()).size.toLong()), null))
        if (action == "fortify_equipment") return listOf(Event("fortify_gear", maxOf(1L, ((plan["material_uids"] as? JArr) ?: JArr()).size.toLong()), null))
        if (action == "fortify_items_jewelry") return listOf(Event("fortify_jewelry", 1, null))
        if (action == "campaign_win") {
            val count = num(plan, "count", 1)
            val stage = PyDocs.long(PyDocs.at(plan, "stage"))
            val events = arrayListOf(Event("stage_win_${Math.floorDiv(stage, 10000L)}", count, stage), Event("stage_count", count, stage),
                Event("battle_won", count, null))
            if (Py.truthy(plan["near_level"])) events.add(Event("defeat_monster", count, null))
            return events + Event("owned_state", 1, null)
        }
        if (action == "friend_praise") return listOf(Event("friend_praise", 1, null))
        if (action == "friend_add") return listOf(Event("friend_add", 1, null))
        if (action == "acquire_item_use" && Py.truthy(plan["template"])) {
            return listOf(Event("item_use", num(plan, "count", 1), PyDocs.long(plan["template"])), Event("owned_state", 1, null))
        }
        if (action == "power_up_train") return listOf(Event("power_up", num(plan, "count", 0), null))
        if (action == "evolve_gear") return listOf(Event("gear_tier_up", 1, null))
        if (action == "castle_collect") {
            val types = ((plan["types"] as? JArr) ?: JArr()).map { PyDocs.long(it) }
            return types.map { Event(COLLECT_EVENTS.getValue(it), 1, null) } +
                (if (types.isNotEmpty()) listOf(Event("castle_collect", types.size.toLong(), null)) else emptyList())
        }
        if (action == "castle_building") return listOf(Event("building_evolve", 1, null),
            Event("building_level", PyDocs.long(PyDocs.at(plan, "level_after")), PyDocs.long(PyDocs.at(plan, "building"))))
        if (action == "castle_tech") return listOf(Event("tech_evolve", 1, null),
            Event("tech_level", PyDocs.long(PyDocs.at(plan, "level_after")), PyDocs.long(PyDocs.at(plan, "tech"))))
        if (action == "castle_transmute") return listOf(Event("transmute", 1, null))
        if (action == "castle_work") return listOf(Event("recruit_action", 1, null))
        if (action in OWNED_STATE_ACTIONS) return listOf(Event("owned_state", 1, null))
        return emptyList()
    }

    /**
     * Achievement events of one committed action (`achievement_events`): the counters the achievements keep (hero evolve
     * 21, gear evolve 22), else a marker when the action's own reply holds an S578.
     */
    fun achievementEvents(action: String, packets: List<Frame>): List<Event> {
        val feed = Achievements.ACTION_FEEDS[action]
        if (feed != null) return listOf(Event(feed, 1, null))
        if (packets.any { it.first == Achievements.S_ACHIEVEMENT }) return listOf(Event(Achievements.CHECK, 1, null))
        return emptyList()
    }

    private fun roleLevel(state: JObj): JValue {
        for (f in (state["role_properties"] as? JArr) ?: JArr()) {
            if (f.asObj["id"] == JInt(ROLE_LEVEL)) return PyDocs.at(f.asObj.obj("value"), "bits")
        }
        return JInt(0)
    }

    /**
     * The follow-up revision's plan (`plan_counters`): documents + frames (S1188 of local event ladders, S2344, S320,
     * Essence S68 / S64, S2728, S326, then the achievement / medal frames, then the Goals' S3106 rows), the Door EXP to
     * add and the new-medal mails (`medal_mails`). `power(current)` = the universal Power (only the Goals read it).
     */
    fun planCounters(owned: Owned, current: StateStore.Current, allEvents: List<Event>, inputs: DailyInputs, now: Long,
                     worldDoor: JObj?, servedTime: Long? = null, power: ((StateStore.Current) -> BigInteger?)? = null): Plan {
        val frames = ArrayList<Frame>()
        val plan = jobj("events" to JArr(allEvents.mapTo(ArrayList()) { it.json() }),
            "evidence_class" to "capture_observed_counters_policy_revision", "door_exp" to 0, "essence" to 0)
        val boardUnits = LinkedHashMap<Long, Long>()
        val goalEvents = allEvents.toList()
        var events = allEvents.filter { it.event !in Goals.EVENTS }
        val medalEvents = events.filter { it.event in Achievements.EVENTS }
        events = events.filter { it.event !in Achievements.EVENTS }
        if (servedTime != null && owned.state.obj("subsystems")["game_activities"].let { it != null && it != JNull }) {
            for (e in events) {
                val source = EVENT_SOURCE[e.event]
                if (source != null && e.units > 0) frames += Events.advance(owned.state, Events.ACTIVE, source, e.units, servedTime)
            }
        }
        val stored = PyDocs.get(current, "quest_state")
        val quests: JObj? = stored?.deepCopy() as JObj?
        var mission: JValue? = PyDocs.get(current, "daily_mission_state")
        var missionChanged = false
        val doorUnits = LinkedHashMap<Long, Long>()
        for (e in events) {
            if (e.units <= 0) continue
            val stateKind = STATE_KINDS[e.event]
            if (quests != null && stateKind != null) {
                if (Quests.setState(quests, inputs, stateKind, e.units, e.param)) plan["quest_state_after"] = quests
                continue
            }
            if (quests != null) {
                val kinds: List<Long?> = if (e.event.startsWith("summon_lot")) listOf(summonKind(e.event)) else QUEST_KINDS[e.event] ?: emptyList()
                for (kind in kinds) {
                    if (kind != null && kind != 0L && Quests.count(quests, inputs, kind, e.units, if (e.event in PARAM_EVENTS) e.param else null)) {
                        plan["quest_state_after"] = quests
                    }
                }
                if (e.event == "shop_buy") {
                    for (shopKind in listOf(39L, 34L)) if (Quests.count(quests, inputs, shopKind, e.units, e.param)) plan["quest_state_after"] = quests
                }
            }
            val missionId = MISSION[e.event]
            if (missionId != null) {
                mission = Daily.missionCount(mission, missionId, 1, inputs, now)
                missionChanged = true
            }
            DOOR_TASK[e.event]?.let { doorUnits[it] = (doorUnits[it] ?: 0L) + e.units }
            BOARD_KINDS[e.event]?.let { boardUnits[it] = (boardUnits[it] ?: 0L) + 1 }
            for (kind in QUEST_KINDS[e.event] ?: emptyList()) boardUnits[kind] = (boardUnits[kind] ?: 0L) + e.units
        }
        if (quests != null && events.isNotEmpty()) {
            // level quests after an action that levelled the player; level-gated story / [Sub] rows join
            val before = quests.arr("quests").map { it.deepCopy() }
            val levelNow = PyDocs.long(roleLevel(owned.state))
            Quests.unlock(quests, inputs, levelNow)
            Quests.refreshStates(quests, inputs, levelNow)
            val levelled = quests.arr("quests").toList() != before
            if (Quests.refreshOwned(quests, inputs, owned.state, owned) || levelled) plan["quest_state_after"] = quests
        }
        val guildTasks = countGuildTasks(PyDocs.get(current, "guild_task_state"), events, inputs, now)
        if (guildTasks != null) {
            plan["guild_task_state_after"] = guildTasks
            frames.add(Guild.S_TASKS to Guild.tasksPayload(guildTasks, now))
        }
        if (missionChanged) plan["daily_mission_state_after"] = mission ?: JNull
        val questsAfter = plan["quest_state_after"]
        if (questsAfter != null && questsAfter != JNull) frames.add(Quests.S_QUESTS to Quests.questsPayload(questsAfter as JObj))
        // Royal Door tasks count only once the Door is open for the character (building 13 unlock level).
        if (doorUnits.isNotEmpty()) {
            val row = inputs.building(DOOR_BUILDING)
            val level = roleLevel(owned.state)
            val unlock = row?.get("unlock_level").let { if (Py.truthy(it)) it!! else JInt(0) }
            if (row != null && PyDocs.compare(level, unlock) < 0) doorUnits.clear()
        }
        if (doorUnits.isNotEmpty() && worldDoor != null) {
            val door = Daily.doorView(PyDocs.get(current, "royal_door_state"), now, PyDocs.at(worldDoor, "born_at_utc"))
            val todays = door.arr("tasks")
            for ((task, units) in doorUnits.entries.sortedBy { it.key }) {
                if (JInt(task) !in todays) continue
                val row = inputs.royalDoorTask(task) ?: continue
                val essence = units * row.long("essence")
                val doorExp = units * row.long("door_exp")
                if (essence != 0L) frames.add(owned.grantItem(row.long("essence_item"), essence))
                frames.add(S_DOOR_BANNER to WireWriter().number('I', essence).number('I', doorExp).bytes())
                plan["door_exp"] = JInt(plan.long("door_exp") + doorExp)
                plan["essence"] = JInt(plan.long("essence") + essence)
                val board = BOARD_KINDS.getValue("door_task")
                boardUnits[board] = (boardUnits[board] ?: 0L) + 1
            }
            plan["royal_door_state_after"] = door
        }
        frames += countBoard(PyDocs.get(current, "bounty_board"), boardUnits, inputs, now, plan)
        // Achievements / medals: every entry is evaluated, so a step the action's own code moved past its target
        // completes here (after the counters).
        val medals = Achievements.evaluate(owned, inputs, medalEvents, base = frames.size)
        frames += medals.packets
        if (medals.completed.isNotEmpty()) {
            plan["achievements_completed"] = medals.completed
            plan["achieve_points_added"] = JInt(medals.points)
        }
        if (medals.mails.isNotEmpty()) plan["medal_mails"] = medals.mails
        // Goals: one S3106 per changed row, last (after the medal frames, so the mails' packet indexes stay valid).
        val counted = Goals.advance(owned, current, goalEvents, inputs, now, power)
        if (counted != null && counted.second.isNotEmpty()) {
            plan["goal_state_after"] = counted.first
            frames += counted.second
        }
        return Plan(plan, frames)
    }

    /**
     * Running Quest Rewards rows of the counted kinds (`_count_board`): progress += units, claimable at quest.csv col 111;
     * only on the board of today. Returns the S326 frames.
     */
    private fun countBoard(board: JValue?, units: Map<Long, Long>, inputs: DailyInputs, now: Long, plan: JObj): List<Frame> {
        if (units.isEmpty() || board == null || (board as JObj)["day"] != JStr(Shops.dayOf(now))) return emptyList()
        val doc = board.deepCopy()
        val changed = ArrayList<JArr>()
        for (r in doc.arr("rows")) {
            val row = r.asArr
            val quest = inputs.quest(PyDocs.long(row[0]))
            if (row[1] != JInt(Quests.STATE_RUNNING) || quest == null || quest.long("kind") !in units) continue
            row[2] = JInt(PyDocs.int(row[2]) + BigInteger.valueOf(units.getValue(quest.long("kind"))))
            if (PyDocs.compare(row[2], quest["target"]!!) >= 0) row[1] = JInt(Quests.STATE_READY)
            changed.add(row)
        }
        if (changed.isEmpty()) return emptyList()
        plan["bounty_board_after"] = doc
        return changed.map { Quests.S_BOARD_ROW to Quests.rowPayload(doc, it, now) }
    }

    /** The follow-up revision is worth committing: frames or a Daily Mission document (`counts_anything`). */
    fun countsAnything(plan: Plan): Boolean = plan.packets.isNotEmpty() || plan["daily_mission_state_after"].let { it != null && it != JNull }
}
