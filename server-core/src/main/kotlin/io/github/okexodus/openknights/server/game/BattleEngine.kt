package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.F32
import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JFloat
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Seeds
import io.github.okexodus.openknights.exact.SplitMix64
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asLong
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.BattleReport
import kotlin.math.abs

/**
 * The server-side campaign battle engine (`server/battle_engine.py`): the seeded, byte-exact single-battle report
 * builder. The client never simulates; the engine only has to emit a self-consistent S4 report, which is built as a
 * codec dict and round-tripped through [BattleReport] (`parse(encode(report)) == report`) before it is returned.
 *
 * Exactness: `f32` = round to IEEE binary32 ([F32.round]); `pct` / `enemyStat` apply each `f32` step and `math.floor`
 * in the reference's order; `damageValue` / `healAmount` / `lifesteal` are float64 then floor; the RNG is one
 * [SplitMix64] stream (unsigned), draws consumed in the module-doc order (random target pick, then per primary target
 * dodge, block [ordinary only], crit, special coefficient; the counter roll; then each secondary effect in slot order
 * per target, random effect targets first). A chance `<= 0` or `>= 10000` bps and a coefficient with `hi == lo`
 * consume no draw. Runes/gems/totems/combos/guard redirects are not simulated (phase 1, POLICY).
 */
object BattleEngine {
    const val ENGINE_VERSION = "pk-campaign-engine/1.0 (2026-09-12)"

    // === POLICY / CAND constants (docs/BATTLE_ENGINE.md table) ======================================================
    /** The POLICY table (`RULES`), merged with an optional per-call override. */
    class Rules(
        val roundLimit: Long = 30,
        val spMax: Long = 100, val spGain: Long = 50, val spCost: Long = 100,
        val ownStartSp: Long = 50, val awakenedLeaderSp: Long = 100, val helperSp: Long = 100,
        val monsterSpLow: Long = 50, val monsterSpHigh: Long = 100, val monsterSpLevel: Long = 100,
        val classBase: Long = 9000,
        val sideFactorPlayer: Double = 0.5637, val sideFactorMonster: Double = 0.4911,
        val defFactor: Double = 1.445, val critMultiplier: Double = 1.5, val blockDivisor: Long = 2,
        val specialStat: String = "crit", val specialSigma: Double = 0.255, val specialKappa: Double = 2.1,
        val counterChance: Long = 6000, val ordinaryCritDivisor: Long = 3, val blockOnSpecial: Boolean = false,
        val randomPickCounts: Map<Long, Long> = mapOf(14L to 3L, 16L to 2L, 17L to 1L),
        val effectRandomCount: Long = 3,
        val starThresholds: List<Pair<Long, Long>> = listOf(8000L to 3L, 5000L to 2L, 0L to 1L),
        val winLogoThresholds: List<Pair<Long, Long>> = listOf(9500L to 1L, 8000L to 2L, 0L to 3L),
        val loseLogoBase: Long = 6, val loseLogoSpan: Long = 4, val loseTip: Long = 1, val timeoutLoseTip: Long = 1,
        val reportVersion: Long = 6, val skipButton: Long = 1, val abilityRate: Long = 10000, val lineup: Long = 1,
        val dodgeDelta: Long = 0,
    ) {
        companion object {
            val DEFAULT = Rules()

            /** `{**RULES, **(rules or {})}`: keep every default, replace the keys present in the override. */
            fun from(override: JObj?): Rules {
                if (override == null || override.isEmpty()) return DEFAULT
                val d = DEFAULT
                fun l(k: String, def: Long): Long = (override[k] as? JInt)?.value?.toLong() ?: def
                fun db(k: String, def: Double): Double = when (val v = override[k]) {
                    is JFloat -> v.value
                    is JInt -> v.value.toDouble()
                    else -> def
                }
                fun b(k: String, def: Boolean): Boolean = (override[k] as? JBool)?.value ?: def
                fun s(k: String, def: String): String = (override[k] as? JStr)?.value ?: def
                fun pairs(k: String, def: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
                    val a = override[k] as? JArr ?: return def
                    return a.map { it.asArr.let { p -> p[0].asLong to p[1].asLong } }
                }
                fun counts(k: String, def: Map<Long, Long>): Map<Long, Long> {
                    val o = override[k] as? JObj ?: return def
                    return o.entries.associate { (kk, vv) -> kk.toLong() to vv.asLong }
                }
                return Rules(
                    roundLimit = l("round_limit", d.roundLimit),
                    spMax = l("sp_max", d.spMax), spGain = l("sp_gain", d.spGain), spCost = l("sp_cost", d.spCost),
                    ownStartSp = l("own_start_sp", d.ownStartSp), awakenedLeaderSp = l("awakened_leader_sp", d.awakenedLeaderSp),
                    helperSp = l("helper_sp", d.helperSp),
                    monsterSpLow = l("monster_sp_low", d.monsterSpLow), monsterSpHigh = l("monster_sp_high", d.monsterSpHigh),
                    monsterSpLevel = l("monster_sp_level", d.monsterSpLevel),
                    classBase = l("class_base", d.classBase),
                    sideFactorPlayer = db("side_factor_player", d.sideFactorPlayer),
                    sideFactorMonster = db("side_factor_monster", d.sideFactorMonster),
                    defFactor = db("def_factor", d.defFactor), critMultiplier = db("crit_multiplier", d.critMultiplier),
                    blockDivisor = l("block_divisor", d.blockDivisor),
                    specialStat = s("special_stat", d.specialStat), specialSigma = db("special_sigma", d.specialSigma),
                    specialKappa = db("special_kappa", d.specialKappa),
                    counterChance = l("counter_chance", d.counterChance),
                    ordinaryCritDivisor = l("ordinary_crit_divisor", d.ordinaryCritDivisor),
                    blockOnSpecial = b("block_on_special", d.blockOnSpecial),
                    randomPickCounts = counts("random_pick_counts", d.randomPickCounts),
                    effectRandomCount = l("effect_random_count", d.effectRandomCount),
                    starThresholds = pairs("star_thresholds", d.starThresholds),
                    winLogoThresholds = pairs("win_logo_thresholds", d.winLogoThresholds),
                    loseLogoBase = l("lose_logo_base", d.loseLogoBase), loseLogoSpan = l("lose_logo_span", d.loseLogoSpan),
                    loseTip = l("lose_tip", d.loseTip), timeoutLoseTip = l("timeout_lose_tip", d.timeoutLoseTip),
                    reportVersion = l("report_version", d.reportVersion), skipButton = l("skip_button", d.skipButton),
                    abilityRate = l("ability_rate", d.abilityRate), lineup = l("lineup", d.lineup),
                    dodgeDelta = l("dodge_delta", d.dodgeDelta),
                )
            }
        }
    }

