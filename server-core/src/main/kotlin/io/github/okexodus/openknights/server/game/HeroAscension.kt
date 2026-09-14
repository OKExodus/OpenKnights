package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.TypedValues
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.game.HeroDictionaryProgression.unpackHeroId
import java.math.BigInteger

/**
 * Hero Ascension (awaken, hero field 24; `hero_ascension.py`): C3907 = u32 target UID, u8 n, n × u32 material hero
 * UIDs; the reply is S68 / S66 per requirement material, S46 (the changed stats, then field 24), [S40 + S34 the
 * consumed hero cards], S3876 (empty), S128 (Gold).
 *
 * The requirement row is Formula::GetHeroAwakeReqId → herojuexingneedres (leader key level × 100 | 1), the gates
 * herojuexinglv 201 (role level, at least property 900044) / 203 (hero level), and the next herojuexing slot must
 * exist. The stats come from the shared model with the awaken level raised by one. The leader branch (no hero cards)
 * is the captured shape; ordinary heroes run only under the labeled local material-cards policy. Error codes are a
 * local mapping onto client texts: 1000 hero, 1008 top level, 103 role level, 1034 hero level, 2002 items, 4000 Gold.
 */
object HeroAscension {
    const val REQUEST_OPCODE = 3907
    const val RESULT_OPCODE = 3876
    const val HERO_UPDATE_OPCODE = 46
    const val ITEM_UPDATE_OPCODE = 68
    const val ITEM_REMOVE_OPCODE = 66
    const val ROLE_UPDATE_OPCODE = 128
    const val BENCH_REMOVE_OPCODE = 40
    const val HERO_REMOVE_OPCODE = 34

    const val UID = 0L
    const val TEMPLATE = 1L
    const val LEVEL = 2L
    val STAT_IDS = listOf(4L, 6L, 8L, 10L)
    const val AWAKEN = 24L

    const val ERROR_INVALID = 102
    const val ERROR_ROLE_LEVEL = 103
    const val ERROR_NO_HERO = 1000
    const val ERROR_TOP_LEVEL = 1008
    const val ERROR_HERO_LEVEL = 1034
    const val ERROR_ITEMS = 2002
    const val ERROR_GOLD = 4000

    const val EVIDENCE_CLASS = "capture_observed_leader_ascension"
    const val ORDINARY_EVIDENCE_CLASS = "preservation_policy_local_ordinary_ascension"
    /** The live hero's field 24 is u16. */
    const val AWAKEN_FRESH_TAG = 4L

    const val MATERIAL_POLICY_PROFILE = "hero_ascension_material_cards_policy_v1"
    const val MATERIAL_POLICY_CLASS = "preservation_policy_local"

    open class AscensionRejected(message: String, val code: Int = ERROR_INVALID) : IllegalArgumentException(message)

    private val REJECT = HeroStats.Reject { m, c -> AscensionRejected(m, c) }

    private fun uint(value: Long, width: Int, label: String): Long {
        if (value < 0 || value >= (1L shl width)) throw PyValues.ValueError("$label must fit uint$width")
        return value
    }

    /** C3907: u32 target UID, u8 n, n × u32 material hero UIDs. */
    fun decodeRequest(payload: ByteArray): JObj {
        val reader = WireReader(payload)
        val target = reader.u32()
        val count = reader.u8()
        val materials = (0 until count).map { reader.u32() }
        if (reader.offset != payload.size) throw PyValues.ValueError("Ascension request has trailing bytes")
        return jobj("target_uid" to target, "material_uids" to materials)
    }

    fun encodeRequest(value: JObj): ByteArray {
        val materials = (value["material_uids"] as? JArr ?: JArr()).map { it.long }
        val w = WireWriter().u32(uint(value.long("target_uid"), 32, "Target UID")).u8(uint(materials.size.toLong(), 8, "Count").toInt())
        for (uid in materials) w.u32(uint(uid, 32, "Material UID"))
        return w.bytes()
    }

