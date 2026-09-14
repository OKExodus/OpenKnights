package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.gamedata.GameTable
import io.github.okexodus.openknights.protocol.TypedValues
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.StateStore
import java.math.BigInteger

/**
 * Main character (leader) class change (`change_job.py`) — item 10726 "Main character transfer card".
 *
 * Native: using the card opens `TipsChangeJob`; the target cards are the same-tier Warrior / Mage / Hunter bases, grade
 * and super-class digit kept. The client looks the `zhujuezhuanzhi` row up by the current base (102) and grade (104)
 * only and shows Gold = col 109 (target col 105) or 114 (target col 110). C2561 = one u8, hero.csv col 103 of the target
 * base (1 Warrior, 2 Mage, 3 Hunter). S3040 = u32 hero UID + the hero's typed field map; it closes the window.
 * CALC: the new base's growth at the same grade, its super-class property and awaken slots, development kept.
 * POLICY (never captured): the cost the client showed; the reply order (card, Gold, S2850, book frames, S3040 last);
 * Astral Power class series move with the class at the same level offset (4800 Warrior / 4900 Mage / 5000 Hunter) and
 * the Warrior-only 4700 series is set aside when leaving Warrior and restored on return; a reborn leader is refused.
 */
object ChangeJob {
    const val C_CHANGE_JOB = 2561
    const val S_CHANGE_JOB = 3040
    const val CARD_ITEM = 10726L
    val CLASSES = listOf(1L, 2L, 3L)                              // hero.csv col 103: Warrior, Mage, Hunter
    val CLASS_SERIES = linkedMapOf(1L to 4800L, 2L to 4900L, 3L to 5000L)   // Astral Power class series
    const val WARRIOR_ONLY_SERIES = 4700L                        // "Invulnerability" (Warrior only)
    const val SERIES_SPAN = 100L
    const val ERROR_SAME_CLASS = 102                             // the client disables the current card (POLICY code)
    const val ERROR_NO_ROW = 5000                                // text 5000 when the client finds no row

    /** `decode_request(payload)`: one class byte 1-3. */
    fun decodeRequest(payload: ByteArray): JObj {
        if (payload.size != 1 || (payload[0].toLong() and 0xFF) !in CLASSES) throw Acquisition.Rejected("C2561 carries one class byte 1-3")
        return jobj("target_class" to (payload[0].toLong() and 0xFF))
    }

    /** `_n(fields, key)`: digits (one leading "-" run allowed) as an integer, else 0. */
    private fun n(row: GameTable.Row, key: String): Long = PyValues.digitInt(row.field(key))

    /** `switch_row(inputs, base, grade)`: the first zhujuezhuanzhi row of (base, grade) (super col 200 not consulted). */
    fun switchRow(inputs: DailyInputs, base: Long, grade: Long): GameTable.Row? =
        inputs.tableRows("zhujuezhuanzhi").firstOrNull { n(it, "102") == base && n(it, "104") == grade }

    /** `hero_class(inputs, base)`: hero.csv col 103 of a base (0 when unknown). */
    fun heroClass(inputs: AcquisitionInputs, base: Long): Long = inputs.single("hero", base)?.let { n(it, "103") } ?: 0L

    private fun seriesOf(skill: Long): Long? {
        for (series in listOf(WARRIOR_ONLY_SERIES) + CLASS_SERIES.values) if (series <= skill && skill < series + SERIES_SPAN) return series
        return null
    }

