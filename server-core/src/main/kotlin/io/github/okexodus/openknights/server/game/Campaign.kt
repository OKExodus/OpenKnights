package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.PyInt
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.StateStore
import java.math.BigInteger
import java.util.WeakHashMap

/**
 * The login parts of `campaign.py` (the battles belong to the campaign port): the per-character campaign document
 * (claimed star boxes S3234, reset day, regeneration anchors), the new-day reset of the stage records' daily bytes,
 * Action Points / Energy regeneration over the time the game was closed, and the release of a level-gated CurStage.
 */
object Campaign {
    const val S_BOXES = 3234
    const val ROLE_LEVEL = 3L
    const val ROLE_CUR_STAGE = 13L
    const val ROLE_MAX_STAMINA = 23L
    const val ENERGY = 10L
    const val ROLE_MAX_ENERGY = 24L
    const val P_REGEN_SECONDS = 20L
    const val P_ENERGY_REGEN_SECONDS = 21L
    const val PROFILE = "campaign_state_v1"

    /** (resource role, maximum role, document anchor key, period property, default seconds). */
    data class Regen(val valueRole: Long, val capRole: Long, val anchorKey: String, val periodKey: Long, val default: Long)

    val REGEN = listOf(Regen(Acquisition.STAMINA, ROLE_MAX_STAMINA, "regen_anchor", P_REGEN_SECONDS, 360),
        Regen(ENERGY, ROLE_MAX_ENERGY, "energy_regen_anchor", P_ENERGY_REGEN_SECONDS, 15))

    // --- tables --------------------------------------------------------------------------------------------------------

    /** `_int(value, default)`: `str(value).strip()` as an integer when it is (optionally signed) digits, else default. */
    fun int(value: String?, default: Long = 0): Long = PyValues.digitInt(value, default)

    private val tableCache = WeakHashMap<DailyInputs, HashMap<String, Map<Long, Map<String, Long>>>>()

    /** `{int(101): {field: int(cell)}}` of a table (non-digit cells → 0), cached on the inputs. */
    fun table(inputs: DailyInputs, name: String): Map<Long, Map<String, Long>> = synchronized(tableCache) {
        tableCache.getOrPut(inputs) { HashMap() }.getOrPut(name) {
            val out = LinkedHashMap<Long, Map<String, Long>>()
            for (r in inputs.tableRows(name)) {
                val key = int(r.field("101"))
                if (key == 0L) continue
                out[key] = LinkedHashMap<String, Long>().also { m -> for ((k, v) in r.fields()) m[k] = int(v) }
            }
            out
        }
    }

    fun stageRow(inputs: DailyInputs, stage: Long): Map<String, Long>? = table(inputs, "stage")[stage]

    fun mapStages(inputs: DailyInputs, mapId: Long): List<Long> =
        table(inputs, "stage").entries.filter { it.value["117"] == mapId }.map { it.key }.sorted()

    /** 1 normal, 2 elite, 3 epic — the leading digit. */
    fun modeOf(stage: Long): Long = Math.floorDiv(stage, 10000L)

    private fun prop(inputs: AcquisitionInputs, key: Long, default: Long): Long {
        val value = inputs.property(key)
        return if (value == null || value == "") default else int(value, default)
    }

    // --- state ------------------------------------------------------------------------------------------------------------

    private fun entries(state: JObj): JObj = state.obj("subsystems").obj("stages").obj("stages")

    /** {stage: [stars, fights today, entry open]} of the S18 stage section. */
    fun records(state: JObj): Map<Long, List<JValue>> {
        val out = LinkedHashMap<Long, List<JValue>>()
        for (e in entries(state).arr("entries")) {
            val wire = e.asObj.arr("wire_values")
            out[PyDocs.long(wire[0])] = wire.drop(1)
        }
        return out
    }

    fun role(owned: Owned, field: Long): BigInteger = owned.roleBits(field)

    private fun setRole(owned: Owned, field: Long, value: Long): Frame = owned.roleAdd(field, BigInteger.valueOf(value) - role(owned, field))

    fun newDocument(seedPayload: ByteArray?, provenance: JValue?): JObj {
        val boxes = JArr()
        if (seedPayload != null && seedPayload.isNotEmpty()) {
            val r = WireReader(seedPayload)
            val n = r.u16()
            repeat(n) { boxes.add(JInt(r.u32())) }
        }
        return jobj("profile" to PROFILE, "day" to null, "boxes" to boxes, "regen_anchor" to null, "seed" to (provenance ?: JNull))
    }

