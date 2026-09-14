package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.server.game.FortifyInputs
import io.github.okexodus.openknights.server.game.FreshProfile
import io.github.okexodus.openknights.server.game.HeroFortify
import io.github.okexodus.openknights.server.game.HeroFortify.FortifyRejected
import io.github.okexodus.openknights.server.game.ItemFortify
import io.github.okexodus.openknights.server.game.ItemFortify.ItemFortifyRejected
import io.github.okexodus.openknights.server.game.ItemFortifyInputs
import io.github.okexodus.openknights.server.game.Py
import io.github.okexodus.openknights.server.game.SecondaryTeam
import io.github.okexodus.openknights.server.game.deepCopy
import io.github.okexodus.openknights.server.game.long
import java.math.BigInteger

/**
 * The hero / gear Fortify and EXP-item Fortify transactions of `state_store.py` (`fortify_hero`, `fortify_equipment`,
 * `fortify_items_hero` / `_gear` / `_jewelry` and their helpers): one atomic revision each (BEGIN IMMEDIATE over the
 * owned view), the planner's refusals before any write, the history detail keys in the reference's order.
 */

/** What a Fortify transaction returns: `{revision, timestamp_utc, **detail}`, the plan and the activity section. */
class FortifyResult(val result: JObj, val plan: JObj, val activitySection: JObj?) {
    operator fun get(key: String): JValue? = result[key]
}

private fun auditIdentity(actor: String, reason: String): JObj {
    if (actor.isBlank() || reason.isBlank()) throw IllegalArgumentException("Actor and reason must be nonempty text")
    return jobj("actor" to actor, "reason" to reason)
}

private fun validateRevision(expectedRevision: Long?) {
    if (expectedRevision != null && expectedRevision < 1) throw IllegalArgumentException("Expected revision must be a positive integer")
}

/** The one 64-bit Gold role field (`id 6`, tag 7 or 8), or the refusal. */
private fun goldField(state: JObj, reject: (String) -> Exception): JObj {
    val fields = state.arr("role_properties").map { it.asObj }.filter { (it["id"] as? JInt)?.value == BigInteger.valueOf(6) }.map { it.obj("value") }
    if (fields.size != 1 || (fields[0]["tag"] as? JInt)?.value?.toInt() !in setOf(7, 8)) throw reject("Expected one 64-bit Gold property")
    return fields[0]
}

private fun bitsOf(value: JObj): BigInteger = (value["bits"] as JInt).value

/**
 * `fortify_hero(request, character_id, policy, inputs, actor, reason, expected_revision)`: ordinary hero Fortify
 * (docs/FORTIFY_CONTRACT.md). Catalog values are loaded only for owned templates; every unsupported branch is refused by
 * the planner before any write. The caller emits the frames after the commit.
 */
