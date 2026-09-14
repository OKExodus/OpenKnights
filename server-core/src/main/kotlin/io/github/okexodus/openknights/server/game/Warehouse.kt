package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import java.math.BigInteger

/**
 * Warehouse sell (C83), buy-back (C85), buy-back delete (C87) and the buy-back list (C89) of `warehouse.py`.
 *
 * Live: C83 `u32 uid, u32 count` → S68 / S66, S3008 (the whole buy-back list), S74 (Reward: Gold = item field 110 ×
 * count), S128 Gold; C85 `u32 buy-back entry, u32 count` → S68 / S64, S128 Gold, S3008. S3008 is `u16 n, n × (u32
 * entry, u32 template, u32 count)`; a new sold entry takes max(entry) + 1; a partial buy-back keeps the entry with the
 * rest. C87 (never captured, built from the client code): remove units of one entry, reply the list.
 */
object Warehouse {
    const val SELL_OPCODE = 83
    const val BUYBACK_OPCODE = 85
    const val BUYBACK_LIST_OPCODE = 89
    const val BUYBACK_DELETE_OPCODE = 87
    const val S_SELL = 74
    const val S_BUYBACK_LIST = 3008
    const val ERROR_NOT_FOR_SALE = 2008          // "This item cannot be sold"
    const val BUYBACK_PERMILLE = 1200L           // candidate: property 102 = 1200; live 120 for a 100-Gold item

    /** `u32, u32` as the reference's tuple (the history records it as a list). */
    fun decodePair(payload: ByteArray, label: String): JArr {
        if (payload.size != 8) throw Acquisition.Rejected("$label is u32, u32")
        val r = WireReader(payload)
        return jarr(r.u32(), r.u32())
    }

    fun listPayload(entries: List<JValue>): ByteArray {
        if (entries.size > 0xFFFF) throw Acquisition.Rejected("Buy-back list exceeds its wire limit")
        val w = WireWriter().number('H', entries.size.toLong())
        for (e in entries) {
            val entry = e.asObj
            w.number('I', PyDocs.at(entry, "entry")).number('I', PyDocs.at(entry, "template")).number('I', PyDocs.at(entry, "count"))
        }
        return w.bytes()
    }

    /** The buy-back list of a character that never sold locally: empty. */
    fun initialDocument(): JObj = jobj("profile" to "buyback_list_v1", "entries" to JArr())

    fun planList(document: JValue?): Plan =
        Plan(JObj(), listOf(S_BUYBACK_LIST to listPayload((if (Py.truthy(document)) document!!.asObj else initialDocument()).arr("entries"))))

    private fun copyOf(document: JValue?): JObj = (if (Py.truthy(document)) document!! else initialDocument()).deepCopy() as JObj

    private fun entryOf(document: JObj, number: Long): JObj? =
        document.arr("entries").map { it.asObj }.firstOrNull { PyDocs.at(it, "entry") == JInt(number) }

    /** C83: the units into the buy-back list, Gold = item field 110 × count. */
    fun planSell(request: JArr, owned: Owned, inputs: AcquisitionInputs, document: JValue?): Plan {
        val uid = PyDocs.long(request[0])
        val count = PyDocs.long(request[1])
        if (count <= 0) throw Acquisition.Rejected("Sell count must be positive")
        val entry = owned.item(uid)
        val row = inputs.item(entry.template)
        val price = if (row != null && Py.truthy(row)) row.long("sell_110") else -1L
        if (price <= 0) throw Acquisition.Rejected("This item cannot be sold", ERROR_NOT_FOR_SALE)
        if (count > entry.count) throw Acquisition.Rejected("Not enough items", Acquisition.ERROR_NOT_ENOUGH)
        val doc = copyOf(document)
        val consume = owned.consume(uid, count)
        val entries = doc.arr("entries")
        val nextEntry = (entries.maxOfOrNull { PyDocs.long(PyDocs.at(it.asObj, "entry")) } ?: 0L) + 1
        entries.add(jobj("entry" to nextEntry, "template" to entry.template, "count" to count))
        val gold = BigInteger.valueOf(price).multiply(BigInteger.valueOf(count))
        val goldFrame = owned.roleAdd(Acquisition.GOLD, gold)
        val reward = BattleReport.emptyReward()
        reward["gold"] = JInt(gold)
        val packets = listOf(consume, S_BUYBACK_LIST to listPayload(entries), S_SELL to BattleReport.encodeReward(reward), goldFrame)
        return Plan(jobj("uid" to uid, "template" to entry.template, "count" to count, "gold" to gold, "entry" to nextEntry,
            "buyback_after" to doc, "evidence_class" to "capture_observed"), packets)
    }