    private val STAT_NAMES = listOf("hp", "atk", "def", "crit")
    private val SLOT_IDS = listOf("103", "104", "105", "106", "107", "108")
    private val SLOT_FACTORS = listOf(
        listOf("201", "202", "203", "204"), listOf("211", "212", "213", "214"), listOf("221", "222", "223", "224"),
        listOf("231", "232", "233", "234"), listOf("241", "242", "243", "244"), listOf("251", "252", "253", "254"))
    private val MA_FIELDS = listOf("102", "103", "104", "105")
    private val HERO_MULT = listOf("521", "522", "523", "524")
    private val GIFT_STATS = mapOf(201L to "hp", 202L to "crit", 203L to "atk", 204L to "def")
    private val BUFF_STATS = mapOf(202L to "crit", 203L to "atk", 204L to "def")
    private val BUFF_CODE = BUFF_STATS.entries.associate { (k, v) -> v to k }   // {"crit"->202, "atk"->203, "def"->204}

    private const val OUTCOME_HIT = 1L
    private const val OUTCOME_MISS = 2L
    private const val OUTCOME_CRIT = 3L
    private const val OUTCOME_BLOCK = 4L
    private const val OUTCOME_SECONDARY = 5L
    private const val OUTCOME_COUNTER = 6L
    private const val D_HP = 2L
    private const val D_SP = 4L
    private const val D_STUN = 1001L

    private inline fun <T> Iterable<T>.toJArr(f: (T) -> JValue): JArr {
        val a = JArr()
        for (x in this) a.add(f(x))
        return a
    }

    // === small numeric helpers ======================================================================================
    /** Round to IEEE binary32 (the client's float arithmetic): `x.toFloat().toDouble()`. */
    private fun f32(x: Double): Double = F32.round(x)
    private fun f32(x: Long): Double = F32.round(x.toDouble())

    /** `int(f32(f32(x) * f32(f32(bps)/f32(10000))))`: CALC heal % max HP (each f32 applied separately). */
    fun pct(amount: Long, bps: Long): Long =
        f32(f32(amount) * f32(f32(bps) / f32(10000))).toLong()

    /**
     * `trunc(f32(floor(f32(floor(f32(f32(ma*s)*h)) * P)) * G))`, `P = f32(f32(pot)/f32(10000)) + 1`, likewise `G`:
     * the CALC binary32 enemy-stat chain (max HP 82/82, ATK/DEF/CRIT the same chain with their own columns).
     */
    fun enemyStat(ma: Long, slot: Long, mult: Long, potential: Long, gift: Long): Long {
        var v = f32(f32(ma) * f32(f32(slot) / f32(10000)))
        v = f32(Math.floor(f32(v * f32(f32(mult) / f32(10000)))))
        val p = f32(f32(f32(potential) / f32(10000)) + f32(1))
        v = f32(Math.floor(f32(v * p)))
        val g = f32(f32(f32(gift) / f32(10000)) + f32(1))
        return f32(v * g).toLong()
    }

    /**
     * Hit damage (float64 then floor; [unrounded] returns the value before the floor):
     * ordinary `A = coef/10000 * F * S * ATK`, `D = A - 1.445 DEF if > 0 else A`;
     * special `A = coef/10000 * sigma * F * S * CRIT`, `D = A - kappa DEF if > 0 else A`;
     * `F = (9000 + h130_att + h130_def)/10000`, `S` the side factor; crit x1.5, block D/2.
     */
    fun damageValue(stat: Double, defense: Double, classAtt: Long, classDef: Long, player: Boolean, special: Boolean,
                    outcome: Long, coef: Long, rules: Rules = Rules.DEFAULT, unrounded: Boolean = false): Double {
        val factor = (rules.classBase + classAtt + classDef).toDouble() / 10000.0
        val side = if (player) rules.sideFactorPlayer else rules.sideFactorMonster
        val raw: Double
        var value: Double
        if (special) {
            raw = coef.toDouble() / 10000.0 * rules.specialSigma * factor * side * stat
            value = raw - rules.specialKappa * defense
        } else {
            raw = coef.toDouble() / 10000.0 * factor * side * stat
            value = raw - rules.defFactor * defense
        }
        if (value <= 0.0) value = raw
        if (outcome == OUTCOME_CRIT) value *= rules.critMultiplier
        else if (outcome == OUTCOME_BLOCK) value /= rules.blockDivisor.toDouble()
        return if (unrounded) maxOf(0.0, value) else maxOf(0.0, Math.floor(value))
    }

    /** `max(0, floor(value))` of [damageValue] (the reference's integer return). */
    private fun damageInt(stat: Double, defense: Double, classAtt: Long, classDef: Long, player: Boolean,
                          special: Boolean, outcome: Long, coef: Long, rules: Rules): Long =
        damageValue(stat, defense, classAtt, classDef, player, special, outcome, coef, rules, unrounded = false).toLong()

    /** `floor(D * value / 10000)` of the UNROUNDED hit damage D (CAND lifesteal). */
    fun lifesteal(dealt: Double, bps: Long): Long = Math.floor(dealt * bps / 10000.0).toLong()

    /** `_int(value, default)`: `str(value).strip()` as `int()`, else the default (empty or malformed). */
    private fun intOf(value: String?, default: Long = 0): Long {
        val text = PyValues.strip(value ?: "")
        if (text.isEmpty()) return default
        return try {
            PyValues.parseLong(text)
        } catch (e: PyValues.ValueError) {
            default
        }
    }

    /** `battle_seed(*parts)`: first 8 bytes (LE) of SHA-256 of `"|".join(map(str, parts))`, as a raw u64. */
    fun battleSeed(vararg parts: Any): Long = Seeds.joined(*parts).toLong()

