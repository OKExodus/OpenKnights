package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.server.game.EvolutionInputs
import io.github.okexodus.openknights.server.game.FreshProfile
import io.github.okexodus.openknights.server.game.GodSkills
import io.github.okexodus.openknights.server.game.HeroAscension
import io.github.okexodus.openknights.server.game.HeroCardInputs
import io.github.okexodus.openknights.server.game.HeroEvolution
import io.github.okexodus.openknights.server.game.HeroFortify
import io.github.okexodus.openknights.server.game.HeroPowerUp
import io.github.okexodus.openknights.server.game.Plan
import io.github.okexodus.openknights.server.game.PyValues
import io.github.okexodus.openknights.server.game.SecondaryTeam
import java.math.BigInteger

/*
 * The hero evolution and hero-card transactions of `state_store.py` (C71, C2083, C2497, C3907, C3713, C3721): each one
 * atomic revision with its history row; every refusal happens before any write. Each returns
 * (`{revision, timestamp_utc, **detail, ...}`, plan).
 */

/** `audit_identity(actor, reason)`. */
private fun auditIdentity(actor: String, reason: String): JObj {
    if (actor.isBlank() || reason.isBlank()) throw PyValues.ValueError("Actor and reason must be nonempty text")
    return jobj("actor" to actor, "reason" to reason)
}

/** `validate_revision(expected_revision)`. */
private fun validateRevision(expectedRevision: Long?) {
    if (expectedRevision != null && expectedRevision < 1) throw PyValues.ValueError("Expected revision must be a positive integer")
}

private fun uidOf(item: JValue): Long = item.asObj.arr("wire_values")[0].let { (it as JInt).value.toLong() }

/** `_owned_items_view(current)`: one view {uid: {"wire_values": [uid, template, count]}} across the item stores. */
fun StateStore.ownedItemsView(current: StateStore.Current): LinkedHashMap<Long, JObj> {
    val view = LinkedHashMap<Long, JObj>()
    for (item in current.state.arr("items")) view[uidOf(item)] = jobj("wire_values" to item.asObj.arr("wire_values"))
    for (item in current.inventoryItems) view[uidOf(item)] = jobj("wire_values" to item.asObj.arr("wire_values"))
    for (item in current.acquiredItems) view[uidOf(item)] = jobj("wire_values" to item.asObj.arr("wire_values"))
    return view
}

/** `_item_wire_view(current)`: {uid: [uid, template, count]} across the item stores (copies). */
fun StateStore.itemWireView(current: StateStore.Current): LinkedHashMap<Long, JArr> {
    val view = LinkedHashMap<Long, JArr>()
    for ((uid, record) in ownedItemsView(current)) view[uid] = JArr(record.arr("wire_values").take(3).toMutableList())
    return view
}

/** `_apply_item_change(db, current, change)`: one stack's new count in its store. */
fun StateStore.applyItemChange(db: SqlConnection, current: StateStore.Current, change: JObj) {
    val (item, location) = ownedItem(current, change.long("uid"))
    setOwnedCount(db, current, item, location, change.long("remaining"))
}

/** `_replace_hero(current, target_uid, after_fields)`. */
fun replaceHero(current: StateStore.Current, targetUid: Long, afterFields: JArr) {
    val state = current.state
    state["heroes"] = JArr(state.arr("heroes").mapTo(ArrayList()) { f ->
        if (HeroFortify.heroUidOf(f.asArr) == BigInteger.valueOf(targetUid)) afterFields else f
    })
}

private fun goldFields(state: JObj): List<JObj> =
    state.arr("role_properties").map { it.asObj }.filter { it["id"] == JInt(6) }.map { it.obj("value") }

