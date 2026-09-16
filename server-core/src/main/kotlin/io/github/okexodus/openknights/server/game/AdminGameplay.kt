package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.*
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.StateStore
import java.math.BigInteger

/** Offline command policy over the existing catalog and owned-state models. */
object AdminGameplay {
    fun plan(name: String, args: List<String>, owned: Owned, current: StateStore.Current, inputs: DailyInputs, now: Long): Plan {
        val plan = when (name) {
            "additem" -> {
                arity(args, 2, name)
                val id = positive(args[0]); val quantity = positive(args[1])
                if (id !in Acquisition.CURRENCY_ITEM_ROLE) {
                    val row = inputs.item(id) ?: throw Acquisition.Rejected("Unknown item ID $id.")
                    val cap = row.long("max_stack_203").takeIf { it != 0L } ?: 0xFFFFFFFFL
                    require(quantity <= cap) { "Quantity exceeds the item's stack limit ($cap)." }
                }
                done("Added $quantity of item $id.", listOf(owned.grantItem(id, quantity)))
            }
            "addgold", "adddiamonds" -> {
                arity(args, 1, name)
                val quantity = positive(args.single())
                val role = if (name == "addgold") Acquisition.GOLD else Acquisition.DIAMOND
                done("Added $quantity ${if (name == "addgold") "Gold" else "Diamonds"}.", listOf(owned.roleAdd(role, quantity)))
            }
            "addhero" -> {
                arity(args, 1, name)
                val id = positive(args.single())
                val base = if (inputs.single("hero", id) != null) id else id / 1000
                require(base in 1..4_294_966) { "Invalid hero ID." }
                val template = base * 1000 + 1
                require(!inputs.heroIsLeader(template)) { "Use /newcharacter for a starter hero; a character can own only one leader." }
                val (_, groups) = owned.grantHero(template)
                done("Added ${inputs.heroName(template).ifBlank { template.toString() }} at level 1 in base form.", groups.values.flatten())
            }
            "setlevel" -> setLevel(args, owned, inputs)
            "setvip" -> AdminVip.plan(args, owned, inputs, now)
            "setcastle", "setwarehouse" -> setBuilding(name, args, owned, inputs)
            "completequest" -> completeQuest(args, current, inputs)
            "levelhero" -> levelHero(args, owned, inputs)
            "evolvehero" -> evolveHero(args, owned, inputs)
            "formation" -> {
                arity(args, 0, name)
                val heroes = SecondaryTeam.ownedHeroes(owned.state)
                val lines = owned.state.arr("formation").map { it.asObj }.filter { it.long("slot_id") in 0..5 }
                    .sortedBy { it.long("slot_id") }.map { slot ->
                        val fields = heroes[slot.longOrNull("hero_uid") ?: 0]
                        val description = if (fields == null) "empty" else {
                            val values = Acquisition.heroValues(fields)
                            "${inputs.heroName(values.getValue(1)!!.long)} (Lv ${values.getValue(2)!!.long})"
                        }
                        "Slot ${slot.long("slot_id") + 1}: $description"
                    }
                done(lines.ifEmpty { listOf("No formation slots are available.") }.joinToString("\n"))
            }
            else -> throw Acquisition.Rejected("Unknown command. Use /help.")
        }
        plan["admin_command"] = name
        plan["evidence_class"] = "offline_admin_policy"
        return plan
    }

    private fun arity(args: List<String>, count: Int, name: String) {
        require(args.size == count) { AdminCommands.usage.getValue(name) }
    }
    private fun positive(text: String): Long {
        require(text.isNotEmpty() && text.all { it in '0'..'9' }) { "Use a positive whole number." }
        return text.toLongOrNull()?.takeIf { it > 0 } ?: throw Acquisition.Rejected("Use a positive whole number within range.")
    }
    private fun done(reply: String, packets: List<Frame> = emptyList()) = Plan(jobj("admin_reply" to reply), packets)

