package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.server.store.StateStore

/**
 * `card_reset.py`: hero / gear / jewelry Sacrifice and Reforge (C3723 / C3725 / C3727): the card's Soul and returned
 * materials, paid in Diamonds by `Formula::ResetNeedDiamond`.
 */
object CardReset {
    const val C_RESET_HERO = 3723
    const val C_RESET_GEAR = 3725
    const val C_RESET_JEWEL = 3727
    const val SACRIFICE = 0L
    const val REFORGE = 1L

    /** `u32 uid, u8 mode` (0 Sacrifice / 1 Reforge) (`decode_reset`). */
    fun decodeReset(payload: ByteArray, opcode: Int): JObj = throw NotPorted("card_reset.decode_reset")

    /** One Sacrifice / Reforge (`plan_reset`); `exploreHeroes`: the heroes out on Hero Set Out. */
    fun planReset(opcode: Int, request: JObj, owned: Owned, inputs: DailyInputs, current: StateStore.Current, servedTime: Long,
                  exploreHeroes: Set<JValue> = emptySet(), miningHeroes: Set<JValue> = emptySet()): Plan =
        throw NotPorted("card_reset.plan_reset")
}