    /** Formula::GetHeroAwakeReqId. */
    fun awakenRequirementKey(isLeader: Boolean, category: Long, level: Long): Long = PkHeroCardInputs.awakenRequirementKey(isLeader, category, level)

    /** `_consume(materials, items)`: items {uid: [uid, template, count]}; one owned stack per template (else 2002). */
    fun consume(materials: List<Pair<Long, Long>>, items: Map<Long, JArr>): List<HeroEvolution.ItemChange> {
        val byTemplate = LinkedHashMap<JValue, MutableList<Long>>()
        for ((uid, wire) in items) byTemplate.getOrPut(wire[1]) { ArrayList() }.add(uid)
        val changes = ArrayList<HeroEvolution.ItemChange>()
        for ((template, quantity) in materials) {
            if (quantity <= 0) continue
            val uids = byTemplate[JInt(template)] ?: emptyList<Long>()
            if (uids.size != 1) throw AscensionRejected("Ascension material $template is missing or not a single stack", ERROR_ITEMS)
            val owned = items.getValue(uids[0])[2].long
            if (owned < quantity) throw AscensionRejected("Insufficient ascension material $template", ERROR_ITEMS)
            val remaining = owned - quantity
            changes.add(HeroEvolution.ItemChange(uids[0], template, quantity, remaining, HeroEvolution.stackPacket(uids[0], remaining)))
        }
        return changes
    }

    /**
     * `check_material_policy(policy)`: only the labeled local ordinary-Ascension policy document (or null) is
     * accepted. The service checks the loaded `ascension` policy with it at start.
     */
    fun checkMaterialPolicy(policy: JValue?): JValue? {
        if (policy == null || policy == JNull) return null
        val doc = if (policy is JObj) (policy["document"] ?: policy) else null
        if (doc !is JObj || (doc["profile"] as? JStr)?.value != MATERIAL_POLICY_PROFILE || (doc["class"] as? JStr)?.value != MATERIAL_POLICY_CLASS) {
            throw AscensionRejected("Ascension policy document is not the labeled material-cards profile")
        }
        return policy
    }

    private fun fieldValue(fields: JArr, fieldId: Long): JValue {
        val values = fields.map { it.asObj }.filter { it["id"] == JInt(fieldId) }.map { it.obj("value").getValue("bits") }
        if (values.size != 1) throw AscensionRejected("Expected one hero field $fieldId")
        return values[0]
    }

    private fun baseId(fields: JArr): Long = unpackHeroId((fieldValue(fields, TEMPLATE) as JInt).value).baseId

    private fun width(tag: Long): Int = when (TypedValues.WIDTH_FORMAT.getValue(tag.toInt())) { 'B' -> 8; 'H' -> 16; 'I' -> 32; else -> 64 }