    private fun compareSkill(a: JValue, b: JValue): Int {
        val x = a.asArr
        val y = b.asArr
        for (i in 0 until minOf(x.size, y.size)) {
            val c = x[i].big.compareTo(y[i].big)
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }

    /**
     * `move_skills(document, uid, old_class, new_class)`: the leader's Astral Power list with its class series moved to
     * the new class (same level offset); the Warrior-only series is set aside (`class_change_stash`) when leaving Warrior
     * and restored on return. Returns (document copy, skills) — skills null when the hero has no entry.
     */
    fun moveSkills(document: JObj, uid: Long, oldClass: Long, newClass: Long): Pair<JObj, JArr?> {
        val doc = document.deepCopy()
        val entry = doc.arr("heroes").map { it.asObj }.firstOrNull { (it["uid"] as? JInt)?.value == BigInteger.valueOf(uid) } ?: return doc to null
        if ("class_change_stash" !in doc) doc["class_change_stash"] = JObj()
        val stash = doc.obj("class_change_stash")
        val skills = ArrayList<JValue>()
        for (pair in entry.arr("skills")) {
            val skill = pair.asArr[0].long
            val progress = pair.asArr[1]
            val series = seriesOf(skill)
            if (series == CLASS_SERIES[oldClass]) {
                // an unknown old class meets a skill outside every series (None == None): the reference fails here
                if (series == null) throw IllegalStateException("unsupported operand type(s) for -: 'int' and 'NoneType'")
                skills.add(jarr(CLASS_SERIES.getValue(newClass) + (skill - series), progress))
            } else if (series == WARRIOR_ONLY_SERIES && newClass != 1L) {
                stash[uid.toString()] = jarr(skill, progress)
            } else {
                skills.add(jarr(skill, progress))
            }
        }
        if (newClass == 1L && skills.none { seriesOf(it.asArr[0].long) == WARRIOR_ONLY_SERIES }) {
            skills.add(stash.remove(uid.toString()) ?: jarr(WARRIOR_ONLY_SERIES, 0))
        }
        if (stash.isEmpty()) doc.remove("class_change_stash")
        entry["skills"] = JArr(skills.sortedWith { a, b -> compareSkill(a, b) }.toMutableList())
        return doc to entry.arr("skills")
    }

    private fun widthOf(tag: Int): Int = when (TypedValues.WIDTH_FORMAT.getValue(tag).uppercaseChar()) { 'B' -> 8; 'H' -> 16; 'I' -> 32; else -> 64 }

    /** `plan_change_job(payload, owned, current, inputs, evolution_inputs)`: the leader's class change (module doc). */
    fun planChangeJob(payload: ByteArray, owned: Owned, current: StateStore.Current, inputs: DailyInputs, evolutionInputs: EvolutionInputs): Plan {
        val reject = HeroStats.Reject { m, c -> Acquisition.Rejected(m, c) }
        val request = decodeRequest(payload)
        val targetClass = request.long("target_class")
        val leaders = owned.state.arr("heroes").filter { Py.truthy(evolutionInputs(HeroFortify.heroUidOf(it.asArr, 1).toLong())["is_leader"]) }
        if (leaders.size != 1) throw Acquisition.Rejected("Expected exactly one leader-class hero")
        val fields = leaders[0].asArr
        val uid = HeroFortify.heroUidOf(fields).toLong()
        val template = HeroFortify.heroUidOf(fields, 1).toLong()
        val dims = HeroDictionaryProgression.unpackHeroId(template)
        val oldClass = heroClass(inputs, dims.baseId)
        if (oldClass == targetClass) throw Acquisition.Rejected("The leader already has that class", ERROR_SAME_CLASS)
        val row = switchRow(inputs, dims.baseId, dims.grade) ?: throw Acquisition.Rejected("No class-change row for this leader tier", ERROR_NO_ROW)
        val target = listOf("105" to "109", "110" to "114").firstOrNull { (b, _) -> n(row, b) != 0L && heroClass(inputs, n(row, b)) == targetClass }
            ?.let { (b, g) -> n(row, b) to n(row, g) }
            ?: throw Acquisition.Rejected("The class-change row offers no base of that class", ERROR_NO_ROW)
        val (targetBase, goldCost) = target
        val resolved = HeroStats.resolveProfile(fields, evolutionInputs(template), reject)
        if (resolved.obj("profile").arr("reborn").any { Py.truthy(it) }) throw Acquisition.Rejected("A reborn leader's class change is not supported yet")
        val newTemplate = targetBase * 1000 + dims.hundredsDigit * 100 + dims.grade
        val newInputs = try {
            evolutionInputs(newTemplate)
        } catch (e: IllegalArgumentException) {
            throw Acquisition.Rejected("No configuration for $newTemplate", ERROR_NO_ROW)
        }
        val fullGrow = HeroStats.recomputeGrow(newInputs.arr("raw_511_514").map { it.long }, newInputs.long("current_potential_rate")).map { BigInteger.valueOf(it) }
        val permille = HeroStats.statPermille(newTemplate, newInputs, resolved.obj("profile").int("awaken"), reject)
        val stats = HeroStats.baseStats(fullGrow, resolved.int("level"), dims.grade, permille, resolved.arr("dev").map { it.big })
        val wireGrow = HeroStats.wireGrowBits(fullGrow, resolved.arr("grow_widths").map { it.long.toInt() })
        val after = LinkedHashMap<Long, BigInteger>()
        after[HeroStats.TEMPLATE] = BigInteger.valueOf(newTemplate)
        HeroStats.GROW_IDS.zip(wireGrow).forEach { (id, v) -> after[id] = v }
        HeroStats.STAT_IDS.zip(stats).forEach { (id, v) -> after[id] = v }
        val values = resolved.obj("values")
        val afterFields = JArr()
        for (f in fields) {
            var field = f.asObj
            val id = field.long("id")
            val newBits = after[id]
            if (newBits != null) {
                if (newBits >= BigInteger.ONE.shiftLeft(widthOf(field.obj("value").long("tag").toInt()))) {
                    throw Acquisition.Rejected("Hero field $id would overflow its wire width")
                }
                field = jobj("id" to field["id"], "value" to JObj(LinkedHashMap(field.obj("value").map)).also { it["bits"] = JInt(newBits) })
            }
            afterFields.add(field)
        }
        // costs: the card, then the Gold the client showed (role_add refuses a shortfall with 4000)
        val frames = ArrayList(owned.consumeTemplate(CARD_ITEM, 1))
        frames.add(owned.roleAdd(Acquisition.GOLD, -goldCost))
        owned.state["heroes"] = JArr(owned.state.arr("heroes").mapTo(ArrayList<JValue>()) { f ->
            if (HeroFortify.heroUidOf(f.asArr).toLong() == uid) afterFields else f
        })
        val god = current.godSkills
        var skills: JArr? = null
        if (god != null) {
            val (document, moved) = moveSkills(god.obj("document"), uid, oldClass, targetClass)
            skills = moved
            owned.godDocument = document
            if (moved != null) frames.add(GodSkills.UPDATE_OPCODE to GodSkills.encodeGodSkillUpdate(uid, moved.map { it.asArr }))
        }
        frames += owned.collect("hero_collection", newTemplate, Acquisition.HERO_BOOK)
        frames.add(S_CHANGE_JOB to WireWriter().u32(uid).also { TypedValues.encodeFields(afterFields, it) }.bytes())
        return Plan(jobj("hero_uid" to uid, "old_template" to template, "new_template" to newTemplate, "old_class" to oldClass,
            "new_class" to targetClass, "gold_cost" to goldCost, "switch_row" to n(row, "101"),
            "stats_before" to HeroStats.STAT_IDS.map { values.obj(it.toString())["bits"] }, "stats_after" to stats,
            "astral_skills_after" to skills, "evidence_class" to "native_use_table_calc_policy_order"), frames)
    }
}
