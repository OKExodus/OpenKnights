package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.PyRandom
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.TypedValues
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.Entropy
import io.github.okexodus.openknights.server.game.HeroDictionaryProgression.f32
import java.math.BigInteger

/**
 * Hero Power Up (development, hero fields 15-18; `hero_power_up.py`): C3693 open (no reply), C3713 train
 * (u32 UID, u8 way, u16 count → S68/66 stones, S3702 u32 UID + 4 × i32 pending deltas), C3721 save (u32 UID →
 * S46 changed fields, S3704 `00000000`).
 *
 * The cap per stat is Formula::GetHeroDevMax = trunc(f32(property 156) × f32(stat − dev) / 10000) in binary32. The
 * per-try roll is server RNG and unrecoverable from the client: it runs only under the labeled local policy
 * `hero_power_up_local_rng_policy_v1` (way 1: the captured per-try frequencies; ways 2 / 3: fumo.csv ranges), summed
 * over `count` tries, the pending dev clamped to [0, cap]. The 64-bit seed comes from the service entropy and is
 * recorded, so a commit replays exactly.
 */
object HeroPowerUp {
    const val OPEN_OPCODE = 3693
    const val TRAIN_OPCODE = 3713
    const val SAVE_OPCODE = 3721
    const val RESULT_OPCODE = 3702
    const val SAVE_RESULT_OPCODE = 3704
    const val HERO_UPDATE_OPCODE = 46
    const val ITEM_UPDATE_OPCODE = 68
    const val ITEM_REMOVE_OPCODE = 66

    val STAT_IDS = listOf(4L, 6L, 8L, 10L)
    val DEV_IDS = listOf(15L, 16L, 17L, 18L)
    val STAT_NAMES = listOf("hp", "atk", "def", "crit")
    val ALLOWED_COUNTS = listOf(1L, 10L, 100L, 1000L)
    val SAVE_SUCCESS_PAYLOAD = byteArrayOf(0, 0, 0, 0)

    const val POLICY_PROFILE = "hero_power_up_local_rng_policy_v1"
    const val POLICY_CLASS = "preservation_policy_local"

    const val ERROR_INVALID = 102
    const val ERROR_NO_HERO = 1000
    const val ERROR_AT_CAP = 1022
    const val ERROR_ITEMS = 2002

    val TYPE_OF_STAT = mapOf("hp" to 1L, "atk" to 7L, "def" to 8L, "crit" to 6L)

    open class PowerUpRejected(message: String, val code: Int = ERROR_INVALID) : IllegalArgumentException(message)

    private val REJECT = HeroStats.Reject { m, c -> PowerUpRejected(m, c) }

    private fun uint(value: Long, width: Int, label: String): Long {
        if (value < 0 || value >= (1L shl width)) throw PyValues.ValueError("$label must fit uint$width")
        return value
    }

    // --- codecs --------------------------------------------------------------------------------------------------

    fun decodeOpenRequest(payload: ByteArray): JObj {
        val reader = WireReader(payload)
        val value = jobj("target_uid" to reader.u32())
        if (reader.offset != payload.size) throw PyValues.ValueError("Power Up open request has trailing bytes")
        return value
    }

    /** C3713: u32 UID, u8 way, u16 count. */
    fun decodeTrainRequest(payload: ByteArray): JObj {
        val reader = WireReader(payload)
        val value = jobj("target_uid" to reader.u32(), "way" to reader.u8(), "count" to reader.u16())
        if (reader.offset != payload.size) throw PyValues.ValueError("Power Up train request has trailing bytes")
        return value
    }

    fun encodeTrainRequest(value: JObj): ByteArray = WireWriter().u32(uint(value.long("target_uid"), 32, "UID"))
        .u8(uint(value.long("way"), 8, "Way").toInt()).u16(uint(value.long("count"), 16, "Count").toInt()).bytes()

    /** C3721: u32 UID. */
    fun decodeSaveRequest(payload: ByteArray): JObj {
        val reader = WireReader(payload)
        val value = jobj("target_uid" to reader.u32())
        if (reader.offset != payload.size) throw PyValues.ValueError("Power Up save request has trailing bytes")
        return value
    }

    /** S3702: u32 UID, 4 × i32 pending deltas (HP, ATK, DEF, CRIT). */
    fun resultPayload(uid: Long, deltas: List<Long>): ByteArray {
        if (deltas.size != 4) throw PyValues.ValueError("Power Up result needs four deltas")
        val w = WireWriter().u32(uint(uid, 32, "UID"))
        for (d in deltas) w.number('i', d)
        return w.bytes()
    }