fun StateStore.fortifyHero(request: JObj, characterId: String, policy: FreshProfile.DeploymentPolicy?, inputs: FortifyInputs?,
                           actor: String, reason: String, expectedRevision: Long? = null): FortifyResult {
    SecondaryTeam.validateOwner(characterId)
    val identity = auditIdentity(actor, reason)
    validateRevision(expectedRevision)
    if (policy == null) throw FortifyRejected("Fortify lacks explicit deployment/assignment evidence for this save")
    if (inputs == null) throw FortifyRejected("Fortify requires a catalog input loader")
    connect().use { db ->
        return db.immediate {
            val current = read(db)
            if (expectedRevision != null && current.revision != expectedRevision) throw FortifyRejected("State revision changed; reread before fortifying")
            try { policy.validateCurrent(current, characterId) } catch (e: SecondaryTeam.Rejected) { throw FortifyRejected(e.message ?: "") }
            val state = current.state
            val heroes = SecondaryTeam.ownedHeroes(state)
            val benchValue = state["offline_hero_uids"]
            if (benchValue !is JArr || benchValue.toSet().size != benchValue.size ||
                !benchValue.all { it is JInt && it.value >= BigInteger.ONE && it.value <= BigInteger.valueOf(0xffffffffL) }) {
                throw FortifyRejected("Bench is outside the verified unique-UID profile")
            }
            val bench = benchValue.map { it.long }
            val formationUids = state.arr("formation").mapNotNull { e -> e.asObj["hero_uid"]?.takeIf { Py.truthy(it) }?.long }
            val secondary = current.secondaryTeam
            val secondaryUids = if (secondary != null) secondary.obj("document").arr("references").map { it.asObj.long("hero_uid") } else emptyList()
            val deployed = LinkedHashSet(formationUids).also { it.addAll(secondaryUids) }
            if (!heroes.keys.containsAll(bench.toSet() + deployed) || bench.any { it in deployed }) {
                throw FortifyRejected("Bench and lineup ownership/membership is inconsistent")
            }
            val excluded = LinkedHashSet(policy.explorationUids).also { it.addAll(policy.miningUids) }
            val gold = goldField(state) { FortifyRejected(it) }
            val targetUid = request.long("target_uid")
            val materials = request.arr("material_uids").map { it.long }
            // Catalog values are loaded only for owned templates; the planner produces the evidenced codes first.
            var loaded: JObj? = null
            if (targetUid in heroes && materials.isNotEmpty() && materials.all { it in heroes }) {
                loaded = inputs(HeroFortify.heroUidOf(heroes.getValue(targetUid), 1).toLong(), materials.map { HeroFortify.heroUidOf(heroes.getValue(it), 1).toLong() })
            }
            val plan = HeroFortify.planHeroFortify(request, heroes, bench, deployed, excluded, bitsOf(gold), loaded)
            val removed = plan.arr("material_uids").map { it.long }.toSet()
            state["heroes"] = JArr(state.arr("heroes").filter { HeroFortify.heroUidOf(it.asArr).toLong() !in removed }.mapTo(ArrayList<JValue>()) {
                if (HeroFortify.heroUidOf(it.asArr).toLong() == targetUid) plan.getValue("after_target") else it
            })
            state["offline_hero_uids"] = plan.getValue("new_bench")
            gold["bits"] = plan.getValue("gold_after")
            val target = loaded!!.obj("target")
            val awarded = plan.arr("awarded_records")
            val detail = JObj(LinkedHashMap(identity.map))
            detail.putAll(jobj("character_id" to characterId, "target_uid" to targetUid,
                "material_uid" to plan.arr("material_uids")[0], "material_uids" to plan["material_uids"],
                "target_template" to target["template"],
                "material_template" to awarded[0].asObj["template"],
                "material_templates" to awarded.map { it.asObj["template"] },
                "before_target" to plan["before_target"], "after_target" to plan["after_target"],
                "changed_fields" to plan["changed_fields"], "removed_material" to plan["removed_material"],
                "removed_materials" to plan["removed_materials"],
                "awarded_exp" to plan["awarded_exp"], "awarded_records" to plan["awarded_records"],
                "material_multiplier" to plan["material_multiplier"],
                "profile_components" to plan["profile_components"],
                "profile_evidence_class" to plan["profile_evidence_class"],
                "stat_permille" to plan["stat_permille"], "full_grow" to plan["full_grow"],
                "gold_before" to (plan.int("gold_after") + BigInteger.valueOf(plan.long("gold_cost"))), "gold_cost" to plan["gold_cost"],
                "gold_after" to plan["gold_after"], "settlement" to plan["settlement"],
                "bench_count_before" to bench.size, "bench_count_after" to plan.arr("new_bench").size,
                "hero_count_before" to heroes.size, "hero_count_after" to state.arr("heroes").size,
                "catalog_inputs" to jobj("target" to without(target, "heroexp"),
                    "materials" to loaded.arr("materials").map { without(it.asObj, "heroexp") }),
                "deployment_policy_path" to policy.sourcePath, "deployment_policy_sha256" to policy.sha256,
                "contract" to "docs/FORTIFY_CONTRACT.md"))
            val result = commitState(db, current, "fortify_hero", detail)
            FortifyResult(result, plan, current.state.obj("subsystems").obj("game_activities"))
        }
    }
}