    private fun entry(position: Long, delta: Long, dispatcher: Long, outcome: Long, presentation: Long = 0,
                      flag: Long = 0): JObj =
        jobj("position" to position, "delta" to delta, "dispatcher" to dispatcher, "outcome_raw" to outcome,
            "presentation_or_helper" to presentation, "flag_raw" to flag)

    // === catalog resolution =========================================================================================
    /** SkillConfig row → a plain skill dict, or null when the id is absent (the actor idles). */
    fun skillSpec(skillId: Long, inputs: DailyInputs): JObj? {
        if (skillId == 0L) return null
        val row = inputs.battleRow("skill", skillId) ?: return null
        val effects = JArr()
        for ((kindKey, idKey) in listOf("108" to "109", "111" to "112")) {
            val kind = intOf(row[kindKey])
            val eid = intOf(row[idKey])
            if (kind == 0L || eid == 0L) continue
            val erow = inputs.battleRow("effect", eid) ?: continue
            effects.add(jobj("kind" to kind, "id" to eid, "type" to intOf(erow["103"]), "chance" to intOf(erow["104"]),
                "value" to intOf(erow["105"]), "duration" to intOf(erow["106"]), "extra" to intOf(erow["112"])))
        }
        val lo = intOf(row["114"])
        var hi = intOf(row["115"], lo)
        if (hi == 0L) hi = lo
        return jobj("id" to skillId, "special" to (intOf(row["105"]) != 0L), "target_type" to intOf(row["106"], 1),
            "coef_lo" to minOf(lo, hi), "coef_hi" to maxOf(lo, hi), "effects" to effects)
    }

    /** hero.csv fields used in battle (normal/special skill bases, class factor, gift, name text, group, rates, mult). */
    fun heroBattleFields(base: Long, inputs: DailyInputs): JObj {
        val row = inputs.battleRow("hero", base) ?: throw IllegalArgumentException("Unknown hero base $base")
        val rates = jobj("hit" to intOf(row["114"]), "dodge" to intOf(row["115"]), "crit" to intOf(row["116"]),
            "block" to intOf(row["117"]), "counter" to intOf(row["118"]), "r119" to intOf(row["119"]),
            "r120" to intOf(row["120"]))
        val mult = HERO_MULT.toJArr { JInt(intOf(row[it])) }
        return jobj("normal" to intOf(row["113"]), "special_base" to intOf(row["112"]),
            "class_factor" to intOf(row["130"]), "gift" to intOf(row["201"]), "name_text" to intOf(row["141"]),
            "group" to PyValues.strip(row["132"] ?: ""), "rates" to rates, "mult" to mult)
    }

    /** `GetHeroAwakenSkillInfo`: scan herojuexing slots L-1..1, keep the newest value per type (1 normal, 2 special, 3 gift). */
    fun awakenOverrides(base: Long, awaken: Long, inputs: DailyInputs): Map<Long, Long> {
        if (awaken < 1 || awaken > 16) return emptyMap()
        val row = inputs.battleRow("herojuexing", base) ?: return emptyMap()
        val out = LinkedHashMap<Long, Long>()
        for (i in (awaken - 1) downTo 1) {
            val value = intOf(row[(201 + 2 * i).toString()])
            val kind = intOf(row[(202 + 2 * i).toString()])
            if (kind in longArrayOf(1, 2, 3) && kind !in out) out[kind] = value
        }
        return out
    }

    /** `normal = hero.113; special = hero.112 + grade - 1` (missing → null); awakening overrides replace them. */
    fun heroSkills(packed: Long, fields: JObj, inputs: DailyInputs, awaken: Long = 0): JObj {
        val grade = Math.floorMod(packed, 100L)
        val specialBase = fields.long("special_base", 0)
        var specialId = if (specialBase != 0L) specialBase + grade - 1 else 0L
        var normalId = fields.long("normal", 0)
        val override = if (awaken != 0L) awakenOverrides(Math.floorDiv(packed, 1000L), awaken, inputs) else emptyMap()
        normalId = override[1L] ?: normalId
        specialId = override[2L] ?: specialId
        return jobj("normal" to (skillSpec(normalId, inputs) ?: JNull), "special" to (skillSpec(specialId, inputs) ?: JNull))
    }

    /** CAP 33/33: stage leading digit 1 normal → 202, 2 elite → 1002, 3 epic → 3202. */
    fun campaignBattleType(stageId: Long): Long = when (stageId.toString()[0]) {
        '1' -> 202L
        '2' -> 1002L
        '3' -> 3202L
        else -> 202L
    }

    /** Enemy actors of one monster.csv encounter row (b2 section 3.2). */
    fun enemySideFromEncounter(encounter: String, inputs: DailyInputs, rules: Rules = Rules.DEFAULT): JObj {
        val m = inputs.battleRow("monster", encounter) ?: throw IllegalArgumentException("Unknown encounter $encounter")
        val level = intOf(m["102"])
        val ma = inputs.battleRow("monsterability", level)
            ?: throw IllegalArgumentException("No monsterability row for level $level")
        val captainSlot = intOf(m["109"])
        val slots = (1..6).map { slot -> slot.toLong() to intOf(m[SLOT_IDS[slot - 1]]) }.filter { it.second != 0L }
        var gift: JObj? = null
        var sideName = ""
        val captain = slots.toMap()[captainSlot]
        if (captain != null && captain != 0L) {
            val cfields = heroBattleFields(Math.floorDiv(captain, 1000L), inputs)
            val nameText = cfields.long("name_text", 0)
            val text = if (nameText != 0L) inputs.battleRow("text", nameText) else null
            sideName = text?.get("102") ?: ""
            val giftId = cfields.long("gift", 0)
            val grow = if (giftId != 0L) inputs.battleRow("gift", giftId) else null
            val code = intOf(grow?.get("109"))
            if (grow != null && code in GIFT_STATS) {
                gift = jobj("id" to giftId, "dispatcher" to code, "value" to intOf(grow["110"]),
                    "chance" to intOf(grow["107"], 10000), "target" to intOf(grow["108"]))
            }
        }
        val startSp = if (level >= rules.monsterSpLevel) rules.monsterSpHigh else rules.monsterSpLow
        val actors = JArr()
        for ((slot, pid) in slots) {
            val fields = heroBattleFields(Math.floorDiv(pid, 1000L), inputs)
            val grade = Math.floorMod(pid, 100L)
            val potential = if (grade > 1) inputs.battlePotential(fields.str("group"), grade) else 0L
            val mult = fields.arr("mult")
            val stats = LinkedHashMap<String, Long>()
            for (i in STAT_NAMES.indices) {
                val name = STAT_NAMES[i]
                val bonus = if (gift != null && GIFT_STATS[gift.long("dispatcher", 0)] == name) gift.long("value", 0) else 0L
                stats[name] = enemyStat(intOf(ma[MA_FIELDS[i]]), intOf(m[SLOT_FACTORS[slot.toInt() - 1][i]]),
                    (mult[i] as JInt).value.toLong(), potential, bonus)
            }
            val actor = jobj("side" to 1, "position" to slot, "packed_hero_id" to pid, "uid" to 0, "level" to level,
                "awaken" to 0, "max_hp" to stats["hp"], "atk" to stats["atk"], "def" to stats["def"],
                "crit" to stats["crit"], "rates" to fields["rates"], "class_factor" to fields["class_factor"],
                "skills" to heroSkills(pid, fields, inputs), "start_sp" to startSp, "player" to false)
            if (slot == captainSlot && gift != null) actor["gift"] = gift
            actors.add(actor)
        }
        return jobj("actors" to actors, "side_name" to sideName, "side_name_hex" to sideName.toByteArray(Charsets.UTF_8).toHexString(),
            "encounter" to encounter, "level" to level, "captain_position" to captainSlot, "gift" to (gift ?: JNull))
    }