/** `_apply_evolution(db, current, plan)`: hero fields, material items, Gold debit. */
fun StateStore.applyEvolution(db: SqlConnection, current: StateStore.Current, plan: Plan) {
    replaceHero(current, plan.data.long("target_uid"), plan.data.arr("after_target"))
    for (change in plan.data.arr("item_changes")) {
        val (item, location) = ownedItem(current, change.asObj.long("uid"))
        setOwnedCount(db, current, item, location, change.asObj.long("remaining"))
    }
    goldFields(current.state)[0]["bits"] = plan.data.getValue("gold_after")
}

/** The owned view both evolution paths (and Ascension) plan on (`_evolution_preamble`). */
class EvolutionView(val heroes: LinkedHashMap<Long, JArr>, val items: LinkedHashMap<Long, JObj>, val gold: BigInteger?, val roleLevel: BigInteger?)

/** `_evolution_preamble(current, character_id, policy)`: the shared owned-view checks. */
fun StateStore.evolutionPreamble(current: StateStore.Current, characterId: String, policy: FreshProfile.DeploymentPolicy): EvolutionView {
    try {
        policy.validateCurrent(current, characterId)
    } catch (e: SecondaryTeam.Rejected) {
        throw HeroEvolution.EvolutionRejected(e.message ?: "")
    }
    val state = current.state
    val heroes = SecondaryTeam.ownedHeroes(state)
    val gold = goldFields(state)
    if (gold.size != 1 || (gold[0]["tag"] as? JInt)?.value?.toInt() !in setOf(7, 8)) throw HeroEvolution.EvolutionRejected("Expected one 64-bit Gold property")
    val levels = state.arr("role_properties").map { it.asObj }.filter { it["id"] == JInt(3) }.map { it.obj("value")["bits"] }
    val roleLevel = if (levels.size == 1) (levels[0] as? JInt)?.value else null
    return EvolutionView(heroes, ownedItemsView(current), (gold[0]["bits"] as? JInt)?.value, roleLevel)
}

/** `_test_policy_detail(test_policy)`. */
fun testPolicyDetail(testPolicy: JObj?): JObj {
    if (testPolicy == null) return jobj("evolution_test_policy" to null)
    return jobj("evolution_test_policy" to jobj("path" to testPolicy["path"], "sha256" to testPolicy["sha256"],
        "profile" to testPolicy.obj("document")["profile"], "class" to testPolicy.obj("document")["class"]))
}

private fun evolutionDetail(identity: JObj, testPolicy: JObj?, plan: Plan, characterId: String, kind: String, targetUid: Long,
                            heroes: Map<Long, JArr>, loaded: JObj, policy: FreshProfile.DeploymentPolicy, leader: Boolean): JObj {
    val d = plan.data
    val detail = JObj(LinkedHashMap(identity.map))
    detail.putAll(testPolicyDetail(testPolicy))
    for ((k, v) in listOf("evidence_class" to d["evidence_class"], "character_id" to characterId, "kind" to kind,
        "target_uid" to targetUid, "old_template" to d["old_template"], "new_template" to d["new_template"], "new_grade" to d["new_grade"],
        "level" to d["level"], "new_grow" to d["grow"], "wire_grow" to d["wire_grow"], "new_stats" to d["stats"],
        "stat_permille" to d["stat_permille"], "profile_components" to d["profile_components"],
        "profile_evidence_class" to d["profile_evidence_class"], "grow_recomputed_from_catalog" to d["grow_recomputed_from_catalog"],
        "changed_fields" to d["changed_fields"], "before_target" to heroes.getValue(d.long("target_uid")), "after_target" to d["after_target"])) {
        detail[k] = io.github.okexodus.openknights.exact.jvalue(v)
    }
    if (leader) {
        detail["leader_field_a"] = d.getValue("leader_field_a")
        detail["leader_field_b"] = d.getValue("leader_field_b")
    }
    detail["materials"] = d.getValue("item_changes")
    detail["gold_before"] = JInt(d.int("gold_after") + d.int("gold_cost"))
    detail["gold_cost"] = d.getValue("gold_cost")
    detail["gold_after"] = d.getValue("gold_after")
    detail["catalog_inputs"] = JObj().also { o -> loaded.forEach { (k, v) -> if (k != "sources") o[k] = v } }
    detail["deployment_policy_path"] = io.github.okexodus.openknights.exact.JStr(policy.sourcePath)
    detail["deployment_policy_sha256"] = io.github.okexodus.openknights.exact.JStr(policy.sha256)
    detail["contract"] = io.github.okexodus.openknights.exact.JStr("docs/EVOLUTION_CONTRACT.md")
    return detail
}