private fun without(value: JObj, key: String): JObj = JObj(LinkedHashMap(value.map.filterKeys { it != key }))

/**
 * `fortify_equipment(request, character_id, policy, inputs, actor, reason, expected_revision)`: gear Fortify
 * (docs/GEAR_FORTIFY_CONTRACT.md), one atomic revision; `inputs.equipment(target key, material keys)` gives the catalog
 * values keyed by (template, grade).
 */
fun StateStore.fortifyEquipment(request: JObj, characterId: String, policy: FreshProfile.DeploymentPolicy?, inputs: FortifyInputs?,
                                actor: String, reason: String, expectedRevision: Long? = null): FortifyResult {
    SecondaryTeam.validateOwner(characterId)
    val identity = auditIdentity(actor, reason)
    validateRevision(expectedRevision)
    if (policy == null) throw FortifyRejected("Gear Fortify lacks explicit owner/source evidence for this save")
    if (inputs == null) throw FortifyRejected("Gear Fortify requires a catalog input loader")
    connect().use { db ->
        return db.immediate {
            val current = read(db)
            if (expectedRevision != null && current.revision != expectedRevision) throw FortifyRejected("State revision changed; reread before fortifying")
            try { policy.validateCurrent(current, characterId) } catch (e: SecondaryTeam.Rejected) { throw FortifyRejected(e.message ?: "") }
            val state = current.state
            val equipment = LinkedHashMap<Long, JArr>()
            for (record in state.arr("equipment")) {
                val wire = record.asObj.arr("wire_values")
                val uid = wire[0].long
                if (uid in equipment) throw FortifyRejected("Duplicate owned equipment UID")
                equipment[uid] = JArr(wire.toMutableList())
            }
            val bagValue = state["bag_equipment_uids"]
            if (bagValue !is JArr || bagValue.toSet().size != bagValue.size || !equipment.keys.containsAll(bagValue.map { it.long })) {
                throw FortifyRejected("Equipment bag is outside the verified unique-owned profile")
            }
            val bag = bagValue.map { it.long }
            val equipped = state.arr("formation").flatMap { e -> e.asObj.arr("assignments").map { it.asArr[1].long } }
            if (bag.any { it in equipped.toSet() } || !equipment.keys.containsAll(equipped)) {
                throw FortifyRejected("Equipment assignments and bag membership are inconsistent")
            }
            val gold = goldField(state) { FortifyRejected(it) }
            val targetUid = request.long("target_uid")
            val materials = request.arr("material_uids").map { it.long }
            var loaded: JObj? = null
            if (targetUid in equipment && materials.isNotEmpty() && materials.all { it in equipment }) {
                val t = equipment.getValue(targetUid)
                loaded = inputs.equipment(t[1].long to t[4].long, materials.map { equipment.getValue(it)[1].long to equipment.getValue(it)[4].long })
            }
            val plan = HeroFortify.planEquipmentFortify(request, equipment, bag, equipped, bitsOf(gold), loaded)
            val removed = plan.arr("material_uids").map { it.long }.toSet()
            state["equipment"] = JArr(state.arr("equipment").filter { it.asObj.arr("wire_values")[0].long !in removed }.mapTo(ArrayList<JValue>()) { r ->
                if (r.asObj.arr("wire_values")[0].long == targetUid) JObj(LinkedHashMap(r.asObj.map)).also { it["wire_values"] = plan.arr("after_target").deepCopyArr() } else r
            })
            state["bag_equipment_uids"] = plan.getValue("new_bag")
            gold["bits"] = plan.getValue("gold_after")
            val target = loaded!!.obj("target")
            val awarded = plan.arr("awarded_records")
            val detail = JObj(LinkedHashMap(identity.map))
            detail.putAll(jobj("character_id" to characterId, "target_uid" to targetUid, "material_uids" to plan["material_uids"],
                "target_template" to target["template"], "target_grade" to target["grade"],
                "material_templates" to awarded.map { it.asObj["template"] },
                "before_target" to plan["before_target"], "after_target" to plan["after_target"],
                "removed_materials" to plan["removed_materials"], "target_was_equipped" to (targetUid in equipped.toSet()),
                "awarded_exp" to plan["awarded_exp"], "awarded_records" to plan["awarded_records"],
                "gold_before" to (plan.int("gold_after") + BigInteger.valueOf(plan.long("gold_cost"))), "gold_cost" to plan["gold_cost"],
                "gold_after" to plan["gold_after"], "settlement" to plan["settlement"],
                "bag_count_before" to bag.size, "bag_count_after" to plan.arr("new_bag").size,
                "equipment_count_before" to equipment.size, "equipment_count_after" to state.arr("equipment").size,
                "catalog_inputs" to jobj("target" to without(target, "equipexp"),
                    "materials" to loaded.arr("materials").map { without(it.asObj, "equipexp") }),
                "deployment_policy_path" to policy.sourcePath, "deployment_policy_sha256" to policy.sha256,
                "contract" to "docs/GEAR_FORTIFY_CONTRACT.md"))
            val result = commitState(db, current, "fortify_equipment", detail)
            FortifyResult(result, plan, null)
        }
    }
}