    // --- arithmetic ------------------------------------------------------------------------------------------------

    /** Formula::GetHeroDevMax: trunc(f32(p156) × f32(stat − dev) / 10000) in binary32. */
    fun devCaps(stats: List<BigInteger>, dev: List<BigInteger>, capPermille: Long): List<Long> {
        val caps = ArrayList<Long>()
        for (i in stats.indices) {
            if (i >= dev.size) break
            val base = (stats[i] - dev[i]).and(BigInteger.valueOf(0xFFFFFFFFL)).toLong()
            caps.add(f32(f32(f32(capPermille.toDouble()) * f32(base.toDouble())) / f32(10000.0)).toLong())
        }
        return caps
    }

    /**
     * `check_policy(policy)`: only the labeled local Power Up RNG policy document (or null) is accepted: ways 1..3,
     * each with an integer item, a positive stones_per_try and positive integer weights per hp / atk / def / crit.
     * The service checks the loaded `power-up` policy with it at start.
     */
    fun checkPolicy(policy: JValue?): JValue? {
        if (policy == null || policy == io.github.okexodus.openknights.exact.JNull) return null
        val doc = if (policy is JObj) (policy["document"] ?: policy) else null
        if (doc !is JObj || (doc["profile"] as? JStr)?.value != POLICY_PROFILE || (doc["class"] as? JStr)?.value != POLICY_CLASS) {
            throw PowerUpRejected("Power Up policy document is not the labeled local RNG profile")
        }
        val ways = doc["ways"]
        if (ways !is JObj || ways.isEmpty()) throw PowerUpRejected("Power Up policy declares no ways")
        for ((way, specValue) in ways) {
            if (way !in listOf("1", "2", "3")) throw PowerUpRejected("Power Up policy way outside 1..3")
            val spec = specValue as? JObj ?: throw IllegalStateException("'${specValue.javaClass.simpleName}' object has no attribute 'get'")
            val stones = spec["stones_per_try"]
            if (spec["item"] !is JInt || stones !is JInt || stones.value.signum() <= 0) {
                throw PowerUpRejected("Power Up policy way needs an item and a positive stones_per_try")
            }
            val table = spec["per_try_distribution"]
            if (table !is JObj || table.keys != STAT_NAMES.toSet()) throw PowerUpRejected("Power Up policy distribution must name hp/atk/def/crit")
            for (weights in table.values) {
                val w = weights as? JObj ?: throw IllegalStateException("'${weights.javaClass.simpleName}' object has no attribute 'values'")
                if (w.isEmpty() || w.values.any { it !is JInt || it.value.signum() <= 0 }) throw PowerUpRejected("Power Up policy weights must be positive integers")
                for (value in w.keys) PyValues.parseInt(value)
            }
        }
        return policy
    }

    private fun policyDoc(policy: JObj): JObj = (policy["document"] ?: policy) as JObj

    /** The sum of `count` tries; per try one independent draw per stat in HP / ATK / DEF / CRIT order. */
    fun roll(seed: BigInteger, count: Long, distribution: JObj): List<Long> {
        val rng = PyRandom.seeded(seed)
        val tables = STAT_NAMES.map { name ->
            val items = distribution.obj(name).map { (value, weight) -> PyValues.parseLong(value) to (weight as JInt).value.longValueExact() }
                .sortedWith(compareBy<Pair<Long, Long>> { it.first }.thenBy { it.second })
            items to items.sumOf { it.second }
        }
        val totals = longArrayOf(0, 0, 0, 0)
        repeat(count.toInt()) {
            for ((index, entry) in tables.withIndex()) {
                val (items, total) = entry
                var pick = rng.randrange(total)
                for ((value, weight) in items) {
                    if (pick < weight) {
                        totals[index] += value
                        break
                    }
                    pick -= weight
                }
            }
        }
        return totals.toList()
    }

    private fun resolve(heroes: Map<Long, JArr>, targetUid: Long, statInputs: JObj?): JObj {
        val fields = heroes[targetUid] ?: throw PowerUpRejected("Target hero is not owned", ERROR_NO_HERO)
        if (statInputs == null) throw PowerUpRejected("Catalog inputs are unavailable for the requested hero")
        val resolved = HeroStats.resolveProfile(fields, statInputs, REJECT)
        val values = resolved.obj("values")
        for (fieldId in DEV_IDS) {
            val value = values[fieldId.toString()] as JObj?
            if (value == null || value["tag"] != JInt(6)) throw PowerUpRejected("Hero development fields 15-18 are absent or not u32; unobserved profile")
        }
        return resolved
    }

