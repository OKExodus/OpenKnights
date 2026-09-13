package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asObj

/**
 * The login part of `recharge.py` (the free top-up route belongs to the acquisition port): whether the character has
 * topped up, which decides the S1248 `00` of the login batch (the client's "first purchase doubled" offer ends then).
 */
object Recharge {
    const val S_CHARGED = 1248
    const val VIP_EXP = 28L

    /** True once the character has topped up (a recorded top-up, or VIP EXP from an earlier one). */
    fun hasCharged(state: JObj?, ledger: JValue?): Boolean {
        val doc = if (PyDocs.truthy(ledger)) ledger as JObj else JObj()
        if (PyDocs.compare(doc["transactions"] ?: JInt(0), JInt(0)) > 0) return true
        val props = (if (PyDocs.truthy(state)) state!! else JObj())["role_properties"]
        for (f in (props ?: JArr()) as JArr) {
            val field = f.asObj
            if (field["id"] == JInt(VIP_EXP)) {
                val value = field.obj("value")
                val bits = value["bits"] ?: JInt(0)
                return PyDocs.compare(if (PyDocs.truthy(bits)) bits else JInt(0), JInt(0)) > 0
            }
        }
        return false
    }
}
