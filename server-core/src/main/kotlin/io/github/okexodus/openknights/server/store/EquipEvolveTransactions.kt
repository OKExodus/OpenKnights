package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.server.game.EquipEvolve
import io.github.okexodus.openknights.server.game.EquipEvolve.EquipEvolveRejected
import io.github.okexodus.openknights.server.game.EquipEvolveInputs
import io.github.okexodus.openknights.server.game.FreshProfile
import io.github.okexodus.openknights.server.game.ItemFortify
import io.github.okexodus.openknights.server.game.SecondaryTeam
import java.math.BigInteger

/**
 * The gear / jewelry evolve transactions of `state_store.py` (`evolve_gear` C2049, `evolve_jewelry` C2629): one atomic
 * revision each over the owned view, the planner's refusals before any write, the history detail in the reference's
 * key order. Level, EXP and every reference (bag, formation assignment, the other block bytes) stay unchanged.
 */

/** What an evolve transaction returns: `{revision, timestamp_utc, **detail}` and the plan. */
class EquipEvolveResult(val result: JObj, val plan: JObj)

private fun identityOf(actor: String, reason: String): JObj {
    if (actor.isBlank() || reason.isBlank()) throw IllegalArgumentException("Actor and reason must be nonempty text")
    return jobj("actor" to actor, "reason" to reason)
}

private fun checkRevision(expectedRevision: Long?) {
    if (expectedRevision != null && expectedRevision < 1) throw IllegalArgumentException("Expected revision must be a positive integer")
}

/** `_evolve_preamble`: the deployment check, then (the one 64-bit Gold field, the role level or null). */
private fun evolvePreamble(current: StateStore.Current, characterId: String, policy: FreshProfile.DeploymentPolicy): Pair<JObj, Long?> {
    try {
        policy.validateCurrent(current, characterId)
    } catch (e: SecondaryTeam.Rejected) {
        throw EquipEvolveRejected(e.message ?: "")
    }
    val roles = current.state.arr("role_properties").map { it.asObj }
    val gold = roles.filter { (it["id"] as? JInt)?.value == BigInteger.valueOf(6) }.map { it.obj("value") }
    if (gold.size != 1 || (gold[0]["tag"] as? JInt)?.value?.toInt() !in setOf(7, 8)) throw EquipEvolveRejected("Expected one 64-bit Gold property")
    val levels = roles.filter { (it["id"] as? JInt)?.value == BigInteger.valueOf(3) }.map { it.obj("value")["bits"] }
    val roleLevel = if (levels.size == 1 && levels[0] is JInt) (levels[0] as JInt).value.toLong() else null
    return gold[0] to roleLevel
}

/** `evolve_gear`: raise the grade byte, consume the row's materials and Gold (one atomic revision). */
fun StateStore.evolveGear(request: JObj, characterId: String, policy: FreshProfile.DeploymentPolicy?, inputs: EquipEvolveInputs?,
                          actor: String, reason: String, expectedRevision: Long? = null): EquipEvolveResult {
    SecondaryTeam.validateOwner(characterId)
    val identity = identityOf(actor, reason)
    checkRevision(expectedRevision)
    if (policy == null) throw EquipEvolveRejected("Gear evolve lacks explicit owner/source evidence for this save")
    if (inputs == null) throw EquipEvolveRejected("Gear evolve requires a catalog input loader")
    connect().use { db ->
        return db.immediate {
            val current = read(db)
            if (expectedRevision != null && current.revision != expectedRevision) throw EquipEvolveRejected("State revision changed; reread before evolving")
            val (goldField, roleLevel) = evolvePreamble(current, characterId, policy)
            val state = current.state
            val equipment = LinkedHashMap<Long, List<Long>>()
            for (record in state.arr("equipment")) {
                val values = record.asObj.arr("wire_values").map { (it as JInt).value.toLong() }
                if (values[0] in equipment) throw EquipEvolveRejected("Duplicate owned equipment UID")
                equipment[values[0]] = values
            }
            val equipped = state.arr("formation").flatMap { e -> e.asObj.arr("assignments").map { (it.asArr[1] as JInt).value.toLong() } }
            val targetUid = request.long("target_uid")
            var loaded: JObj? = null
            equipment[targetUid]?.let { owned ->
                loaded = try {
                    inputs.gear(owned[1], owned[4])
                } catch (e: IllegalArgumentException) {
                    throw EquipEvolveRejected("Gear has no usable evolve configuration: ${e.message}", 6011)
                }
            }
            val plan = EquipEvolve.planGearEvolve(request, equipment, itemWireView(current), (goldField["bits"] as JInt).value,
                roleLevel, loaded, equipped)
            val after = plan.arr("after_record")
            val records = state.arr("equipment")
            for (i in records.indices) {
                val record = records[i].asObj
                if ((record.arr("wire_values")[0] as JInt).value.toLong() == targetUid) {
                    records[i] = JObj(LinkedHashMap(record.map)).also { it["wire_values"] = JArr(after.toMutableList()) }
                }
            }
            for (change in plan.arr("item_changes")) applyItemChange(db, current, change.asObj)
            goldField["bits"] = plan["gold_after"]!!
            val goldAfter = (plan["gold_after"] as JInt).value
            val detail = jobj("actor" to identity["actor"], "reason" to identity["reason"], "evidence_class" to plan["evidence_class"],
                "observed_row" to plan["observed_row"], "character_id" to characterId, "kind" to "gear", "target_uid" to targetUid,
                "before_record" to plan["before_record"], "after_record" to plan["after_record"],
                "target_was_equipped" to plan["target_was_equipped"], "row_key" to plan["row_key"],
                "next_row_key" to plan["next_row_key"], "gates" to plan["gates"],
                "cap_before" to plan["cap_before"], "cap_after" to plan["cap_after"],
                "potential_before" to plan["potential_before"], "potential_after" to plan["potential_after"],
                "value_before" to plan["value_before"], "value_after" to plan["value_after"],
                "value_increase" to plan["value_increase"], "super_flag_popup" to plan["super_flag_popup"],
                "materials" to EquipEvolve.materials(plan),
                "gold_before" to goldAfter + BigInteger.valueOf(plan.long("gold_cost")), "gold_cost" to plan["gold_cost"],
                "gold_after" to plan["gold_after"], "role_level" to roleLevel,
                "deployment_policy_path" to policy.sourcePath, "deployment_policy_sha256" to policy.sha256,
                "contract" to "docs/EQUIP_EVOLVE_CONTRACT.md")
            EquipEvolveResult(commitState(db, current, "evolve_gear", detail), plan)
        }
    }
}