    /** Enemy side of a campaign stage: encounter = stage.999, background = stage.119, battle type from the leading digit. */
    fun enemyActors(stageId: Long, inputs: DailyInputs, rules: JObj? = null): JObj {
        val stage = inputs.battleRow("stage", stageId) ?: throw IllegalArgumentException("Unknown stage $stageId")
        val encounter = PyValues.strip(stage["999"] ?: "").ifEmpty { stageId.toString() }
        val side = enemySideFromEncounter(encounter, inputs, Rules.from(rules))
        side["stage_id"] = JInt(stageId)
        side["background"] = JInt(intOf(stage["119"]))
        side["battle_type"] = JInt(campaignBattleType(stageId))
        return side
    }

    /** Own side actors in formation-slot order (slot 0 = leader). `stats(entry) -> {"hp","atk","def","crit"}`. */
    fun ownActorsFromStats(lineup: List<JValue>, stats: (JValue) -> JObj, inputs: DailyInputs, rules: JObj? = null): List<JObj> {
        val r = Rules.from(rules)
        val actors = ArrayList<JObj>()
        var leaderSeen = false
        for ((index, e) in lineup.withIndex()) {
            val entry = e.asObj
            val packed = listOf("packed", "template", "packed_hero_id")
                .firstNotNullOfOrNull { k -> entry[k]?.takeIf { Py.truthy(it) } }?.let { (it as JInt).value.toLong() }
                ?: throw IllegalArgumentException("lineup entry has no packed hero id")
            val fields = heroBattleFields(Math.floorDiv(packed, 1000L), inputs)
            val values = stats(entry)
            val helper = Py.truthy(entry["helper"])
            val leader = if (entry.containsKey("leader")) Py.truthy(entry["leader"]) else (!leaderSeen && !helper && index == 0)
            leaderSeen = leaderSeen || leader
            val awaken = entry.long("awaken", 0)
            val defaultSp = when {
                helper -> r.helperSp
                leader && awaken > 0 -> r.awakenedLeaderSp
                else -> r.ownStartSp
            }
            val crit = (values["crit"] as? JInt)?.value?.toLong() ?: (values["unq"] as? JInt)?.value?.toLong() ?: 0L
            actors.add(jobj("side" to 0, "position" to entry.long("position", 0), "packed_hero_id" to packed,
                "uid" to entry.long("uid", 0), "level" to entry.long("level", 0), "awaken" to awaken,
                "max_hp" to values.long("hp", 0), "atk" to values.long("atk", 0), "def" to values.long("def", 0),
                "crit" to crit, "rates" to fields["rates"], "class_factor" to fields["class_factor"],
                "skills" to heroSkills(packed, fields, inputs, awaken), "start_sp" to entry.long("start_sp", defaultSp),
                "leader" to leader, "helper" to helper, "player" to true))
        }
        return actors
    }

    // === deciders ===================================================================================================
    /** Every random decision goes through one object (the RNG path; a forced-script decider could replace it). */
    interface Decider {
        fun pick(unit: Unit, skill: JObj?, candidates: List<Unit>, count: Long): List<Unit>
        fun effectPick(unit: Unit, effect: JObj, candidates: List<Unit>, count: Long): List<Unit>
        fun redirect(unit: Unit, skill: JObj, index: Int, target: Unit): Pair<Unit, Long>
        fun outcome(unit: Unit, skill: JObj, index: Int, target: Unit): Long
        fun coefficient(unit: Unit, skill: JObj, index: Int, target: Unit): Long
        fun damage(unit: Unit, skill: JObj, index: Int, target: Unit, outcome: Long, model: Long, kind: String): Long
        fun counter(blocker: Unit, attacker: Unit): Boolean
        fun effect(unit: Unit, skill: JObj, effect: JObj, target: Unit): Boolean
    }

    /** Answers every outcome decision from one SplitMix64 stream in the fixed draw order of the module doc. */
    class RandomDecider(seed: Long, private val rules: Rules) : Decider {
        private val rng = SplitMix64(seed.toULong())

        override fun pick(unit: Unit, skill: JObj?, candidates: List<Unit>, count: Long): List<Unit> {
            val pool = candidates.toMutableList()
            val out = ArrayList<Unit>()
            repeat(minOf(count, pool.size.toLong()).toInt()) { out.add(pool.removeAt(rng.below(pool.size.toLong()).toInt())) }
            return out
        }

        override fun effectPick(unit: Unit, effect: JObj, candidates: List<Unit>, count: Long): List<Unit> =
            pick(unit, null, candidates, count)

        override fun redirect(unit: Unit, skill: JObj, index: Int, target: Unit): Pair<Unit, Long> = target to 0L

