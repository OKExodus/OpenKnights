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

}