    private fun setLevel(args: List<String>, owned: Owned, inputs: DailyInputs): Plan {
        arity(args, 1, "setlevel")
        val level = positive(args.single())
        require(level <= inputs.maxRoleLevel()) { "Maximum character level is ${inputs.maxRoleLevel()}." }
        val old = owned.roleBits(PlayerLevel.ROLE_LEVEL).longValueExact()
        val packets = mutableListOf(owned.roleAdd(PlayerLevel.ROLE_LEVEL, level - old),
            owned.roleAdd(PlayerLevel.ROLE_EXP, owned.roleBits(PlayerLevel.ROLE_EXP).negate()))
        packets.addAll(Castle.unlockBuildings(owned.state, inputs, level, above = old))
        for (entry in owned.state.obj("subsystems").obj("achievements").arr("entries")) {
            val wire = entry.asObj.arr("wire_values")
            if (wire[0] == JInt(PlayerLevel.ACH_LEVEL)) {
                wire[2] = JInt(level)
                packets.add(Acquisition.S_ACHIEVEMENT to Acquisition.achievementPayload(wire))
            }
        }
        return done("Character level set to $level.", packets)
    }

    private fun setBuilding(name: String, args: List<String>, owned: Owned, inputs: DailyInputs): Plan {
        arity(args, 1, name)
        val level = positive(args.single())
        val id = if (name == "setcastle") Castle.CASTLE else Castle.WAREHOUSE
        val row = inputs.building(id) ?: throw Acquisition.Rejected("Building data is unavailable.")
        val max = row.long("max_level").takeIf { it > 0 } ?: inputs.maxRoleLevel()
        require(level <= minOf(max, 65535)) { "Maximum building level is $max." }
        val packets = ArrayList<Frame>()
        fun set(building: Long, value: Long) {
            Castle.setBuilding(owned.state, building, JInt(value))
            packets.add(Castle.S_BUILDING to WireWriter().u8(building.toInt()).u16(value.toInt()).bytes())
        }
        set(id, level)
        if (id == Castle.CASTLE) {
            for (follower in Castle.FOLLOW_CASTLE) if (follower in Castle.buildingLevels(owned.state)) {
                val cap = inputs.building(follower)?.long("max_level")?.takeIf { it > 0 } ?: level
                set(follower, minOf(level, cap))
            }
            for (tech in Castle.listNewTechs(owned.state, inputs)) packets.add(Castle.S_TECH to WireWriter().u32(tech).u32(0).bytes())
        } else {
            // Keep purchased capacity and stored items when lowering the building.
            val limits = Castle.warehouseCapacity(level, inputs)
            val before = owned.state.arr("item_capacity_values")
            val after = JArr(before.mapIndexedTo(ArrayList()) { i, value -> JInt(maxOf(value.long, limits[i])) })
            owned.state["item_capacity_values"] = after
            packets.add(Castle.S_ITEM_CAPACITY to Castle.itemCapacityPayload(after))
        }
        return done("${if (id == Castle.CASTLE) "Castle" else "Warehouse"} level set to $level.", packets)
    }

    private fun completeQuest(args: List<String>, current: StateStore.Current, inputs: DailyInputs): Plan {
        require(args.size <= 1) { AdminCommands.usage.getValue("completequest") }
        val doc = (current.document("quest_state") as? JObj)?.deepCopy() ?: throw Acquisition.Rejected("No active quests.")
        val rows = doc.arr("quests").map { it.asArr }
        val id = args.singleOrNull()?.let { positive(it) }
        val row = if (id != null) rows.firstOrNull { it[0].long == id } else rows
            .filter { it[1] == JInt(Quests.STATE_RUNNING) && inputs.quest(it[0].long)?.long("story") == 1L }
            .minByOrNull { it[0].long }
        require(row != null && row[1].long in Quests.STATE_AVAILABLE..Quests.STATE_READY) { "No matching active quest." }
        val quest = inputs.quest(row[0].long) ?: throw Acquisition.Rejected("Quest data is unavailable.")
        row[1] = JInt(Quests.STATE_READY)
        row[2] = JInt(quest.long("target"))
        return done("Quest ${row[0].long} complete. Claim its reward normally.", listOf(Quests.S_QUESTS to Quests.questsPayload(doc)))
            .also { it.data["quest_state_after"] = doc }
    }

    private fun hero(slotText: String, owned: Owned): Pair<Long, JArr> {
        val slot = positive(slotText)
        require(slot in 1..6) { "Formation slots are 1 through 6. Use /formation." }
        val entry = owned.state.arr("formation").map { it.asObj }.firstOrNull { it.long("slot_id") == slot - 1 }
        val uid = entry?.longOrNull("hero_uid") ?: 0
        val fields = SecondaryTeam.ownedHeroes(owned.state)[uid] ?: throw Acquisition.Rejected("Formation slot $slot is empty.")
        return uid to fields
    }

