package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import java.math.BigInteger

/**
 * Login repair of a leader's super-class digit (`leader_repair.py`). A leader that took an earlier star's super step
 * could reach later stars with the wrong packed hundreds digit; `leader_digit` gives the digit the rows call for (the
 * grade's one row, or at a super grade 1 only when the super step to this template was taken). A mismatching leader
 * gets that digit and its four stats (the property-533 factor follows the digit; growth, level, EXP, development and
 * awaken stay). POLICY repair: one audited revision before the login S18, no frame.
 */
object LeaderRepair {
    const val TEMPLATE = 1

    /** Templates reached by a super step: (old, new) `evolve_leader_hero` pairs at the same grade whose digit rose. */
    fun superReachedTemplates(historyPairs: List<Pair<Long?, Long?>>): Set<Long> {
        val reached = LinkedHashSet<Long>()
        for ((old, new) in historyPairs) {
            if (old != null && new != null && old != 0L && new != 0L && Math.floorMod(old, 100L) == Math.floorMod(new, 100L) &&
                Math.floorDiv(old, 1000L) == Math.floorDiv(new, 1000L) && new > old) reached.add(new)
        }
        return reached
    }

    /** The one owned hero whose evolution inputs say leader, else null (a hero whose inputs cannot be formed is skipped). */
    fun leader(owned: Owned, evolutionInputs: EvolutionInputs): JArr? {
        val leaders = ArrayList<JArr>()
        for (f in owned.state.arr("heroes")) {
            val fields = f as JArr
            try {
                val template = HeroFortify.heroUidOf(fields, TEMPLATE)
                val inputs = evolutionInputs(unpackedTemplate(template))
                if (HeroStats.truthy(inputs["is_leader"])) leaders.add(fields)
            } catch (e: IllegalArgumentException) {
                continue
            }
        }
        return if (leaders.size == 1) leaders[0] else null
    }

    /** (leader fields, template now, template it should be), or null when nothing is to repair. */
    fun repairTarget(owned: Owned, evolutionInputs: EvolutionInputs, reached: Set<Long>): Triple<JArr, Long, Long>? {
        val fields = leader(owned, evolutionInputs) ?: return null
        val template = unpackedTemplate(HeroFortify.heroUidOf(fields, TEMPLATE))
        val digit = evolutionInputs.leaderDigit(template, template in reached)
        val current = (template / 100) % 10
        if (digit == null || digit == current) return null
        return Triple(fields, template, template - current * 100 + digit * 100)
    }

    /** Rewrite the leader's template digit and its four stats (the S18 carries them; no frame). */
    fun planRepair(owned: Owned, evolutionInputs: EvolutionInputs, reached: Set<Long>): Plan {
        val reject = HeroStats.Reject { m, c -> Acquisition.Rejected(m, c) }
        val (fields, before, after) = repairTarget(owned, evolutionInputs, reached)
            ?: throw Acquisition.Rejected("Leader digit already matches its row")
        val resolved = HeroStats.resolveProfile(fields, evolutionInputs(before), reject)
        val newInputs = evolutionInputs(after)
        val permille = HeroStats.statPermille(after, newInputs, resolved.obj("profile").int("awaken"), reject)
        val stats = HeroStats.baseStats(resolved.arr("full_grow").map { (it as JInt).value }, resolved.int("level"),
            resolved.long("grade"), permille, resolved.arr("dev").map { (it as JInt).value })
        for (f in fields) {
            val field = f.asObj
            val id = field.long("id")
            if (id == TEMPLATE.toLong()) field.obj("value")["bits"] = JInt(after)
            else if (id in HeroEvolution.STAT_IDS) field.obj("value")["bits"] = JInt(stats[HeroEvolution.STAT_IDS.indexOf(id)])
        }
        return Plan(jobj("template_before" to before, "template_after" to after, "stats_after" to stats, "stat_permille" to permille,
            "evidence_class" to "policy_repair_leader_super_digit"))
    }

    /**
     * A template as the reference's integer arithmetic sees it. A value beyond the unsigned 32-bit range is refused
     * the way the evolution inputs refuse it (their packed-id check), so such a hero is skipped like there.
     */
    private fun unpackedTemplate(template: BigInteger): Long {
        HeroDictionaryProgression.unpackHeroId(template)
        return template.toLong()
    }
}