    /** S3234 `u16 n, n × u32 claimed box` (sorted). */
    fun boxesPayload(document: JObj): ByteArray {
        val boxes = PyDocs.sorted(document["boxes"]?.let { it as JArr } ?: JArr())
        val w = WireWriter().number('H', boxes.size.toLong())
        for (b in boxes) w.number('I', b)
        return w.bytes()
    }

    /** Local midnight: every stage record's daily bytes return to (0, 1). Returns whether the day changed. */
    fun dayRoll(state: JObj, document: JObj, now: Long): Boolean {
        val today = Shops.dayOf(now)
        if (PyDocs.get(document, "day") == JStr(today)) return false
        for (e in entries(state).arr("entries")) {
            val wire = e.asObj.arr("wire_values")
            wire[2] = JInt(0)
            wire[3] = JInt(1)
        }
        document["day"] = JStr(today)
        return true
    }

    private fun regenOne(owned: Owned, document: JObj, valueRole: Long, capRole: Long, anchorKey: String, period: Long, now: Long): Boolean {
        val value = role(owned, valueRole)
        val cap = role(owned, capRole)
        val anchor = PyDocs.get(document, anchorKey)
        if (value >= cap) {
            document[anchorKey] = JNull
            return false
        }
        if (anchor == null || PyDocs.compare(anchor, JInt(now)) > 0) {
            document[anchorKey] = JInt(now)
            return false
        }
        val anchorValue = PyDocs.int(anchor)
        val ticks = maxOf(BigInteger.ZERO, PyInt.floorDiv(BigInteger.valueOf(now) - anchorValue, BigInteger.valueOf(period)))
        if (ticks.signum() == 0) return false
        val gained = ticks.min(cap - value)
        owned.roleAdd(valueRole, gained)
        document[anchorKey] = if (value + gained >= cap) JNull else JInt(anchorValue + ticks * BigInteger.valueOf(period))
        return true
    }

    /** Action Points and Energy refill over time. Returns one S128 of the changed values or null. */
    fun regen(owned: Owned, document: JObj, inputs: AcquisitionInputs, now: Long): Frame? {
        val changed = ArrayList<Long>()
        for (r in REGEN) {
            try {
                role(owned, r.valueRole); role(owned, r.capRole)
            } catch (e: Acquisition.Rejected) {
                continue
            }
            if (regenOne(owned, document, r.valueRole, r.capRole, r.anchorKey, prop(inputs, r.periodKey, r.default), now)) changed.add(r.valueRole)
        }
        if (changed.isEmpty()) return null
        return Acquisition.S_ROLE to Acquisition.roleUpdatePayload(changed.map { f -> Triple(f, owned.role(f).long("tag"), owned.roleBits(f)) })
    }

    /**
     * CurStage (role 13) moves to the next normal stage (field 115) while the current one has been won and the level
     * meets the next one's field 116. Returns whether it moved.
     */
    fun advanceCurStage(owned: Owned, inputs: DailyInputs, level: Long? = null): Boolean {
        val lvl: BigInteger
        var current: Long
        try {
            lvl = if (level == null) role(owned, ROLE_LEVEL) else BigInteger.valueOf(level)
            current = role(owned, ROLE_CUR_STAGE).toLong()
        } catch (e: Acquisition.Rejected) {
            return false
        }
        val recs = records(owned.state)
        var moved = false
        val seen = HashSet<Long>()
        while (current in recs && current !in seen) {
            seen.add(current)
            val row = stageRow(inputs, current)
            val following = if (row != null) stageRow(inputs, row["115"] ?: 0L) else null
            if (following == null || modeOf(row!!.getValue("115")) != 1L || BigInteger.valueOf(following["116"] ?: 0L) > lvl) break
            setRole(owned, ROLE_CUR_STAGE, row.getValue("115"))
            current = row.getValue("115")
            moved = true
        }
        return moved
    }

    // === campaign battles (group 9, owned by the campaign slice) =====================================================
    // Lead-written constants + fixed-signature stubs so Session.campaignRoute wires in without conflicts; the campaign
    // slice replaces each stub body with the port of the matching `campaign.py` function. A NotPorted keeps the step
    // waiting until it is ported.