private fun evolutionResult(result: JObj, plan: Plan, targetUid: Long, current: StateStore.Current): JObj {
    result["target_uid"] = JInt(targetUid)
    result["new_template"] = plan.data.getValue("new_template")
    result["gold_after"] = plan.data.getValue("gold_after")
    result["activity_section"] = current.state.obj("subsystems").obj("game_activities")
    return result
}

/**
 * Ordinary hero evolution (C71, docs/EVOLUTION_CONTRACT.md): raise the packed tier, recompute growth / stats,
 * consume the config materials and Gold; level and EXP unchanged.
 */
fun StateStore.evolveHero(request: JObj, characterId: String, policy: FreshProfile.DeploymentPolicy?, inputs: EvolutionInputs?,
                          actor: String, reason: String, expectedRevision: Long? = null, testPolicy: JObj? = null): Pair<JObj, Plan> {
    SecondaryTeam.validateOwner(characterId)
    val identity = auditIdentity(actor, reason)
    validateRevision(expectedRevision)
    if (policy == null) throw HeroEvolution.EvolutionRejected("Evolution lacks explicit owner/source evidence for this save")
    if (inputs == null) throw HeroEvolution.EvolutionRejected("Evolution requires a catalog input loader")
    connect().use { conn ->
        return conn.immediate { db ->
            val current = read(db)
            if (expectedRevision != null && current.revision != expectedRevision) throw HeroEvolution.EvolutionRejected("State revision changed; reread before evolving")
            val view = evolutionPreamble(current, characterId, policy)
            val targetUid = request.long("target_uid")
            val loaded = view.heroes[targetUid]?.let { inputs(HeroFortify.heroUidOf(it, 1).longValueExact()) }
            val plan = HeroEvolution.planOrdinaryEvolution(request, view.heroes, view.gold, view.items, loaded, testPolicy, view.roleLevel)
            applyEvolution(db, current, plan)
            val detail = evolutionDetail(identity, testPolicy, plan, characterId, "ordinary", targetUid, view.heroes, loaded!!, policy, leader = false)
            evolutionResult(commitState(db, current, "evolve_hero", detail), plan, targetUid, current) to plan
        }
    }
}

/**
 * Leader hero evolution (C2083): the empty request carries no target; the one owned leader-class hero (config field
 * 143 != 0) is resolved here.
 */
fun StateStore.evolveLeaderHero(request: JObj, characterId: String, policy: FreshProfile.DeploymentPolicy?, inputs: EvolutionInputs?,
                                actor: String, reason: String, expectedRevision: Long? = null, testPolicy: JObj? = null): Pair<JObj, Plan> {
    SecondaryTeam.validateOwner(characterId)
    val identity = auditIdentity(actor, reason)
    validateRevision(expectedRevision)
    if (policy == null) throw HeroEvolution.EvolutionRejected("Leader evolution lacks explicit owner/source evidence for this save")
    if (inputs == null) throw HeroEvolution.EvolutionRejected("Leader evolution requires a catalog input loader")
    connect().use { conn ->
        return conn.immediate { db ->
            val current = read(db)
            if (expectedRevision != null && current.revision != expectedRevision) throw HeroEvolution.EvolutionRejected("State revision changed; reread before evolving")
            val view = evolutionPreamble(current, characterId, policy)
            // The single owned leader-class hero.
            val leaders = ArrayList<Long>()
            for ((uid, fields) in view.heroes) {
                val info = try {
                    inputs(HeroFortify.heroUidOf(fields, 1).longValueExact())
                } catch (e: IllegalArgumentException) {
                    continue
                }
                if (io.github.okexodus.openknights.server.game.Py.truthy(info["is_leader"])) leaders.add(uid)
            }
            if (leaders.size != 1) throw HeroEvolution.EvolutionRejected("Expected exactly one owned leader-class hero")
            val leaderUid = leaders[0]
            val loaded = inputs(HeroFortify.heroUidOf(view.heroes.getValue(leaderUid), 1).longValueExact())
            val plan = HeroEvolution.planLeaderEvolution(request, view.heroes, leaderUid, view.gold, view.items, loaded, testPolicy, view.roleLevel)
            applyEvolution(db, current, plan)
            val detail = evolutionDetail(identity, testPolicy, plan, characterId, "leader", leaderUid, view.heroes, loaded, policy, leader = true)
            evolutionResult(commitState(db, current, "evolve_leader_hero", detail), plan, leaderUid, current) to plan
        }
    }
}

