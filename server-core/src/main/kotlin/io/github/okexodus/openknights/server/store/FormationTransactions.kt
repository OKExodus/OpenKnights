package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.jvalue
import io.github.okexodus.openknights.server.game.EquipFormation
import io.github.okexodus.openknights.server.game.EquipFormation.FormationRejected
import io.github.okexodus.openknights.server.game.FormationInputs
import io.github.okexodus.openknights.server.game.FreshProfile
import io.github.okexodus.openknights.server.game.SecondaryTeam
import io.github.okexodus.openknights.server.game.long

/**
 * The equipment / jewelry / runes / main-formation transaction of `state_store.py` (`formation_transaction`;
 * docs/EQUIP_FORMATION_CONTRACT.md): one atomic revision (BEGIN IMMEDIATE over the owned view), the planner's refusals
 * before any write, the history detail keys in the reference's order.
 */

/** What the transaction returns: `{revision, timestamp_utc, **detail}`, the plan and the action. */
class FormationResult(val result: JObj, val plan: EquipFormation.FormationPlan, val action: String) {
    operator fun get(key: String): JValue? = result[key]
}

/** The owned heroes as the transaction reads them: `{values[0]: values}`, `values = {field id: bits or None}`. */
fun formationHeroes(state: JObj): LinkedHashMap<Long, Map<Long, JValue?>> {
    val heroes = LinkedHashMap<Long, Map<Long, JValue?>>()
    for (fields in state.arr("heroes")) {
        val values = LinkedHashMap<Long, JValue?>()
        for (f in fields.asArr) values[f.asObj.long("id")] = f.asObj.obj("value")["bits"]
        heroes[values.getValue(0L)!!.long] = values
    }
    return heroes
}

/** The planners' view of a save (the transaction's `view` dict): the state's own formation, bench, bag and gem lists. */
fun formationView(state: JObj, heroes: Map<Long, Map<Long, JValue?>>, gemTypes: Map<Long, Long>, jewelList: JArr): EquipFormation.View {
    val gems = state.obj("subsystems").obj("gems")
    val equipment = LinkedHashMap<Long, JArr>()
    for (r in state.arr("equipment")) {
        val values = r.asObj.arr("wire_values")
        equipment[values[0].long] = JArr(ArrayList(values))
    }
    return EquipFormation.View(state.arr("formation"), state.getValue("captain_slot"), state.arr("offline_hero_uids"),
        state.arr("bag_equipment_uids"), equipment, heroes, jobj("count" to gems.getValue("count"), "entries" to gems.getValue("entries")),
        gemTypes, jewelList)
}

/**
 * `formation_transaction(opcode, request, character_id, policy, inputs, actor, reason, expected_revision,
 * served_jewel_list, served_jewel_source)`: one of C67 / C35 / C65 / C79 / C2625 / C1217 / C1219 / C1221 as one atomic
 * revision. [servedJewelList] is the S3072 payload the session served; the first jewelry transaction seeds the stored
 * list from exactly that payload (provenance recorded).
 */