        override fun outcome(unit: Unit, skill: JObj, index: Int, target: Unit): Long {
            if (rng.chance(target.rate("dodge"))) return OUTCOME_MISS
            if ((!skill.bool("special") || rules.blockOnSpecial) && rng.chance(target.rate("block"))) return OUTCOME_BLOCK
            var crit = unit.rate("crit")
            if (!skill.bool("special")) crit = Math.floorDiv(crit, rules.ordinaryCritDivisor)
            return if (rng.chance(crit)) OUTCOME_CRIT else OUTCOME_HIT
        }

        override fun coefficient(unit: Unit, skill: JObj, index: Int, target: Unit): Long {
            val lo = skill.long("coef_lo", 0)
            val hi = skill.long("coef_hi", 0)
            return if (hi > lo) lo + rng.below(hi - lo + 1) else lo
        }

        override fun damage(unit: Unit, skill: JObj, index: Int, target: Unit, outcome: Long, model: Long, kind: String): Long = model

        override fun counter(blocker: Unit, attacker: Unit): Boolean = rng.chance(rules.counterChance)

        override fun effect(unit: Unit, skill: JObj, effect: JObj, target: Unit): Boolean = rng.chance(effect.long("chance", 0))
    }

    // === units and battle ===========================================================================================
    internal class Status(val id: Long, val type: Long, val value: Long, val seq: Long) {
        var expireRound: Long? = null
        var turns: Long = 0
        var endRound: Long? = null
    }

    class Unit(val spec: JObj, val side: Int, val index: Int, rules: Rules) {
        val pos: Int = (spec["position"] as JInt).value.toInt()
        val gpos: Long
        val maxHp: Long
        var hp: Long
        var sp: Long
        internal val statuses = ArrayList<Status>()
        val player: Boolean
        val normal: JObj?
        val special: JObj?

        init {
            if (pos !in 1..6) throw IllegalArgumentException("Actor position $pos outside 1..6")
            gpos = side.toLong() * 6 + pos
            maxHp = spec.long("max_hp", 0)
            hp = maxOf(0L, minOf(maxHp, spec.long("hp", maxHp)))
            sp = maxOf(0L, minOf(rules.spMax, spec.long("start_sp", rules.ownStartSp)))
            player = (spec["player"] as? JBool)?.value ?: (side == 0)
            val skills = spec["skills"] as? JObj ?: JObj()
            normal = skills["normal"] as? JObj
            special = skills["special"] as? JObj
        }

        val alive: Boolean get() = hp >= 1
        val lane: Long get() = ((pos - 1) % 3).toLong()
        val front: Boolean get() = pos <= 3
        val stunned: Boolean get() = statuses.any { it.type == D_STUN && it.turns > 0 }

        fun rate(name: String): Long = ((spec["rates"] as? JObj)?.get(name) as? JInt)?.value?.toLong() ?: 0L

        /** Base stat with active stat-buff statuses (types 202/203/204 as +value bps). */
        fun stat(name: String): Double {
            val base = spec.long(name, 0)
            val code = BUFF_CODE[name]
            val bonus = if (code != null) statuses.filter { it.type == code }.sumOf { it.value } else 0L
            return if (bonus != 0L) maxOf(0.0, base.toDouble() * (1 + bonus.toDouble() / 10000.0)) else base.toDouble()
        }
    }

    private class Battle(own: List<JObj>, enemy: List<JObj>, val rules: Rules, val decider: Decider, trace: Boolean) {
        val sides: List<List<Unit>>
        val trace: JArr? = if (trace) JArr() else null
        val leader: Unit?
        var seq = 0L
        var roundNo = 0L

        init {
            sides = listOf(own.mapIndexed { i, spec -> Unit(spec, 0, i, rules) },
                enemy.mapIndexed { i, spec -> Unit(spec, 1, i, rules) })
            for (side in sides) {
                if (side.isEmpty()) throw IllegalArgumentException("Each side needs at least one actor")
                if (side.map { it.pos }.toSet().size != side.size) throw IllegalArgumentException("Duplicate actor position on one side")
            }
            val leaders = sides[0].filter { (it.spec["leader"] as? JBool)?.value == true }
            if (leaders.size > 1) throw IllegalArgumentException("At most one leader")
            leader = leaders.firstOrNull()
        }

        fun living(side: Int): List<Unit> = sides[side].filter { it.alive }
        fun over(): Boolean = living(0).isEmpty() || living(1).isEmpty()
        fun unitAt(side: Int, pos: Int): Unit? = sides[side].firstOrNull { it.pos == pos }

        fun apply(e: JObj) {
            if ((e.long("outcome_raw", 0) and 0xBFL) == OUTCOME_MISS) return
            val position = e.long("position", 0)
            val unit = unitAt(((position - 1) / 6).toInt(), ((position - 1) % 6 + 1).toInt()) ?: return
            val dispatcher = e.long("dispatcher", 0)
            val delta = e.long("delta", 0)
            if (dispatcher == D_HP) unit.hp = maxOf(0L, minOf(unit.maxHp, unit.hp + delta))
            else if (dispatcher == 3L || dispatcher == D_SP) unit.sp = maxOf(0L, minOf(rules.spMax, unit.sp + delta))
        }

        fun record(vararg values: Pair<String, Any?>) {
            if (trace != null) trace.add(jobj("round" to roundNo, *values))
        }

        fun order(): List<Unit> {
            val seq = ArrayList<Unit>()
            if (leader != null) seq.add(leader)
            for (slot in 1..6) for (side in 0..1) {
                val unit = unitAt(side, slot)
                if (unit != null && unit !== leader) seq.add(unit)
            }
            return seq
        }

        fun type1(unit: Unit, foes: List<Unit>, backFirst: Boolean = false): Unit? {
            val front = foes.filter { it.front }
            val back = foes.filter { !it.front }
            val rows = if (backFirst) listOf(back, front) else listOf(front, back)
            val row = rows[0].ifEmpty { rows[1] }
            if (row.isEmpty()) return null
            return row.sortedBy { it.gpos }.minWithOrNull(compareBy({ abs(it.lane - unit.lane) }, { it.lane }))
        }