    /**
     * `table_way(inputs, way)`: the Intermediate (2) / Advanced (3) per-try draw built from fumo.csv — per stat a
     * decrease with chance 105 and an increase with chance 108 (per 10,000), each uniform over its range; otherwise no
     * change. Null when the table has no such way.
     */
    fun tableWay(inputs: JObj, way: Long): JObj? {
        val row = (inputs["table_ways"] as? JObj)?.get(way.toString()) as JObj?
        if (row == null || !Py.truthy(row["item"]) || !Py.truthy(row["stones_per_try"])) return null
        val table = JObj()
        val decrease = row.long("decrease")
        val increase = row.long("increase")
        for (name in STAT_NAMES) {
            val kind = TYPE_OF_STAT.getValue(name).toString()
            val weights = LinkedHashMap<String, Long>()
            for ((chance, range, sign) in listOf(Triple(decrease, row.obj("down")[kind] as JArr?, -1L), Triple(increase, row.obj("up")[kind] as JArr?, 1L))) {
                val low = range?.get(0)?.long ?: 0L
                val high = range?.get(1)?.long ?: 0L
                val span = if (high >= low && low > 0 && chance > 0) high - low + 1 else 0L
                if (span == 0L) continue
                for (value in low..high) {
                    val k = (sign * value).toString()
                    // `weights.get(k, 0) + chance * 10 // span or 1`: the `or` applies to the sum
                    val sum = (weights[k] ?: 0L) + Math.floorDiv(chance * 10, span)
                    weights[k] = if (sum == 0L) 1L else sum
                }
            }
            val still = 10000 - decrease - increase
            if (still > 0) weights["0"] = (weights["0"] ?: 0L) + still * 10
            table[name] = JObj().also { o -> weights.forEach { (k, v) -> o[k] = JInt(v) } }
        }
        return jobj("item" to row["item"], "stones_per_try" to row["stones_per_try"], "per_try_distribution" to table,
            "source" to "fumo.csv row ${row["row"]}")
    }

    /**
     * Validate one decoded C3713; roll under the labeled policy; return the mutation. `items` {uid: [uid, template,
     * count]}; `inputs` the Power Up catalog inputs. [Plan.packets] holds the stone frame.
     */
    fun planTrain(request: JObj, heroes: Map<Long, JArr>, items: Map<Long, JArr>, statInputs: JObj?, inputs: JObj, policy: JValue?,
                  rngSeed: BigInteger? = null, excludedUids: Collection<Long> = emptyList()): Plan {
        if (checkPolicy(policy) == null) throw PowerUpRejected("Power Up requires the labeled local RNG policy (server RNG is unrecovered)")
        val doc = policyDoc(policy as JObj)
        val targetUid = request.long("target_uid")
        val way = request.long("way")
        val count = request.long("count")
        if (targetUid in excludedUids.toSet()) throw PowerUpRejected("Hero is setting out (exploration/mining) and cannot be powered up")
        if (count !in ALLOWED_COUNTS) throw PowerUpRejected("Train count outside the client's 1/10/100/1000 buttons")
        val spec = (doc.obj("ways")[way.toString()]?.takeIf { Py.truthy(it) } as JObj?) ?: tableWay(inputs, way)
            ?: throw PowerUpRejected("Power Up way $way has no policy distribution (unobserved)")
        if (spec["item"] != inputs.obj("way_items")[way.toString()]) throw PowerUpRejected("Policy stone item disagrees with the catalog way item")
        if (way == 1L && spec["stones_per_try"] != inputs["stones_per_try_159"]) throw PowerUpRejected("Policy stones_per_try disagrees with property 159")
        val resolved = resolve(heroes, targetUid, statInputs)
        val values = resolved.obj("values")
        val stats = STAT_IDS.map { values.obj(it.toString()).int("bits") }
        val dev = DEV_IDS.map { values.obj(it.toString()).int("bits") }
        val caps = devCaps(stats, dev, inputs.long("cap_permille_156"))
        if (dev.indices.all { it >= caps.size || dev[it] >= BigInteger.valueOf(caps[it]) }) throw PowerUpRejected("Every development value is at its cap", ERROR_AT_CAP)
        val stones = spec.long("stones_per_try") * count
        val stacks = items.values.filter { it[1] == spec["item"] }
        if (stacks.size != 1) throw PowerUpRejected("Hero Stone stack is missing or not a single stack", ERROR_ITEMS)
        val stackUid = stacks[0][0].long
        val owned = stacks[0][2].long
        if (owned < stones) throw PowerUpRejected("Not enough Hero Stones for the requested tries", ERROR_ITEMS)
        val seed = rngSeed ?: Entropy.current.randbits(64)
        val raw = roll(seed, count, spec.obj("per_try_distribution"))
        val pending = dev.indices.filter { it < raw.size && it < caps.size }.map { i ->
            val d = dev[i].longValueExact()
            val c = caps[i]
            if (c >= d) minOf(maxOf(d + raw[i], 0L), c) - d else 0L
        }
        val remaining = owned - stones
        val packet = HeroEvolution.stackPacket(stackUid, remaining)
        return Plan(jobj("target_uid" to targetUid, "way" to way, "count" to count, "rng_seed" to seed, "raw_roll" to raw,
            "pending" to pending, "dev_before" to dev, "stats_before" to stats, "caps" to caps,
            "template" to resolved["template"], "profile_components" to resolved["components"],
            "item_change" to jobj("uid" to stackUid, "template" to spec["item"], "quantity" to stones, "remaining" to remaining),
            "evidence_class" to "preservation_policy_local_rng"), listOf(packet))
    }