fun StateStore.formationTransaction(opcode: Int, request: JObj, characterId: String, policy: FreshProfile.DeploymentPolicy?,
                                    inputs: FormationInputs?, actor: String, reason: String, expectedRevision: Long? = null,
                                    servedJewelList: ByteArray? = null, servedJewelSource: String? = null): FormationResult {
    SecondaryTeam.validateOwner(characterId)
    if (actor.isBlank() || reason.isBlank()) throw IllegalArgumentException("Actor and reason must be nonempty text")
    val identity = jobj("actor" to actor, "reason" to reason)
    if (expectedRevision != null && expectedRevision < 1) throw IllegalArgumentException("Expected revision must be a positive integer")
    if (policy == null) throw FormationRejected("Formation change lacks explicit owner/source evidence for this save")
    if (inputs == null) throw FormationRejected("Formation changes require a catalog input loader")
    connect().use { db ->
        return db.immediate {
            val current = read(db)
            if (expectedRevision != null && current.revision != expectedRevision) throw FormationRejected("State revision changed; reread before changing the formation")
            try { policy.validateCurrent(current, characterId) } catch (e: SecondaryTeam.Rejected) { throw FormationRejected(e.message ?: "") }
            val state = current.state
            val heroes = formationHeroes(state)
            var jewelDoc = current.jewelryList?.obj("document")
            var seeded = false
            if (opcode == EquipFormation.JEWEL_OPCODE && jewelDoc == null) {
                if (servedJewelList == null) throw FormationRejected("No served jewelry list to seed the stored list from")
                jewelDoc = EquipFormation.seedJewelryDocument(servedJewelList, servedJewelSource ?: "served")
                seeded = true
            }
            val view = formationView(state, heroes, inputs.gemTypes(), jewelDoc?.get("entries") as? JArr ?: JArr())
            val excluded = LinkedHashSet(policy.explorationUids).also { it.addAll(policy.miningUids) }
            val plan = when (opcode) {
                EquipFormation.LINEUP_OPCODE -> {
                    val leaders = heroes.filter { (_, values) -> inputs.isLeader(values[1L]) }.keys
                    EquipFormation.planLineup(request, view, excludedUids = excluded, leaderUids = leaders)
                }
                EquipFormation.POSITION_OPCODE -> EquipFormation.planPosition(request, view)
                EquipFormation.CAPTAIN_OPCODE -> EquipFormation.planCaptain(request, view)
                EquipFormation.EQUIP_OPCODE -> EquipFormation.planEquip(request, view, inputs::equipPosition)
                EquipFormation.JEWEL_OPCODE -> EquipFormation.planJewel(request, view, inputs::jewelPosition)
                EquipFormation.RUNE_EQUIP_OPCODE -> EquipFormation.planRuneEquip(request, view)
                EquipFormation.RUNE_UNEQUIP_OPCODE -> EquipFormation.planRuneUnequip(request, view)
                EquipFormation.RUNE_COMBINE_OPCODE -> EquipFormation.planRuneCombine(request, view, inputs.gemRows())
                else -> throw FormationRejected("Unsupported formation opcode")
            }
            val new = plan.view
            state["formation"] = new.formation
            state["captain_slot"] = new.captainSlot
            state["offline_hero_uids"] = new.offlineHeroUids
            state["bag_equipment_uids"] = new.bagEquipmentUids
            val gems = state.obj("subsystems").obj("gems")
            gems["count"] = new.gems.getValue("count")
            gems["entries"] = new.gems.getValue("entries")
            var jewelChecksum: String? = null
            if (opcode == EquipFormation.JEWEL_OPCODE) {
                val doc = JObj(LinkedHashMap(jewelDoc!!.map)).also { it["entries"] = new.jewelList }
                jewelChecksum = EquipFormation.writeJewelryList(db, doc)
            }
            val action = mapOf(EquipFormation.LINEUP_OPCODE to "set_lineup", EquipFormation.POSITION_OPCODE to "set_position",
                EquipFormation.CAPTAIN_OPCODE to "set_captain", EquipFormation.EQUIP_OPCODE to "set_equip",
                EquipFormation.JEWEL_OPCODE to "set_jewel", EquipFormation.RUNE_EQUIP_OPCODE to "rune_equip",
                EquipFormation.RUNE_UNEQUIP_OPCODE to "rune_unequip", EquipFormation.RUNE_COMBINE_OPCODE to "rune_combine").getValue(opcode)
            val detail = JObj(LinkedHashMap(identity.map))
            detail["character_id"] = JStr(characterId)
            detail["opcode"] = JInt(opcode)
            detail["request"] = request
            for ((k, v) in plan.data) detail[k] = v
            detail["jewelry_list_seeded"] = JBool(seeded)
            detail["jewelry_list_sha256"] = jvalue(jewelChecksum)
            detail["reply_opcodes"] = JArr(plan.packets.mapTo(ArrayList()) { JInt(it.first) })
            detail["deployment_policy_path"] = JStr(policy.sourcePath)
            detail["deployment_policy_sha256"] = JStr(policy.sha256)
            detail["contract"] = JStr("docs/EQUIP_FORMATION_CONTRACT.md")
            FormationResult(commitState(db, current, action, detail), plan, action)
        }
    }
}