private fun JArr.deepCopyArr(): JArr = JArr(this.toMutableList())

/** `_owned_items_view(current)`: {uid: wire values [uid, template, count]} across the three item stores. */
private fun ownedItemsView(current: StateStore.Current): LinkedHashMap<Long, JArr> {
    val view = LinkedHashMap<Long, JArr>()
    for (item in current.state.arr("items")) view[item.asObj.arr("wire_values")[0].long] = item.asObj.arr("wire_values")
    for (item in current.inventoryItems) view[item.asObj.arr("wire_values")[0].long] = item.asObj.arr("wire_values")
    for (item in current.acquiredItems) view[item.asObj.arr("wire_values")[0].long] = item.asObj.arr("wire_values")
    return view
}

/** `_owned_by_item(current)`: {item template: {uid, count}} across the item stores; one stack per template. */
private fun ownedByItem(current: StateStore.Current): LinkedHashMap<Long, JObj> {
    val byTemplate = LinkedHashMap<Long, JObj>()
    for (wire in ownedItemsView(current).values) {
        val template = wire[1].long
        if (template in byTemplate) throw ItemFortifyRejected("Multiple owned stacks of one Fortify item template")
        byTemplate[template] = jobj("uid" to wire[0], "count" to wire[2])
    }
    return byTemplate
}

/** `_apply_item_fortify(db, current, plan)`: the target's level / EXP (+ stats), the item stacks, the Gold. */
private fun StateStore.applyItemFortify(db: SqlConnection, current: StateStore.Current, plan: JObj) {
    val state = current.state
    val targetUid = plan.long("target_uid")
    when (plan.str("kind")) {
        "hero" -> state["heroes"] = JArr(state.arr("heroes").mapTo(ArrayList<JValue>()) {
            if (HeroFortify.heroUidOf(it.asArr).toLong() == targetUid) plan.getValue("after_target") else it
        })
        "gear" -> state["equipment"] = JArr(state.arr("equipment").mapTo(ArrayList<JValue>()) { r ->
            if (r.asObj.arr("wire_values")[0].long == targetUid) JObj(LinkedHashMap(r.asObj.map)).also { it["wire_values"] = plan.arr("after_target").deepCopyArr() } else r
        })
        else -> {
            // Equipped jewelry lives in formation blocks_40; rewrite only EXP / level.
            val loc = plan.obj("jewelry_location")
            val block = state.arr("formation")[loc.long("slot_index").toInt()].asObj.arr("blocks_40")[loc.long("block_index").toInt()].asObj
            block["raw_hex"] = io.github.okexodus.openknights.exact.JStr(ItemFortify.jewelryBlockWith(block.str("raw_hex").hexBytes(),
                plan.long("new_exp"), plan.long("new_level")).toHexString())
        }
    }
    for (change in plan.arr("item_changes")) {
        val (item, location) = ownedItem(current, change.asObj.long("uid"))
        setOwnedCount(db, current, item, location, change.asObj.long("remaining"))
    }
    goldField(state) { ItemFortifyRejected(it) }["bits"] = plan.getValue("gold_after")
}