    /** Live order: S68/66 (stones), S3702 (pending). */
    fun trainPackets(plan: Plan): List<Frame> =
        listOf(plan.packets[0], RESULT_OPCODE to resultPayload(plan.data.long("target_uid"), plan.data.arr("pending").map { it.long }))

    /** Apply the latest pending roll of this hero (from its power_up_train commit). */
    fun planSave(request: JObj, heroes: Map<Long, JArr>, statInputs: JObj?, pending: JObj?): Plan {
        val targetUid = request.long("target_uid")
        if (pending == null) throw PowerUpRejected("No pending Power Up result for this hero")
        val resolved = resolve(heroes, targetUid, statInputs)
        val values = resolved.obj("values")
        val dev = DEV_IDS.map { values.obj(it.toString()).int("bits") }
        if (JArr(dev.mapTo(ArrayList<JValue>()) { JInt(it) }) != pending.arr("dev_before")) throw PowerUpRejected("Hero development changed since the pending roll")
        val deltas = pending.arr("pending").map { (it as JInt).value }
        val changed = ArrayList<JObj>()
        for (fieldIds in listOf(STAT_IDS, DEV_IDS)) {
            for ((i, fieldId) in fieldIds.withIndex()) {
                if (i >= deltas.size) break
                val delta = deltas[i]
                if (delta.signum() == 0) continue
                val value = values.obj(fieldId.toString())
                val newValue = value.int("bits") + delta
                val width = when (TypedValues.WIDTH_FORMAT.getValue(value.long("tag").toInt())) { 'B' -> 8; 'H' -> 16; 'I' -> 32; else -> 64 }
                if (newValue.signum() < 0 || newValue >= BigInteger.ONE.shiftLeft(width)) throw PowerUpRejected("Hero field $fieldId would leave its wire range")
                changed.add(jobj("id" to fieldId, "value" to jobj("tag" to value["tag"], "bits" to newValue)))
            }
        }
        changed.sortBy { it.long("id") }
        val changedArr = JArr(changed.toMutableList<JValue>())
        val after = HeroEvolution.afterFields(heroes.getValue(targetUid), changedArr)
        return Plan(jobj("target_uid" to targetUid, "deltas" to deltas, "dev_before" to dev,
            "dev_after" to dev.indices.filter { it < deltas.size }.map { dev[it] + deltas[it] },
            "stats_before" to STAT_IDS.map { values.obj(it.toString()).int("bits") },
            "stats_after" to STAT_IDS.indices.filter { it < deltas.size }.map { values.obj(STAT_IDS[it].toString()).int("bits") + deltas[it] },
            "changed_fields" to changedArr, "after_target" to after, "train_revision" to pending["revision"],
            "evidence_class" to "capture_observed_save"))
    }

    /** Live order: S46 (changed fields; omitted when every delta is 0) then S3704. */
    fun savePackets(plan: Plan): List<Frame> {
        val packets = ArrayList<Frame>()
        val changed = plan.data.arr("changed_fields")
        if (changed.isNotEmpty()) packets.add(HERO_UPDATE_OPCODE to HeroEvolution.heroPropertyUpdatePayload(plan.data.long("target_uid"), changed))
        packets.add(SAVE_RESULT_OPCODE to SAVE_SUCCESS_PAYLOAD.copyOf())
        return packets
    }
}