/**
 * `evolve_jewelry`: raise byte 16 of the equipped jewel's formation block and consume the row's four materials (no
 * Gold). Unequipped (opcode-3072) jewelry is not modelled and is refused.
 */
fun StateStore.evolveJewelry(request: JObj, characterId: String, policy: FreshProfile.DeploymentPolicy?, inputs: EquipEvolveInputs?,
                             actor: String, reason: String, expectedRevision: Long? = null, unequippedUids: List<Long> = emptyList()): EquipEvolveResult {
    SecondaryTeam.validateOwner(characterId)
    val identity = identityOf(actor, reason)
    checkRevision(expectedRevision)
    if (policy == null) throw EquipEvolveRejected("Jewelry evolve lacks explicit owner/source evidence for this save")
    if (inputs == null) throw EquipEvolveRejected("Jewelry evolve requires a catalog input loader")
    connect().use { db ->
        return db.immediate {
            val current = read(db)
            if (expectedRevision != null && current.revision != expectedRevision) throw EquipEvolveRejected("State revision changed; reread before evolving")
            val (_, roleLevel) = evolvePreamble(current, characterId, policy)
            val view = try {
                ItemFortify.jewelryView(current.state.arr("formation"))
            } catch (e: IllegalArgumentException) {
                throw EquipEvolveRejected(e.message ?: "")
            }
            val targetUid = request.long("target_uid")
            var loaded: JObj? = null
            view[targetUid]?.let { entry ->
                loaded = try {
                    inputs.jewelry(entry.record[1], entry.record[4])
                } catch (e: IllegalArgumentException) {
                    throw EquipEvolveRejected("Jewelry has no usable evolve configuration: ${e.message}", 69508)
                }
            }
            val plan = EquipEvolve.planJewelEvolve(request, view.mapValues { it.value.record }, itemWireView(current), roleLevel, loaded,
                unequippedUids)
            val location = view.getValue(targetUid)
            val block = current.state.arr("formation")[location.slotIndex].asObj.arr("blocks_40")[location.blockIndex].asObj
            val grade = plan.arr("after_record")[4].let { (it as JInt).value.toLong() }
            block["raw_hex"] = JStr(EquipEvolve.jewelBlockWithGrade((block["raw_hex"] as JStr).value.hexBytes(), grade).toHexString())
            for (change in plan.arr("item_changes")) applyItemChange(db, current, change.asObj)
            val detail = jobj("actor" to identity["actor"], "reason" to identity["reason"], "evidence_class" to plan["evidence_class"],
                "observed_row" to plan["observed_row"], "character_id" to characterId, "kind" to "jewelry", "target_uid" to targetUid,
                "jewelry_location" to jobj("slot_index" to location.slotIndex, "block_index" to location.blockIndex, "block_id" to location.blockId),
                "before_record" to plan["before_record"], "after_record" to plan["after_record"],
                "row_key" to plan["row_key"], "next_row_key" to plan["next_row_key"], "gates" to plan["gates"],
                "cap_before" to plan["cap_before"], "cap_after" to plan["cap_after"],
                "potential_before" to plan["potential_before"], "potential_after" to plan["potential_after"],
                "value_before" to plan["value_before"], "value_after" to plan["value_after"],
                "value_increase" to plan["value_increase"],
                "materials" to EquipEvolve.materials(plan),
                "role_level" to roleLevel,
                "deployment_policy_path" to policy.sourcePath, "deployment_policy_sha256" to policy.sha256,
                "contract" to "docs/EQUIP_EVOLVE_CONTRACT.md")
            EquipEvolveResult(commitState(db, current, "evolve_jewelry", detail), plan)
        }
    }
}