        fun select(unit: Unit, skill: JObj, foes: List<Unit>, allies: List<Unit>): List<Unit> {
            val ttype = skill.long("target_type", 1)
            if (ttype == 12L) {
                return if (allies.isNotEmpty())
                    listOf(allies.minWithOrNull(compareBy({ Math.floorDiv(it.hp * 10000, maxOf(1L, it.maxHp)) }, { it.gpos }))!!)
                else emptyList()
            }
            if (foes.isEmpty()) return emptyList()
            val ordered = foes.sortedBy { it.gpos }
            rules.randomPickCounts[ttype]?.let { return decider.pick(unit, skill, ordered, it) }
            if (ttype == 13L) return listOf(type1(unit, foes, backFirst = true)!!)
            val base = type1(unit, foes)!!
            if (ttype == 2L) return ordered.filter { it.lane == base.lane }
            if (ttype == 3L) return listOf(base) + ordered.filter { it.front == base.front && it !== base }
            if (ttype == 4L) return foes
            if (ttype == 11L) {
                val back = ordered.filter { !it.front }
                return back.ifEmpty { ordered.filter { it.front } }
            }
            if (ttype == 21L) return listOf(ordered.minWithOrNull(compareBy({ it.hp }, { it.gpos }))!!)
            return listOf(base)
        }

        fun effectTargets(effect: JObj, unit: Unit, hits: List<Triple<Unit, Long, Double>>, base: Unit?,
                          dodged: Set<Long>): List<Unit> {
            val kind = effect.long("kind", 0)
            val foes = living(1 - unit.side).filter { it.gpos !in dodged }
            val allies = living(unit.side)
            val ordered = foes.sortedBy { it.gpos }
            if (kind == 1L) return if (base != null && base.alive && base.gpos !in dodged) listOf(base) else emptyList()
            if (kind == 2L || kind == 3L) {
                if (base == null) return emptyList()
                if (kind == 2L) return ordered.filter { it.lane == base.lane }
                return (if (base in foes) listOf(base) else emptyList()) + ordered.filter { it.front == base.front && it !== base }
            }
            if (kind == 4L) return foes
            if (kind == 5L) return if (unit.alive) listOf(unit) else emptyList()
            if (kind == 7L) return (if (unit.alive) listOf(unit) else emptyList()) + allies.filter { it !== unit && it.front == unit.front }
            if (kind == 8L) return (if (unit.alive) listOf(unit) else emptyList()) + allies.filter { it !== unit }
            if (kind == 9L || kind == 10L) return if (allies.isNotEmpty())
                listOf(allies.maxWithOrNull(compareBy({ Math.floorDiv((it.maxHp - it.hp) * 10000, maxOf(1L, it.maxHp)) }, { -it.gpos }))!!)
            else emptyList()
            if (kind == 11L) {
                val back = ordered.filter { !it.front }
                return back.ifEmpty { ordered.filter { it.front } }
            }
            if (kind == 19L) return if (allies.isNotEmpty()) listOf(allies.minWithOrNull(compareBy({ it.hp }, { it.gpos }))!!) else emptyList()
            if (kind == 20L) return decider.effectPick(unit, effect, ordered, rules.effectRandomCount)
            val seen = HashSet<Long>()
            val out = ArrayList<Unit>()
            for ((target, outcome, _) in hits) {
                if (outcome != OUTCOME_MISS && target.alive && target.gpos !in seen) {
                    seen.add(target.gpos); out.add(target)
                }
            }
            return out
        }

        fun damage(unit: Unit, target: Unit, skill: JObj, outcome: Long, coef: Long): Long {
            val statName = if (skill.bool("special")) rules.specialStat else "atk"
            return damageInt(unit.stat(statName), target.stat("def"), unit.spec.long("class_factor", 0),
                target.spec.long("class_factor", 0), unit.player, skill.bool("special"), outcome, coef, rules)
        }

        fun damageUnrounded(unit: Unit, target: Unit, skill: JObj, outcome: Long, coef: Long): Double {
            val statName = if (skill.bool("special")) rules.specialStat else "atk"
            return damageValue(unit.stat(statName), target.stat("def"), unit.spec.long("class_factor", 0),
                target.spec.long("class_factor", 0), unit.player, skill.bool("special"), outcome, coef, rules, unrounded = true)
        }

        fun healAmount(unit: Unit, skill: JObj, coef: Long): Long {
            val side = if (unit.player) rules.sideFactorPlayer else rules.sideFactorMonster
            return maxOf(0L, Math.floor(coef.toDouble() / 10000.0 * rules.specialSigma * side * unit.stat(rules.specialStat)).toLong())
        }

        fun addStatus(target: Unit, effect: JObj, turns: Long? = null) {
            seq += 1
            val effectId = effect.long("id", 0)
            val duration = effect.long("duration", 0)
            for (s in target.statuses) {
                if (s.id == effectId && s.expireRound == null) {
                    if (turns != null) s.turns = maxOf(s.turns, turns)
                    else s.endRound = roundNo + maxOf(1L, duration) - 1
                    return
                }
            }
            val status = Status(effectId, effect.long("type", 0), effect.long("value", 0), seq)
            if (turns != null) {
                status.turns = turns
                status.endRound = null
            } else {
                status.turns = 0
                status.endRound = if (duration > 0) roundNo + duration - 1 else null
            }
            target.statuses.add(status)
        }

        fun effectEntries(effect: JObj, unit: Unit, target: Unit, success: Boolean, dealt: Double): List<JObj> {
            val effectId = effect.long("id", 0)
            if (!success) return listOf(entry(target.gpos, 0, 0, OUTCOME_MISS, effectId))
            val etype = effect.long("type", 0)
            val value = effect.long("value", 0)
            if (etype == 207L) {
                val heal = lifesteal(dealt, value)
                return if (heal > 0) listOf(entry(target.gpos, heal, D_HP, OUTCOME_SECONDARY, effectId)) else emptyList()
            }
            if (etype == 205L) return listOf(entry(target.gpos, pct(target.maxHp, value), D_HP, OUTCOME_SECONDARY, effectId))
            if (etype == 1101L) return listOf(entry(target.gpos, -value, D_SP, OUTCOME_SECONDARY, effectId))
            if (etype == D_STUN) {
                addStatus(target, effect, turns = maxOf(1L, effect.long("duration", 0)))
                return listOf(entry(target.gpos, value, D_STUN, OUTCOME_SECONDARY, effectId))
            }
            addStatus(target, effect)
            return listOf(entry(target.gpos, value, etype, OUTCOME_SECONDARY, effectId))
        }