/** `_item_fortify_preamble(current, character_id, policy)`: (Gold bits, Gold field, items by template). */
private fun itemFortifyPreamble(current: StateStore.Current, characterId: String, policy: FreshProfile.DeploymentPolicy): Triple<BigInteger, JObj, LinkedHashMap<Long, JObj>> {
    try { policy.validateCurrent(current, characterId) } catch (e: SecondaryTeam.Rejected) { throw ItemFortifyRejected(e.message ?: "") }
    val gold = goldField(current.state) { ItemFortifyRejected(it) }
    return Triple(bitsOf(gold), gold, ownedByItem(current))
}

/** `_bonus_policy_detail(plan, bonus_policy)`: the history fields of the labeled local bonus RNG policy (or its absence). */
private fun bonusPolicyDetail(plan: JObj, bonusPolicy: JObj?): JObj {
    val applied = Py.truthy(plan["bonus_policy_applied"])
    var info: JValue = JNull
    if (applied && bonusPolicy != null) {
        val document = (bonusPolicy["document"] ?: bonusPolicy) as JObj
        info = jobj("path" to bonusPolicy["path"], "sha256" to bonusPolicy["sha256"], "profile" to document["profile"],
            "class" to document["class"], "odds_per_10000" to document["odds_per_10000"], "fill_to_cap" to document["fill_to_cap"])
    }
    return jobj("evidence_class" to (plan["evidence_class"] ?: io.github.okexodus.openknights.exact.JStr("capture_observed_no_bonus")),
        "bonus_policy" to info, "rng_seed" to plan["rng_seed"],
        "bonus_tallies" to ((plan["bonus_tallies"] as? JObj)?.deepCopy() ?: JObj()),
        "topped_up" to ((plan["topped_up"] as? JObj)?.deepCopy() ?: JObj()),
        "total_cost_base" to plan["total_cost_base"])
}

/** `_consumed_detail(plan)`. */
private fun consumedDetail(plan: JObj): JArr = JArr(plan.arr("consumed").mapTo(ArrayList<JValue>()) { cv ->
    val c = cv.asObj
    jobj("item_id" to c["item_id"], "taken" to c["taken"], "remaining_stack" to c["remaining_stack"],
        "staged_quantity" to c["staged_quantity"], "topped_up" to (c["topped_up"] ?: JInt(0)),
        "tally" to ((c["tally"] as? JObj) ?: JObj()), "awarded" to c["awarded"])
})

private fun itemPreconditions(characterId: String, actor: String, reason: String, expectedRevision: Long?,
                              policy: FreshProfile.DeploymentPolicy?, inputs: ItemFortifyInputs?): Pair<JObj, FreshProfile.DeploymentPolicy> {
    SecondaryTeam.validateOwner(characterId)
    val identity = auditIdentity(actor, reason)
    validateRevision(expectedRevision)
    if (policy == null) throw ItemFortifyRejected("Item Fortify lacks explicit owner/source evidence for this save")
    if (inputs == null) throw ItemFortifyRejected("Item Fortify requires a catalog input loader")
    return identity to policy
}

private fun detailOf(identity: JObj, bonus: JObj, rest: JObj): JObj {
    val detail = JObj(LinkedHashMap(identity.map))
    detail.putAll(bonus)
    detail.putAll(rest)
    return detail
}

/**
 * `fortify_items_hero(request, character_id, policy, inputs, actor, reason, expected_revision, bonus_policy)`: hero
 * EXP-item Fortify (C91). `bonusPolicy` is the labeled local RNG / QoL policy or null; its label, hash, seed and tallies
 * are recorded in the history row.
 */