/** `_hero_card_preamble(current, character_id, policy, rejected)`. */
private fun heroCardPreamble(current: StateStore.Current, characterId: String, policy: FreshProfile.DeploymentPolicy, rejected: (String) -> Exception) {
    try {
        policy.validateCurrent(current, characterId)
    } catch (e: SecondaryTeam.Rejected) {
        throw rejected(e.message ?: "")
    }
}

private fun policyTail(detail: JObj, policy: FreshProfile.DeploymentPolicy, contract: String) {
    detail["deployment_policy_path"] = io.github.okexodus.openknights.exact.JStr(policy.sourcePath)
    detail["deployment_policy_sha256"] = io.github.okexodus.openknights.exact.JStr(policy.sha256)
    detail["contract"] = io.github.okexodus.openknights.exact.JStr(contract)
}

/** `{k: v for k, v in change.items() if k != "packet"}` — the plan's change already carries no packet. */
private fun changeDetail(change: JObj): JObj = JObj(LinkedHashMap(change.map))

/** Astral Power press (C2497, docs/ASTRAL_POWER_CONTRACT.md). */
fun StateStore.astralUpgrade(request: JObj, characterId: String, policy: FreshProfile.DeploymentPolicy?, inputs: HeroCardInputs?,
                             actor: String, reason: String, expectedRevision: Long? = null): Pair<JObj, Plan> {
    SecondaryTeam.validateOwner(characterId)
    val identity = auditIdentity(actor, reason)
    validateRevision(expectedRevision)
    if (policy == null) throw GodSkills.GodSkillRejected("Astral Power lacks explicit owner/source evidence for this save")
    if (inputs == null) throw GodSkills.GodSkillRejected("Astral Power requires a catalog input loader")
    connect().use { conn ->
        return conn.immediate { db ->
            val current = read(db)
            if (expectedRevision != null && current.revision != expectedRevision) throw GodSkills.GodSkillRejected("State revision changed; reread before upgrading")
            heroCardPreamble(current, characterId, policy) { GodSkills.GodSkillRejected(it) }
            val god = current.godSkills ?: throw GodSkills.GodSkillRejected("This save has no imported god-skill state")
            val owned = SecondaryTeam.ownedHeroes(current.state)
            val items = itemWireView(current)
            val rows = inputs.astral().obj("rows")
            val plan = GodSkills.planUpgrade(request, god.obj("document"), owned.keys, items, rows)
            val d = plan.data
            applyItemChange(db, current, d.obj("item_change"))
            val checksum = GodSkills.writeGodSkills(db, d.obj("document_after"))
            val detail = JObj(LinkedHashMap(identity.map))
            for (k in listOf("evidence_class")) detail[k] = d.getValue(k)
            detail["character_id"] = io.github.okexodus.openknights.exact.JStr(characterId)
            for (k in listOf("hero_uid", "times", "presses", "stopped", "skill_before", "progress_before", "skill_after", "progress_after")) detail[k] = d.getValue(k)
            detail["item"] = changeDetail(d.obj("item_change"))
            for (k in listOf("row_before", "row_after", "skills_after")) detail[k] = d.getValue(k)
            detail["god_skills_sha256"] = io.github.okexodus.openknights.exact.JStr(checksum)
            policyTail(detail, policy, "docs/ASTRAL_POWER_CONTRACT.md")
            commitState(db, current, "astral_upgrade", detail) to plan
        }
    }
}