    /** C85: the units back from the list for sell price × 1200 / 1000 × count Gold. */
    fun planBuyback(request: JArr, owned: Owned, inputs: AcquisitionInputs, document: JValue?): Plan {
        val number = PyDocs.long(request[0])
        val count = PyDocs.long(request[1])
        val doc = copyOf(document)
        val entry = entryOf(doc, number)
        if (entry == null || count <= 0 || count > PyDocs.long(PyDocs.at(entry, "count"))) {
            throw Acquisition.Rejected("Unknown buy-back entry or count", Acquisition.ERROR_NOT_ENOUGH)
        }
        val template = PyDocs.long(PyDocs.at(entry, "template"))
        val row = inputs.item(template)
        if (row == null || row.long("sell_110") <= 0) throw Acquisition.Rejected("Item has no sell price", Acquisition.ERROR_WRONG_TYPE)
        val cost = BigInteger.valueOf(row.long("sell_110")).multiply(BigInteger.valueOf(BUYBACK_PERMILLE))
            .divide(BigInteger.valueOf(1000L)).multiply(BigInteger.valueOf(count))      // sell_110 > 0: floor = truncation
        val grant = owned.grantItem(template, count)
        val goldFrame = owned.roleAdd(Acquisition.GOLD, cost.negate())
        entry["count"] = JInt(PyDocs.long(PyDocs.at(entry, "count")) - count)
        if (PyDocs.long(entry["count"]) == 0L) doc["entries"] = JArr(doc.arr("entries").filter { PyDocs.at(it.asObj, "entry") != JInt(number) }.toMutableList())
        val packets = listOf(grant, goldFrame, S_BUYBACK_LIST to listPayload(doc.arr("entries")))
        return Plan(jobj("entry" to number, "template" to entry["template"], "count" to count, "cost" to cost, "buyback_after" to doc,
            "evidence_class" to "capture_observed_price_candidate"), packets)
    }

    /**
     * C87: remove `count` units of one buy-back entry (nothing granted or refunded); the entry is dropped at 0 and kept
     * with the rest when partial; reply = the whole list S3008. An unknown entry, count 0 or a count above the entry is
     * refused with the C85 code, before any change.
     */
    fun planBuybackDelete(request: JArr, document: JValue?): Plan {
        val number = PyDocs.long(request[0])
        val count = PyDocs.long(request[1])
        val doc = copyOf(document)
        val entry = entryOf(doc, number)
        if (entry == null || count <= 0 || count > PyDocs.long(PyDocs.at(entry, "count"))) {
            throw Acquisition.Rejected("Unknown buy-back entry or count", Acquisition.ERROR_NOT_ENOUGH)
        }
        entry["count"] = JInt(PyDocs.long(PyDocs.at(entry, "count")) - count)
        if (PyDocs.long(entry["count"]) == 0L) doc["entries"] = JArr(doc.arr("entries").filter { PyDocs.at(it.asObj, "entry") != JInt(number) }.toMutableList())
        return Plan(jobj("operation" to "buyback_delete", "entry" to number, "template" to entry["template"], "count" to count,
            "remaining" to entry["count"], "buyback_after" to doc, "evidence_class" to "native_use_layout_candidate_semantics"),
            listOf(S_BUYBACK_LIST to listPayload(doc.arr("entries"))))
    }
}