        fun turn(unit: Unit, skill: JObj, rec: JObj) {
            val rn = roundNo
            val foes = living(1 - unit.side)
            val allies = living(unit.side)
            val base = type1(unit, foes)
            val targets = select(unit, skill, foes, allies)
            val entries = JArr()
            val hits = ArrayList<Triple<Unit, Long, Double>>()
            for ((index, target0) in targets.withIndex()) {
                if (skill.long("target_type", 1) == 12L) {
                    val coef = decider.coefficient(unit, skill, index, target0)
                    val model = healAmount(unit, skill, coef)
                    val amount = decider.damage(unit, skill, index, target0, OUTCOME_HIT, model, "heal")
                    val e = entry(target0.gpos, amount, D_HP, OUTCOME_HIT)
                    entries.add(e); apply(e)
                    hits.add(Triple(target0, OUTCOME_HIT, 0.0))
                    continue
                }
                val (target, helper) = decider.redirect(unit, skill, index, target0)
                val outcome = decider.outcome(unit, skill, index, target)
                var coef = skill.long("coef_lo", 0)
                if (skill.bool("special") && outcome != OUTCOME_MISS) coef = decider.coefficient(unit, skill, index, target)
                val model: Long
                val modelRaw: Double
                if (outcome == OUTCOME_MISS) {
                    model = rules.dodgeDelta; modelRaw = 0.0
                } else {
                    modelRaw = damageUnrounded(unit, target, skill, outcome, coef)
                    model = maxOf(0L, Math.floor(modelRaw).toLong())
                }
                val dealt = decider.damage(unit, skill, index, target, outcome, model, "hit")
                record("kind" to "hit", "attacker" to unit.gpos, "target" to target.gpos, "skill" to skill.long("id", 0),
                    "special" to skill.bool("special"), "outcome" to outcome, "coef" to coef, "model" to model,
                    "damage" to dealt, "target_hp_before" to target.hp)
                val e = entry(target.gpos, -dealt, D_HP, outcome or (if (helper != 0L) 0x40L else 0L), helper)
                entries.add(e); apply(e)
                val unrounded = if (dealt == model) modelRaw else dealt.toDouble()
                hits.add(Triple(target, outcome, if (outcome != OUTCOME_MISS) unrounded else 0.0))
            }
            var counterBy: Unit? = null
            if (!skill.bool("special") && hits.size == 1 && hits[0].second == OUTCOME_BLOCK && hits[0].first.alive
                && unit.alive && hits[0].first.normal != null) {
                if (decider.counter(hits[0].first, unit)) counterBy = hits[0].first
            }
            val dealtTotal = hits.filter { it.second != OUTCOME_MISS }.sumOf { it.third }
            val dodged = hits.filter { it.second == OUTCOME_MISS }.map { it.first.gpos }.toSet()
            for (effect in skill.arr("effects")) {
                val ej = effect.asObj
                if (ej.long("type", 0) == 207L && dealtTotal <= 0.0) continue
                for (target in effectTargets(ej, unit, hits, base, dodged)) {
                    val success = decider.effect(unit, skill, ej, target)
                    for (e in effectEntries(ej, unit, target, success, dealtTotal)) {
                        entries.add(e); apply(e)
                    }
                }
            }
            val spEntry: JObj? = when {
                skill.bool("special") -> entry(unit.gpos, -rules.spCost, D_SP, OUTCOME_SECONDARY)
                hits.any { it.second != OUTCOME_MISS } -> entry(unit.gpos, rules.spGain, D_SP, OUTCOME_SECONDARY)
                else -> null
            }
            if (spEntry != null) { entries.add(spEntry); apply(spEntry) }
            rec.arr("attacks").add(jobj("position" to unit.gpos, "skill_id" to skill.long("id", 0), "targets" to entries))
            if (counterBy != null && counterBy.alive && unit.alive) {
                val nskill = counterBy.normal!!
                val model = damage(counterBy, unit, nskill, OUTCOME_COUNTER, nskill.long("coef_lo", 0))
                val dealt = decider.damage(counterBy, nskill, 0, unit, OUTCOME_COUNTER, model, "counter")
                record("kind" to "counter", "attacker" to counterBy.gpos, "target" to unit.gpos, "skill" to nskill.long("id", 0),
                    "special" to false, "outcome" to OUTCOME_COUNTER, "coef" to nskill.long("coef_lo", 0), "model" to model,
                    "damage" to dealt, "target_hp_before" to unit.hp)
                val e = entry(unit.gpos, -dealt, D_HP, OUTCOME_COUNTER)
                apply(e)
                rec.arr("attacks").add(jobj("position" to counterBy.gpos, "skill_id" to nskill.long("id", 0), "targets" to jarr(e)))
            }
        }

        fun gifts(): JArr {
            val out = JArr()
            for (side in 0..1) for (unit in sides[side]) {
                val gift = unit.spec["gift"] as? JObj ?: continue
                if (!unit.alive) continue
                val allies = listOf(unit) + living(side).filter { it !== unit }.sortedBy { it.gpos }
                out.add(jobj("position" to unit.gpos, "gift_id" to gift.long("id", 0),
                    "targets" to allies.toJArr {
                        entry(it.gpos, gift.long("value", 0), gift.long("dispatcher", 0), OUTCOME_SECONDARY)
                    }))
            }
            return out
        }

        fun roundEnd(rec: JObj) {
            val rn = roundNo
            for (side in 0..1) for (unit in sides[side]) {
                if (!unit.alive) { unit.statuses.clear(); continue }
                val keep = ArrayList<Status>()
                for (status in unit.statuses.sortedBy { it.seq }) {
                    val due = status.expireRound == rn || (status.type != D_STUN && status.endRound == rn)
                    if (due) rec.arr("targets").add(entry(unit.gpos, -status.value, status.type, OUTCOME_SECONDARY, status.id, 1))
                    else keep.add(status)
                }
                unit.statuses.clear(); unit.statuses.addAll(keep)
            }
        }

        fun run(): Pair<JArr, JArr> {
            val initial = gifts()
            val rounds = JArr()
            for (rn in 1..rules.roundLimit) {
                roundNo = rn
                val rec = jobj("attacks" to JArr(), "targets" to JArr(), "totems" to JArr(), "combos" to JArr(), "changes" to JArr())
                for (unit in order()) {
                    if (over()) break
                    if (!unit.alive) continue
                    val stun = unit.statuses.firstOrNull { it.type == D_STUN && it.turns > 0 }
                    if (stun != null) {
                        stun.turns -= 1
                        if (stun.turns == 0L) stun.expireRound = rn
                        continue
                    }
                    val skill = if (unit.sp >= rules.spMax) unit.special else unit.normal
                    if (skill == null) continue
                    turn(unit, skill, rec)
                }
                roundEnd(rec)
                rounds.add(rec)
                if (over()) break
            }
            return initial to rounds
        }
    }