/**
 * Hero Ascension (C3907, docs/ASCENSION_CONTRACT.md). `materialPolicy` is the labeled local ordinary-Ascension policy
 * (hero cards consumed); without it only the captured leader branch is accepted.
 */
fun StateStore.ascendHero(request: JObj, characterId: String, policy: FreshProfile.DeploymentPolicy?, inputs: HeroCardInputs?,
                          actor: String, reason: String, expectedRevision: Long? = null, materialPolicy: JObj? = null): Pair<JObj, Plan> {
    SecondaryTeam.validateOwner(characterId)
    val identity = auditIdentity(actor, reason)
    validateRevision(expectedRevision)
    if (policy == null) throw HeroAscension.AscensionRejected("Ascension lacks explicit owner/source evidence for this save")
    if (inputs == null) throw HeroAscension.AscensionRejected("Ascension requires a catalog input loader")
    connect().use { conn ->
        return conn.immediate { db ->
            val current = read(db)
            if (expectedRevision != null && current.revision != expectedRevision) throw HeroAscension.AscensionRejected("State revision changed; reread before ascending")
            heroCardPreamble(current, characterId, policy) { HeroAscension.AscensionRejected(it) }
            val view = evolutionPreamble(current, characterId, policy)
            val state = current.state
            val bench = state.arr("offline_hero_uids").map { (it as JInt).value.toLong() }
            val formationUids = state.arr("formation").map { it.asObj.getValue("hero_uid") }.filter { io.github.okexodus.openknights.server.game.Py.truthy(it) }
                .map { (it as JInt).value.toLong() }
            val secondary = current.secondaryTeam
            val secondaryUids = secondary?.obj("document")?.arr("references")?.map { (it.asObj.getValue("hero_uid") as JInt).value.toLong() } ?: emptyList()
            val excluded = (policy.explorationUids + policy.miningUids).toSet()
            val targetUid = request.long("target_uid")
            val loaded = view.heroes[targetUid]?.let { inputs.ascension(HeroFortify.heroUidOf(it, 1).longValueExact()) }
            val plan = HeroAscension.planAscension(request, view.heroes, itemWireView(current), view.gold, view.roleLevel, loaded,
                materialPolicy, bench, (formationUids + secondaryUids).toSet(), excluded)
            val d = plan.data
            val removed = d.arr("material_uids").map { it.long() }.toSet()
            if (removed.isNotEmpty()) {
                state["heroes"] = JArr(state.arr("heroes").filter { HeroFortify.heroUidOf(it.asArr).toLong() !in removed }.toMutableList())
                state["offline_hero_uids"] = d.getValue("new_bench")
                val god = current.godSkills
                if (god != null) {
                    val document = god.obj("document")
                    document["heroes"] = JArr(document.arr("heroes").filter { (it.asObj["uid"] as JInt).value.toLong() !in removed }.toMutableList())
                    GodSkills.writeGodSkills(db, document)
                }
            }
            replaceHero(current, targetUid, d.arr("after_target"))
            for (change in d.arr("item_changes")) applyItemChange(db, current, change.asObj)
            goldFields(current.state)[0]["bits"] = d.getValue("gold_after")
            val detail = JObj(LinkedHashMap(identity.map))
            detail["evidence_class"] = d.getValue("evidence_class")
            detail["character_id"] = io.github.okexodus.openknights.exact.JStr(characterId)
            detail["target_uid"] = JInt(targetUid)
            for (k in listOf("template", "level", "awaken_before", "awaken_after", "requirement_key", "requirement_row", "gates",
                "stats_before", "stats_after", "permille_before", "permille_after", "dev", "profile_components", "changed_fields")) detail[k] = d.getValue(k)
            detail["before_target"] = view.heroes.getValue(targetUid)
            detail["after_target"] = d.getValue("after_target")
            detail["materials"] = JArr(d.arr("item_changes").mapTo(ArrayList()) { changeDetail(it.asObj) })
            detail["gold_before"] = io.github.okexodus.openknights.exact.jvalue(view.gold)
            detail["gold_cost"] = d.getValue("gold_cost")
            detail["gold_after"] = d.getValue("gold_after")
            for (k in listOf("kind", "material_uids", "removed_materials", "field_24_added")) detail[k] = d.getValue(k)
            detail["bench_count_before"] = JInt(bench.size)
            detail["bench_count_after"] = JInt(state.arr("offline_hero_uids").size)
            detail["material_policy"] = if (d.arr("material_uids").isNotEmpty() && materialPolicy != null) {
                jobj("path" to materialPolicy["path"], "sha256" to materialPolicy["sha256"],
                    "profile" to materialPolicy.obj("document")["profile"], "class" to materialPolicy.obj("document")["class"])
            } else JNull
            policyTail(detail, policy, "docs/ASCENSION_CONTRACT.md")
            commitState(db, current, "ascend_hero", detail) to plan
        }
    }
}