    const val C_BATTLE = 129
    const val C_AUTO = 131
    const val C_STAGE_INFO = 133
    const val C_REENTRY = 135
    const val C_MAP_CHEST = 137
    const val C_STAR_BOX = 2785
    const val S_REPORT = 4
    const val S_STAR = 160
    const val S_FIRST_KILL = 162
    const val S_AUTO = 608
    const val S_AUTO_HELL = 610
    const val S_BOX = 3232
    const val NO_HELPER_SLOT = 6
    const val ERR_INVALID = 102
    const val ERR_RESOURCES = 4000
    const val ERR_UNITS = 5001
    const val ERR_SUPPORT = 5003
    const val ERR_HELPER_CD = 5005
    const val ERR_NO_ATTEMPT = 5006
    const val ERR_REQUIREMENTS = 5010

    /** `POLICY` (docs/CAMPAIGN_CONTRACT.md §6) — the transaction detail's `policy` block; a fresh copy per call. */
    fun policy(): JObj = jobj("exp_multiplier" to 1.0, "record_stars" to "max", "normal_auto" to "no_battle",
        "auto_fuse" to "ignored", "drops" to "stage_groups_v1", "regen_anchor" to "drop_below_max",
        "first_kill" to "first_local_winner", "map_chest" to "refused")

    /** A lost battle: the S4 is sent and nothing is committed (no AP, no attempt, no record change). */
    class Lost(val plan: Plan) : Exception("battle lost")

    /** Small deterministic PRNG for the settlement rolls (drops): SplitMix64 (`campaign.SplitMix64`). */
    class SplitMix64(seed: Long) {
        var state: ULong = seed.toULong()
        fun next(): ULong {
            state += 0x9E3779B97F4A7C15uL
            var z = state
            z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
            z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
            return z xor (z shr 31)
        }
        fun random(): Double = (next() shr 11).toLong().toDouble() / (1L shl 53).toDouble()
        fun randint(low: Long, high: Long): Long = low + (next() % (high - low + 1).toULong()).toLong()
    }

    /** `battle_seed(character_id, revision, stage, now, helper, slot)`: first 8 bytes (LE) of SHA-256 as a raw u64. */
    fun battleSeed(characterId: String, revision: Long, stage: Long, now: Long, helper: Long, slot: Long): Long =
        throw NotPorted("campaign.battle_seed")

    fun decodeStage(payload: ByteArray, opcode: Int): JObj = throw NotPorted("campaign.decode_stage (C$opcode)")
    fun decodeBattle(payload: ByteArray): JObj = throw NotPorted("campaign.decode_battle (C129)")
    fun decodeAuto(payload: ByteArray): JObj = throw NotPorted("campaign.decode_auto (C131)")

    /** `first_kill_payload(stage, entry, offset)`: S162 `u32 stage, cstring name, u32 epoch`. */
    fun firstKillPayload(stage: Long, entry: JObj?, offset: Long = 0): ByteArray =
        throw NotPorted("campaign.first_kill_payload (C133)")

    /** `plan_battle` (C129): checks, the engine battle, then settlement + S4 on a win or the S4 only on a loss. */
    fun planBattle(request: JObj, owned: Owned, current: StateStore.Current, inputs: DailyInputs, document: JObj,
                   now: Long, world: JObj?, helper: WorldParticipants.Participant?, helperState: JObj?, seed: Long): Plan =
        throw NotPorted("campaign.plan_battle (C129)")

    /** `plan_auto` (C131): Auto-play of a won stage — normal ×N (S608) or elite/epic (S610). */
    fun planAuto(request: JObj, owned: Owned, current: StateStore.Current, inputs: DailyInputs, document: JObj,
                 now: Long, rng: SplitMix64): Plan = throw NotPorted("campaign.plan_auto (C131)")

    /** `plan_reentry` (C135): Diamond re-entry of an elite/epic stage. */
    fun planReentry(request: JObj, owned: Owned, inputs: DailyInputs, document: JObj, now: Long, serverTime: Long?): Plan =
        throw NotPorted("campaign.plan_reentry (C135)")

    /** `plan_star_box` (C2785): claim a three-star treasure chest. */
    fun planStarBox(request: JObj, owned: Owned, inputs: DailyInputs, document: JObj): Plan =
        throw NotPorted("campaign.plan_star_box (C2785)")

    /** `helper_participant` (C129 helper): the friend/participant whose captain replaces the own hero of the slot. */
    fun helperParticipant(request: JObj, owned: Owned, worldCtx: DailyRoutes.WorldContext?, social: JObj?, now: Long,
                          inputs: DailyInputs): WorldParticipants.Participant? =
        throw NotPorted("campaign.helper_participant (C129)")

    /** `own_name(state)`: the character's name (role property 2, UTF-8). */
    fun ownName(state: JObj): String = throw NotPorted("campaign.own_name")
}
