package io.github.okexodus.openknights.protocol

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.asObj

/** Opcode 64 (Bag::HandleItemList): a u8 count of item records, each as in the player record. Lossless. */
object Inventory {
    fun decode(payload: ByteArray): JArr {
        val r = WireReader(payload)
        val items = PlayerState.readItems(r, r.u8())
        if (r.offset != payload.size) throw ProtocolException("Inventory payload has trailing bytes")
        return items
    }

    fun encode(items: JArr): ByteArray {
        if (items.size > 255) throw ProtocolException("Inventory list count exceeds one byte; use separate batches")
        val w = WireWriter().number('B', items.size.toLong())
        for (entry in items) {
            val item = entry.asObj
            val values = item["wire_values"] as? JArr
            val flag = item["timed_flag"]
            if (values == null || flag !is JInt || values.size != 3 || values.any { it !is JInt }) {
                throw ProtocolException("Inventory record requires three integer values and a flag")
            }
            try {
                w.values("III", values).number('B', flag)
            } catch (e: ProtocolException) {
                throw ProtocolException("Invalid inventory record")
            }
            val f = flag.value.toLong()
            if (f in 1..127) {
                val timing = item["timed_values"] as? JArr
                if (timing == null || timing.size != 2 || timing.any { it !is JInt }) {
                    throw ProtocolException("Positive signed timing flag requires two integer timing fields")
                }
                try { w.values("iI", timing) } catch (e: ProtocolException) { throw ProtocolException("Invalid inventory record") }
            } else if (item.containsKey("timed_values")) {
                throw ProtocolException("Nonpositive signed timing flag cannot carry timing fields")
            }
        }
        return w.bytes()
    }
}