private fun JValue.long(): Long = (this as JInt).value.toLong()

/** `_power_up_policy_detail(power_up_policy)`. */
fun powerUpPolicyDetail(powerUpPolicy: JObj): JObj {
    val document = (powerUpPolicy["document"] ?: powerUpPolicy) as JObj
    return jobj("path" to powerUpPolicy["path"], "sha256" to powerUpPolicy["sha256"], "profile" to document["profile"], "class" to document["class"])
}

/**
 * Power Up train (C3713): consume the stones and record the pending roll, atomically. The roll is a labeled local RNG
 * policy (docs/POWER_UP_CONTRACT.md); the pending deltas live in this revision's history row until a save.
 */
fun StateStore.powerUpTrain(request: JObj, characterId: String, policy: FreshProfile.DeploymentPolicy?, inputs: HeroCardInputs?,
                            powerUpPolicy: JObj?, actor: String, reason: String, expectedRevision: Long? = null,
                            rngSeed: BigInteger? = null): Pair<JObj, Plan> {
    SecondaryTeam.validateOwner(characterId)
    val identity = auditIdentity(actor, reason)
    validateRevision(expectedRevision)
    if (policy == null) throw HeroPowerUp.PowerUpRejected("Power Up lacks explicit owner/source evidence for this save")
    if (inputs == null) throw HeroPowerUp.PowerUpRejected("Power Up requires a catalog input loader")
    connect().use { conn ->
        return conn.immediate { db ->
            val current = read(db)
            if (expectedRevision != null && current.revision != expectedRevision) throw HeroPowerUp.PowerUpRejected("State revision changed; reread before training")
            heroCardPreamble(current, characterId, policy) { HeroPowerUp.PowerUpRejected(it) }
            val heroes = SecondaryTeam.ownedHeroes(current.state)
            val targetUid = request.long("target_uid")
            val statInputs = heroes[targetUid]?.let { inputs.heroStats(HeroFortify.heroUidOf(it, 1).longValueExact()) }
            val excluded = (policy.explorationUids + policy.miningUids).toSet()
            val items = itemWireView(current)
            val powerUp = inputs.powerUp()
            val plan = HeroPowerUp.planTrain(request, heroes, items, statInputs, powerUp, powerUpPolicy, rngSeed, excluded)
            val d = plan.data
            applyItemChange(db, current, d.obj("item_change"))
            val detail = JObj(LinkedHashMap(identity.map))
            detail["evidence_class"] = d.getValue("evidence_class")
            detail["character_id"] = io.github.okexodus.openknights.exact.JStr(characterId)
            detail["target_uid"] = JInt(targetUid)
            for (k in listOf("template", "way", "count", "rng_seed", "raw_roll", "pending", "dev_before", "stats_before", "caps",
                "profile_components")) detail[k] = d.getValue(k)
            detail["item"] = changeDetail(d.obj("item_change"))
            detail["power_up_policy"] = powerUpPolicyDetail(powerUpPolicy!!)
            policyTail(detail, policy, "docs/POWER_UP_CONTRACT.md")
            commitState(db, current, "power_up_train", detail) to plan
        }
    }
}

