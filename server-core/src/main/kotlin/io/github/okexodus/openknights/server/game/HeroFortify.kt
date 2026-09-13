package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.protocol.TypedValues
import java.math.BigInteger

/**
 * The owned-hero field access of `hero_fortify.py` that group 1 reaches (`hero_uid_of`). The Fortify planners and
 * their inputs belong to the hero group.
 */
object HeroFortify {
    const val UID = 0

    /** `hero_uid_of(fields, field_id)`: the one scalar field of this id (its `bits`); anything else is refused. */
    fun heroUidOf(fields: JArr, fieldId: Int = UID): BigInteger {
        val values = fields.map { it.asObj }.filter { (it["id"] as? JInt)?.value?.toInt() == fieldId }.map { it.obj("value") }
        if (values.size != 1 || (values[0]["tag"] as? JInt)?.value?.toInt() !in TypedValues.WIDTH_FORMAT) {
            throw PyValues.ValueError("Expected one scalar hero field $fieldId")
        }
        return (values[0].getValue("bits") as JInt).value
    }
}
