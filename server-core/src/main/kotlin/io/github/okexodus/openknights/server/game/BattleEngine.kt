package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue

/**
 * The server-side campaign battle engine (`server/battle_engine.py`): the seeded, byte-exact single-battle report
 * builder (SplitMix64 RNG, f32 arithmetic, the enemy stat chain, the fitted damage mechanics, the `_Unit` / `_Battle`
 * simulation and the S4 report through the [io.github.okexodus.openknights.protocol.BattleReport] codec).
 *
 * Lead-written entry-point stubs so `Campaign.planBattle` and the campaign route wire in without conflicts; the
 * battle-engine slice replaces the whole object with the full port. A NotPorted keeps the step waiting until it is
 * ported.
 */
object BattleEngine {
    const val ENGINE_VERSION = "pk-campaign-engine/1.0 (2026-09-12)"

    /** `enemy_actors(stage_id, inputs, rules)`: the enemy side of a campaign stage (encounter, background, type). */
    fun enemyActors(stageId: Long, inputs: DailyInputs, rules: JObj? = null): JObj =
        throw NotPorted("battle_engine.enemy_actors")

    /** `own_actors_from_stats(lineup, stats, inputs, rules)`: own-side actors in formation-slot order. */
    fun ownActorsFromStats(lineup: List<JValue>, stats: (JValue) -> JObj, inputs: DailyInputs, rules: JObj? = null): List<JObj> =
        throw NotPorted("battle_engine.own_actors_from_stats")

    /** `simulate(own_actors, enemy_actors, battle_type, background, seed, ...)`: one campaign battle → the S4 report. */
    fun simulate(ownActors: List<JValue>, enemyActors: JValue, battleType: Long, background: Long, seed: Long,
                 rules: JObj? = null, ownName: String = "", enemyName: String? = null, trace: Boolean = false,
                 reward: JObj? = null): JObj =
        throw NotPorted("battle_engine.simulate")
}