fun StateStore.fortifyItemsHero(request: JObj, characterId: String, policy: FreshProfile.DeploymentPolicy?, inputs: ItemFortifyInputs?,
                                actor: String, reason: String, expectedRevision: Long? = null, bonusPolicy: JObj? = null): FortifyResult {
    val (identity, bound) = itemPreconditions(characterId, actor, reason, expectedRevision, policy, inputs)
    connect().use { db ->
        return db.immediate {
            val current = read(db)
            if (expectedRevision != null && current.revision != expectedRevision) throw ItemFortifyRejected("State revision changed; reread before fortifying")
            val (gold, _, itemsByTemplate) = itemFortifyPreamble(current, characterId, bound)
            val heroes = SecondaryTeam.ownedHeroes(current.state)
            val targetUid = request.long("target_uid")
            val loaded = if (targetUid in heroes) inputs!!.hero(HeroFortify.heroUidOf(heroes.getValue(targetUid), 1).toLong()) else null
            val plan = ItemFortify.planHeroItemFortify(request, heroes, inputs!!.itemMap(), itemsByTemplate, gold, loaded, bonusPolicy)
            applyItemFortify(db, current, plan)
            val detail = detailOf(identity, bonusPolicyDetail(plan, bonusPolicy), jobj(
                "character_id" to characterId, "kind" to "hero", "target_uid" to targetUid,
                "template" to plan["template"], "level_before" to plan["level_before"],
                "new_level" to plan["new_level"], "new_exp" to plan["new_exp"], "reached_cap" to plan["reached_cap"],
                "new_stats" to plan["new_stats"], "total_awarded" to plan["total_awarded"],
                "profile_components" to plan["profile_components"],
                "profile_evidence_class" to plan["profile_evidence_class"],
                "stat_permille" to plan["stat_permille"], "full_grow" to plan["full_grow"],
                "total_staged_base" to plan["total_staged_base"], "gold_before" to (plan.int("gold_after") + BigInteger.valueOf(plan.long("gold_cost"))),
                "gold_cost" to plan["gold_cost"], "gold_after" to plan["gold_after"],
                "consumed" to consumedDetail(plan),
                "deployment_policy_path" to bound.sourcePath, "deployment_policy_sha256" to bound.sha256,
                "contract" to "docs/ITEM_FORTIFY_CONTRACT.md"))
            val result = commitState(db, current, "fortify_items_hero", detail)
            FortifyResult(result, plan, current.state.obj("subsystems").obj("game_activities"))
        }
    }
}

/** `fortify_items_gear(...)`: gear EXP-item Fortify (C93). Ported and proved; the route belongs to the gear group. */
fun StateStore.fortifyItemsGear(request: JObj, characterId: String, policy: FreshProfile.DeploymentPolicy?, inputs: ItemFortifyInputs?,
                                actor: String, reason: String, expectedRevision: Long? = null, bonusPolicy: JObj? = null): FortifyResult {
    val (identity, bound) = itemPreconditions(characterId, actor, reason, expectedRevision, policy, inputs)
    connect().use { db ->
        return db.immediate {
            val current = read(db)
            if (expectedRevision != null && current.revision != expectedRevision) throw ItemFortifyRejected("State revision changed; reread before fortifying")
            val (gold, _, itemsByTemplate) = itemFortifyPreamble(current, characterId, bound)
            val equipment = LinkedHashMap<Long, List<Long>>()
            for (record in current.state.arr("equipment")) {
                val wire = record.asObj.arr("wire_values")
                equipment[wire[0].long] = wire.map { it.long }
            }
            val targetUid = request.long("target_uid")
            val loaded = equipment[targetUid]?.let { inputs!!.gear(it[1], it[4]) }
            val plan = ItemFortify.planGearItemFortify(request, equipment, inputs!!.itemMap(), itemsByTemplate, gold, loaded, bonusPolicy)
            applyItemFortify(db, current, plan)
            val detail = detailOf(identity, bonusPolicyDetail(plan, bonusPolicy), jobj(
                "character_id" to characterId, "kind" to "gear", "target_uid" to targetUid,
                "template" to plan["template"], "grade" to plan["grade"], "level_before" to plan["level_before"],
                "new_level" to plan["new_level"], "new_exp" to plan["new_exp"], "reached_cap" to plan["reached_cap"],
                "total_awarded" to plan["total_awarded"], "total_staged_base" to plan["total_staged_base"],
                "gold_before" to (plan.int("gold_after") + BigInteger.valueOf(plan.long("gold_cost"))), "gold_cost" to plan["gold_cost"],
                "gold_after" to plan["gold_after"],
                "consumed" to consumedDetail(plan),
                "deployment_policy_path" to bound.sourcePath, "deployment_policy_sha256" to bound.sha256,
                "contract" to "docs/ITEM_FORTIFY_CONTRACT.md"))
            val result = commitState(db, current, "fortify_items_gear", detail)
            FortifyResult(result, plan, current.state.obj("subsystems").obj("game_activities"))
        }
    }
}