    /**
     * Validate one decoded C3907 against an owned view; return the exact mutation. `heroes` {uid: fields}; `items`
     * {uid: [uid, template, count]}; `inputs` the ascension inputs of the target's template. Ordinary heroes need the
     * material policy: exactly the row's 301 distinct owned cards of the target's resource base, on the bench and
     * outside deployment / assignment, removed with the Fortify path's frames. [Plan.packets] holds the material frames.
     */
    fun planAscension(request: JObj, heroes: Map<Long, JArr>, items: Map<Long, JArr>, gold: BigInteger?, roleLevel: BigInteger?,
                      inputs: JObj?, materialPolicy: JValue? = null, bench: List<Long> = emptyList(),
                      deployed: Collection<Long> = emptyList(), excluded: Collection<Long> = emptyList()): Plan {
        val targetUid = request.long("target_uid")
        val materials = (request["material_uids"] as? JArr ?: JArr()).map { it.long }
        val fields = heroes[targetUid] ?: throw AscensionRejected("Target hero is not owned", ERROR_NO_HERO)
        if (inputs == null) throw AscensionRejected("Catalog inputs are unavailable for the requested hero")
        val resolved = HeroStats.resolveProfile(fields, inputs.obj("stat_inputs"), REJECT)
        val values = resolved.obj("values")
        val isLeader = Py.truthy(inputs["is_leader"])
        if (AWAKEN.toString() !in values && isLeader) throw AscensionRejected("Leader hero has no awaken field 24; unobserved profile")
        val awaken = resolved.obj("profile").int("awaken")
        val nextLevel = awaken + BigInteger.ONE
        if (inputs["awaken_rows"] != JInt(1)) throw AscensionRejected("Hero has no unique herojuexing row")
        if (inputs.arr("awaken_slots").none { val s = it.asObj; s["slot"] == JInt(nextLevel) && Py.truthy(s["value"]) }) {
            throw AscensionRejected("Hero has no further ascension slot", ERROR_TOP_LEVEL)
        }
        val ordinary = !isLeader
        if (ordinary && checkMaterialPolicy(materialPolicy) == null) {
            // Ordinary rows (category × 10000 + level × 100) all consume duplicate hero cards (301 > 0).
            throw AscensionRejected("Ordinary (non-leader) ascension consumes hero cards; needs the labeled local policy")
        }
        val category = (inputs["category_104"] as? JInt)?.value?.takeIf { it.signum() != 0 } ?: BigInteger.ZERO
        val key = if (!ordinary) nextLevel.multiply(BigInteger.valueOf(100)).or(BigInteger.ONE)
            else category * BigInteger.valueOf(10000) + nextLevel * BigInteger.valueOf(100)
        val need = inputs.obj("need_rows")[key.toString()] as JObj?
        val lv = inputs.obj("lv_rows")[nextLevel.toString()] as JObj?
        if (need == null || lv == null) throw AscensionRejected("No configured requirement row for the next ascension level", ERROR_TOP_LEVEL)
        if (!ordinary && (need.long("hero_count_301") != 0L || materials.isNotEmpty())) {
            throw AscensionRejected("Leader ascension with material hero cards is unobserved; unsupported")
        }
        if (ordinary) {
            if (materials.size.toLong() != need.long("hero_count_301")) throw AscensionRejected("Request must name exactly the configured number of hero cards", ERROR_ITEMS)
            if (materials.toSet().size != materials.size || targetUid in materials) throw AscensionRejected("Hero cards must be distinct and differ from the target", ERROR_ITEMS)
            for (uid in materials) {
                val card = heroes[uid] ?: throw AscensionRejected("Hero card is not owned", ERROR_NO_HERO)
                if (JInt(baseId(card)) != inputs["resource_base"]) throw AscensionRejected("Hero card is not the target's resource card", ERROR_ITEMS)
                if (uid !in bench.toSet() || uid in deployed.toSet() || uid in excluded.toSet()) {
                    throw AscensionRejected("Hero card must be on the bench and outside deployment/assignment", ERROR_ITEMS)
                }
            }
        }
        val openLevel = (inputs["open_role_level_900044"] as? JInt)?.value?.takeIf { it.signum() != 0 } ?: BigInteger.ZERO
        val gateRole = lv.int("role_level_201").max(openLevel)
        if (roleLevel == null || roleLevel < gateRole) throw AscensionRejected("Role level below the ascension requirement", ERROR_ROLE_LEVEL)
        if (resolved.int("level") < lv.int("hero_level_203")) throw AscensionRejected("Hero level below the ascension requirement", ERROR_HERO_LEVEL)
        val cost = need.int("gold_401")
        if (gold == null || gold < cost) throw AscensionRejected("Insufficient Gold for the ascension", ERROR_GOLD)
        val itemChanges = consume(need.arr("materials").map { it.asArr[0].long to it.asArr[1].long }, items)
        val permille = HeroStats.statPermille(resolved.int("template"), inputs.obj("stat_inputs"), nextLevel, REJECT)
        val stats = HeroStats.baseStats(resolved.arr("full_grow").map { (it as JInt).value }, resolved.int("level"), resolved.long("grade"),
            permille, resolved.arr("dev").map { (it as JInt).value })
        val changed = JArr()
        for ((i, statId) in STAT_IDS.withIndex()) {
            if (i >= stats.size) break
            val newValue = stats[i]
            val value = values.obj(statId.toString())
            if (newValue == value.int("bits")) continue
            if (newValue >= BigInteger.ONE.shiftLeft(width(value.long("tag")))) throw AscensionRejected("Hero field $statId would overflow its wire width")
            changed.add(jobj("id" to statId, "value" to jobj("tag" to value["tag"], "bits" to newValue)))
        }
        val awakenValue = values[AWAKEN.toString()] as JObj?
        val awakenTag = awakenValue?.long("tag") ?: AWAKEN_FRESH_TAG
        if (nextLevel >= BigInteger.ONE.shiftLeft(width(awakenTag))) throw AscensionRejected("Awaken level would overflow its wire width")
        changed.add(jobj("id" to AWAKEN, "value" to jobj("tag" to awakenTag, "bits" to nextLevel)))
        var after = HeroEvolution.afterFields(fields, changed)
        if (awakenValue == null) {
            // Fresh hero maps carry no field 24; it is inserted in field-id order as u16 (local policy, first awaken).
            after.add(jobj("id" to AWAKEN, "value" to jobj("tag" to AWAKEN_FRESH_TAG, "bits" to nextLevel)))
            after = JArr(after.sortedBy { (it.asObj["id"] as JInt).value }.toMutableList())
        }
        val removed = JObj().also { o -> materials.forEach { o[it.toString()] = heroes.getValue(it) } }
        return Plan(jobj("kind" to (if (ordinary) "ordinary" else "leader"), "material_uids" to materials,
            "removed_materials" to removed, "new_bench" to bench.filter { it !in materials.toSet() },
            "field_24_added" to (awakenValue == null),
            "target_uid" to targetUid, "template" to resolved["template"], "level" to resolved["level"],
            "grade" to resolved["grade"], "awaken_before" to awaken, "awaken_after" to nextLevel,
            "requirement_key" to key, "requirement_row" to JObj(LinkedHashMap(need.map)),
            "gates" to jobj("role_level" to gateRole, "hero_level" to lv["hero_level_203"]),
            "stats_before" to STAT_IDS.map { values.obj(it.toString()).int("bits") }, "stats_after" to stats,
            "permille_before" to resolved["permille"], "permille_after" to permille, "dev" to resolved["dev"],
            "full_grow" to resolved["full_grow"], "profile_components" to resolved["components"],
            "changed_fields" to changed, "after_target" to after, "item_changes" to itemChanges.map { it.toJson() },
            "gold_cost" to cost, "gold_after" to gold - cost,
            "evidence_class" to (if (ordinary) ORDINARY_EVIDENCE_CLASS else EVIDENCE_CLASS)), itemChanges.map { it.packet })
    }

    /** Observed live order: 68/66 materials, 46 (changed fields), [40, 34 the cards], 3876 (empty), 128. */
    fun ascensionPackets(plan: Plan, goldPayload: ByteArray): List<Frame> {
        val packets = ArrayList(plan.packets)
        packets.add(HERO_UPDATE_OPCODE to HeroEvolution.heroPropertyUpdatePayload(plan.data.long("target_uid"), plan.data.arr("changed_fields")))
        val materials = plan.data.arr("material_uids").map { it.long }
        if (materials.isNotEmpty()) {
            // Policy branch: the Fortify path's removal frames, before the S3876 trigger (its handler rebuilds the list).
            packets.add(BENCH_REMOVE_OPCODE to HeroFortify.countedUidPayload(materials))
            packets.add(HERO_REMOVE_OPCODE to HeroFortify.countedUidPayload(materials))
        }
        packets.add(RESULT_OPCODE to ByteArray(0))
        packets.add(ROLE_UPDATE_OPCODE to goldPayload)
        return packets
    }
}