/** `_latest_power_up_pending(db, target_uid)`: the newest power_up_train row of this hero, unless a later save consumed it. */
fun latestPowerUpPending(db: SqlConnection, targetUid: Long): JObj? {
    for (row in db.query("SELECT revision,action,detail_json FROM state_history " +
            "WHERE action IN ('power_up_train','power_up_save') OR action LIKE 'acquire_%' ORDER BY revision DESC")) {
        val detail = Json.loads(row.string("detail_json")).asObj
        val action = row.string("action")
        if (action.startsWith("acquire_")) {
            // Hero UIDs are max(owned) + 1 and a removed top UID is reused: an earlier hero's pending roll never carries
            // over to a new instance with the same UID.
            if ((detail["heroes_added"] as? JArr ?: JArr()).any { (it as JObj)["uid"] == JInt(targetUid) }) return null
            continue
        }
        if (detail["target_uid"] != JInt(targetUid)) continue
        if (action == "power_up_save") return null
        return jobj("revision" to row.long("revision"), "pending" to detail.getValue("pending"), "dev_before" to detail.getValue("dev_before"))
    }
    return null
}

/** Power Up save (C3721): apply the pending roll to the development fields and stats, atomically. */
fun StateStore.powerUpSave(request: JObj, characterId: String, policy: FreshProfile.DeploymentPolicy?, inputs: HeroCardInputs?,
                           actor: String, reason: String, expectedRevision: Long? = null): Pair<JObj, Plan> {
    SecondaryTeam.validateOwner(characterId)
    val identity = auditIdentity(actor, reason)
    validateRevision(expectedRevision)
    if (policy == null) throw HeroPowerUp.PowerUpRejected("Power Up lacks explicit owner/source evidence for this save")
    if (inputs == null) throw HeroPowerUp.PowerUpRejected("Power Up requires a catalog input loader")
    connect().use { conn ->
        return conn.immediate { db ->
            val current = read(db)
            if (expectedRevision != null && current.revision != expectedRevision) throw HeroPowerUp.PowerUpRejected("State revision changed; reread before saving")
            heroCardPreamble(current, characterId, policy) { HeroPowerUp.PowerUpRejected(it) }
            val heroes = SecondaryTeam.ownedHeroes(current.state)
            val targetUid = request.long("target_uid")
            val fields = heroes[targetUid] ?: throw HeroPowerUp.PowerUpRejected("Target hero is not owned", 1000)
            val pending = latestPowerUpPending(db, targetUid)
            val plan = HeroPowerUp.planSave(request, heroes, inputs.heroStats(HeroFortify.heroUidOf(fields, 1).longValueExact()), pending)
            val d = plan.data
            replaceHero(current, targetUid, d.arr("after_target"))
            val detail = JObj(LinkedHashMap(identity.map))
            detail["evidence_class"] = d.getValue("evidence_class")
            detail["character_id"] = io.github.okexodus.openknights.exact.JStr(characterId)
            detail["target_uid"] = JInt(targetUid)
            for (k in listOf("train_revision", "deltas", "dev_before", "dev_after", "stats_before", "stats_after", "changed_fields")) detail[k] = d.getValue(k)
            detail["before_target"] = fields
            detail["after_target"] = d.getValue("after_target")
            policyTail(detail, policy, "docs/POWER_UP_CONTRACT.md")
            commitState(db, current, "power_up_save", detail) to plan
        }
    }
}