/**
 * `fortify_items_jewelry(...)`: equipped-jewelry EXP-item Fortify (C2641). The target is a formation blocks_40 jewelry;
 * only its EXP / level bytes change and the op-3080 record is emitted. Unequipped (opcode-3072) jewelry is refused.
 */
fun StateStore.fortifyItemsJewelry(request: JObj, characterId: String, policy: FreshProfile.DeploymentPolicy?, inputs: ItemFortifyInputs?,
                                   actor: String, reason: String, expectedRevision: Long? = null, bonusPolicy: JObj? = null): FortifyResult {
    val (identity, bound) = itemPreconditions(characterId, actor, reason, expectedRevision, policy, inputs)
    connect().use { db ->
        return db.immediate {
            val current = read(db)
            if (expectedRevision != null && current.revision != expectedRevision) throw ItemFortifyRejected("State revision changed; reread before fortifying")
            val (gold, _, itemsByTemplate) = itemFortifyPreamble(current, characterId, bound)
            val view = try {
                ItemFortify.jewelryView(current.state.arr("formation"))
            } catch (e: IllegalArgumentException) {
                throw ItemFortifyRejected(e.message ?: "")
            }
            val targetUid = request.long("target_uid")
            val jewelry = LinkedHashMap<Long, List<Long>>().also { m -> view.forEach { (uid, v) -> m[uid] = v.record } }
            val loaded = view[targetUid]?.let { inputs!!.jewelry(it.record[1], it.record[4]) }
            val plan = ItemFortify.planJewelryItemFortify(request, jewelry, inputs!!.itemMap(), itemsByTemplate, gold, loaded, bonusPolicy)
            val at = view.getValue(targetUid)
            plan["jewelry_location"] = jobj("slot_index" to at.slotIndex, "block_index" to at.blockIndex, "block_id" to at.blockId)
            applyItemFortify(db, current, plan)
            val detail = detailOf(identity, bonusPolicyDetail(plan, bonusPolicy), jobj(
                "character_id" to characterId, "kind" to "jewelry", "target_uid" to targetUid,
                "template" to plan["template"], "grade" to plan["grade"], "cap" to loaded!!["cap"],
                "cap_row" to loaded["cap_row"], "jewelry_location" to plan["jewelry_location"],
                "level_before" to plan["level_before"], "new_level" to plan["new_level"], "new_exp" to plan["new_exp"],
                "reached_cap" to plan["reached_cap"], "total_awarded" to plan["total_awarded"],
                "total_staged_base" to plan["total_staged_base"], "gold_before" to (plan.int("gold_after") + BigInteger.valueOf(plan.long("gold_cost"))),
                "gold_cost" to plan["gold_cost"], "gold_after" to plan["gold_after"],
                "consumed" to consumedDetail(plan),
                "deployment_policy_path" to bound.sourcePath, "deployment_policy_sha256" to bound.sha256,
                "contract" to "docs/ITEM_FORTIFY_CONTRACT.md"))
            val result = commitState(db, current, "fortify_items_jewelry", detail)
            FortifyResult(result, plan, current.state.obj("subsystems").obj("game_activities"))
        }
    }
}