    private fun hpShare(units: List<Unit>): Long {
        val total = units.sumOf { it.maxHp }
        return if (total != 0L) Math.floorDiv(10000L * units.sumOf { it.hp }, total) else 0L
    }

    /** result 2 iff the enemy side is wiped; stars/logos POLICY/CALC. Returns (result, stars, logo, tip). */
    private fun tail(battle: Battle, rules: Rules, timedOut: Boolean): LongArray {
        val win = battle.living(1).isEmpty() && battle.living(0).isNotEmpty()
        if (win) {
            val share = hpShare(battle.sides[0])
            val stars = rules.starThresholds.first { share >= it.first }.second
            val logo = rules.winLogoThresholds.first { share >= it.first }.second
            return longArrayOf(2, stars, logo, 0)
        }
        val enemies = battle.sides[1]
        val total = enemies.sumOf { it.maxHp }
        val lost = total - enemies.sumOf { it.hp }
        var logo = rules.loseLogoBase + (if (total != 0L) Math.floorDiv(rules.loseLogoSpan * lost, total) else 0L)
        logo = maxOf(rules.loseLogoBase, minOf(rules.loseLogoBase + 5, logo))
        return longArrayOf(0, 0, logo, if (timedOut) rules.timeoutLoseTip else rules.loseTip)
    }

    private fun actorRecord(unit: Unit, rules: Rules): JObj {
        val spec = unit.spec
        return jobj("lineup_raw" to rules.lineup, "position" to unit.pos.toLong(), "identifier_raw" to spec.long("uid", 0),
            "packed_hero_id" to spec.long("packed_hero_id", 0), "max_hp" to unit.maxHp,
            "current_hp" to spec.long("hp", unit.maxHp), "sp" to spec.long("start_sp", rules.ownStartSp),
            "value_18_raw" to spec.long("level", 1), "ability_rate" to rules.abilityRate, "value_1a_raw" to spec.long("awaken", 0))
    }

    /** Convenience composition for the campaign transaction: enemy side + own actors, then [simulate]. */
    fun campaignBattle(stageId: Long, lineup: List<JValue>, stats: (JValue) -> JObj, inputs: DailyInputs, seed: Long,
                       ownName: String = "", rules: JObj? = null, battleType: Long? = null): JObj {
        val side = enemyActors(stageId, inputs, rules)
        val own = ownActorsFromStats(lineup, stats, inputs, rules)
        return simulate(own, side, battleType = battleType ?: side.long("battle_type", 202),
            background = side.long("background", 0), seed = seed, rules = rules, ownName = ownName)
    }

    /**
     * Simulate one campaign battle and build its S4 single report through the codec. Returns a dict with
     * `result, stars, rounds, value_231, value_232, report, payload (hex), seed (u64), engine_version, timed_out,
     * final, trace`. The report is round-tripped through [BattleReport] before it is returned.
     */
    fun simulate(ownActors: List<JValue>, enemyActors: JValue, battleType: Long, background: Long, seed: Long,
                 rules: JObj? = null, ownName: String = "", enemyName: String? = null, trace: Boolean = false,
                 reward: JObj? = null, decider: Decider? = null, sideMeta: List<JObj>? = null): JObj {
        val r = Rules.from(rules)
        var enemyNameV = enemyName
        val enemyList: List<JObj> = when (enemyActors) {
            is JObj -> {
                if (enemyNameV == null) enemyNameV = enemyActors.strOrNull("side_name") ?: ""
                enemyActors.arr("actors").map { it.asObj }
            }
            is JArr -> enemyActors.map { it.asObj }
            else -> throw IllegalArgumentException("enemy_actors must be a dict or a list")
        }
        val ownList = ownActors.map { it.asObj }
        val d = decider ?: RandomDecider(seed, r)
        val battle = Battle(ownList, enemyList, r, d, trace)
        val (initial, rounds) = battle.run()
        val timedOut = rounds.size.toLong() >= r.roundLimit && !battle.over()
        val t = tail(battle, r, timedOut)
        val result = t[0]; val stars = t[1]; val logo = t[2]; val tip = t[3]
        val names = listOf(ownName, enemyNameV ?: "")
        val sides = JArr()
        for (side in 0..1) {
            val meta = sideMeta?.getOrNull(side) ?: JObj()
            sides.add(jobj("name_hex" to names[side].toByteArray(Charsets.UTF_8).toHexString(),
                "actors" to battle.sides[side].toJArr { actorRecord(it, r) },
                "value_250_raw" to meta.long("value_250_raw", 0), "value_258_raw" to meta.long("value_258_raw", 0),
                "value_25c_raw" to meta.long("value_25c_raw", 0)))
        }
        val report = jobj("version" to r.reportVersion, "battle_type" to battleType, "identifier_80_raw" to background,
            "sides" to sides, "result_raw" to result, "display_stars" to stars, "initial_effects" to initial,
            "rounds" to rounds, "flag_208_raw" to 0, "value_210_raw" to 0, "string_218_hex" to "",
            "flag_230_raw" to r.skipButton, "value_231_raw" to logo, "value_232_raw" to tip,
            "reward" to (reward ?: BattleReport.emptyReward(14)))
        val payload = BattleReport.encode(report)
        if (BattleReport.parse(payload) != report) throw IllegalArgumentException("Engine report does not round-trip through the codec")
        val final = JArr()
        for (side in battle.sides) for (u in side) final.add(jobj("position" to u.gpos, "hp" to u.hp, "sp" to u.sp, "alive" to u.alive))
        return jobj("result" to result, "stars" to stars, "rounds" to rounds.size.toLong(), "value_231" to logo,
            "value_232" to tip, "report" to report, "payload" to payload.toHexString(), "seed" to seed.toULong(),
            "engine_version" to ENGINE_VERSION, "timed_out" to timedOut, "final" to final,
            "trace" to (battle.trace ?: JNull))
    }
}
