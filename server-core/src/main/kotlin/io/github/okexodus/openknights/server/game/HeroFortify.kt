package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.protocol.TypedValues
import io.github.okexodus.openknights.protocol.WireWriter
import java.math.BigInteger

/**
 * Ordinary hero Fortify and gear Fortify (`hero_fortify.py`): request / reply codecs, the owned-hero field access and
 * the planners.
 */
object HeroFortify {
    const val UID = 0

    private fun uint(value: Long, width: Int, label: String): Long {
        if (value < 0 || (width < 64 && value >= (1L shl width))) throw PyValues.ValueError("$label must fit uint$width")
        return value
    }

    /** Opcode 46: the u32 owned UID, then the absolute typed fields to apply (`hero_property_update_payload`). */
    fun heroPropertyUpdatePayload(uid: Long, fields: JArr): ByteArray {
        if (fields.isEmpty()) throw PyValues.ValueError("A hero property update carries at least one field")
        return WireWriter().number('I', uint(uid, 32, "Owned hero UID")).also { TypedValues.encodeFields(fields, it) }.bytes()
    }

    /** Opcodes 34 / 40 / 98 / 102: a u8 count, then the u32 owned UIDs (`counted_uid_payload`). */
    fun countedUidPayload(uids: List<Long>): ByteArray {
        if (uids.size !in 1..255) throw PyValues.ValueError("Counted UID list must hold 1..255 references")
        val w = WireWriter().number('B', uids.size.toLong())
        for (uid in uids) w.number('I', uint(uid, 32, "Owned UID"))
        return w.bytes()
    }

    /** `hero_uid_of(fields, field_id)`: the one scalar field of this id (its `bits`); anything else is refused. */
    fun heroUidOf(fields: JArr, fieldId: Int = UID): BigInteger {
        val values = fields.map { it.asObj }.filter { (it["id"] as? JInt)?.value?.toInt() == fieldId }.map { it.obj("value") }
        if (values.size != 1 || (values[0]["tag"] as? JInt)?.value?.toInt() !in TypedValues.WIDTH_FORMAT) {
            throw PyValues.ValueError("Expected one scalar hero field $fieldId")
        }
        return (values[0].getValue("bits") as JInt).value
    }
}