    private fun replace(owned: Owned, uid: Long, fields: JArr) {
        val heroes = owned.state.arr("heroes")
        val index = heroes.indexOfFirst { Acquisition.heroValues(it.asArr)[0]!!.long == uid }
        require(index >= 0) { "Hero is not owned." }
        heroes[index] = fields
    }

    private fun levelHero(args: List<String>, owned: Owned, inputs: DailyInputs): Plan {
        arity(args, 2, "levelhero")
        val (uid, fields) = hero(args[0], owned)
        val level = positive(args[1])
        val template = Acquisition.heroValues(fields)[HeroStats.TEMPLATE]!!.long
        val catalog = PkFortifyContract.heroTemplateInputs(inputs.tables, template)
        require(level <= catalog.long("cap")) { "This hero's maximum level is ${catalog.long("cap")}." }
        val resolved = HeroStats.resolveProfile(fields, catalog)
        val stats = HeroStats.statsAt(resolved, BigInteger.valueOf(level))
        val values = linkedMapOf(HeroStats.LEVEL to BigInteger.valueOf(level), HeroStats.EXP to BigInteger.ZERO)
        HeroStats.STAT_IDS.forEachIndexed { i, field -> values[field] = stats[i] }
        val changed = JArr(fields.mapNotNull { f ->
            val field = f.asObj
            values[field.long("id")]?.let { value -> jobj("id" to field["id"], "value" to jobj("tag" to field.obj("value")["tag"], "bits" to value)) }
        }.toMutableList())
        val after = HeroEvolution.afterFields(fields, changed)
        HeroStats.resolveProfile(after, catalog)
        replace(owned, uid, after)
        return done("Slot ${args[0]} hero set to level $level.", listOf(HeroEvolution.HERO_UPDATE_OPCODE to HeroEvolution.heroPropertyUpdatePayload(uid, changed)))
    }

    private fun evolveHero(args: List<String>, owned: Owned, inputs: DailyInputs): Plan {
        arity(args, 1, "evolvehero")
        val (uid, fields) = hero(args.single(), owned)
        val template = Acquisition.heroValues(fields)[HeroStats.TEMPLATE]!!.long
        val catalog = PkEvolutionContract.evolutionInputs(inputs.tables, template)
        val cost = catalog["current_grade_row"] as? JObj ?: throw Acquisition.Rejected("Hero is at maximum evolution.")
        // Waive costs and level gates, retaining the catalog's tier transitions and stat arithmetic.
        cost["gold"] = JInt(0); cost["materials"] = JArr(); cost["third_slot_quantity"] = JInt(0)
        cost["hero_level_requirement_501"] = JInt(0); cost["role_level_requirement"] = JInt(0)
        val heroes = mapOf(uid to fields)
        val policy = jobj("profile" to HeroEvolution.TEST_POLICY_PROFILE, "class" to "preservation_policy_test")
        val leader = catalog.bool("is_leader")
        val plan = if (leader) HeroEvolution.planLeaderEvolution(jobj(), heroes, uid, BigInteger.ZERO, emptyMap(), catalog, policy)
            else HeroEvolution.planOrdinaryEvolution(jobj("target_uid" to uid), heroes, BigInteger.ZERO, emptyMap(), catalog, policy)
        val after = plan.data.arr("after_target")
        HeroStats.resolveProfile(after, inputs.heroStatInputs(plan.data.long("new_template")))
        replace(owned, uid, after)
        val packets = mutableListOf(HeroEvolution.HERO_UPDATE_OPCODE to HeroEvolution.heroPropertyUpdatePayload(uid, plan.data.arr("changed_fields")))
        if (leader) packets.add(HeroEvolution.LEADER_INFO_OPCODE to HeroEvolution.leaderInfoPayload(uid, plan.data.long("leader_field_b")))
        return done("Evolved the hero in slot ${args.single()}.", packets).also {
            if (leader) {
                it.data["admin_leader_old_template"] = JInt(template)
                it.data["admin_leader_new_template"] = plan.data["new_template"]!!
            }
        }
    }
}
