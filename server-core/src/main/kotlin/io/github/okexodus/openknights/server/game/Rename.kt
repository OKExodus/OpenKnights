package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.TypedValues
import io.github.okexodus.openknights.protocol.Utf8
import io.github.okexodus.openknights.server.store.StateStore

/**
 * Rename Card — C1569 character rename (`rename.py`). Using item 10719 opens the client's rename panel directly (no
 * C73: the server consumes the card); the panel sends C1569 = name bytes + NUL without waiting. S1696 `u8`: 01 renamed,
 * 02 "Someone has taken this name already.", 03 "Character name consists blocked words". The name itself arrives as
 * S128 role property 2 (text 0x61). POLICY: the creation name rules apply; the world directory, the registry and the
 * save change together (the world first, undone when the save commit fails).
 */
object Rename {
    const val C_RENAME = 1569
    const val S_RENAME = 1696
    const val CARD_ITEM = 10719L
    const val OK = 1
    const val TAKEN = 2
    const val REFUSED = 3
    const val ROLE_NAME = 2L
    const val TEXT_TAG = 0x61
    const val ERROR_NO_CARD = 1731                   // "Not enough items" (the panel only opens from an owned card)

    /** Thrown where the reference's strict UTF-8 decode raises UnicodeDecodeError (a ValueError). */
    class UnicodeDecodeError(message: String) : IllegalArgumentException(message)

    fun decodeRequest(payload: ByteArray): String {
        if (payload.isEmpty() || payload.last() != 0.toByte() || payload.copyOfRange(0, payload.size - 1).contains(0.toByte())) {
            throw PyValues.ValueError("C1569 carries one NUL-terminated name")
        }
        return Utf8.decodeStrict(payload.copyOfRange(0, payload.size - 1)) ?: throw UnicodeDecodeError("'utf-8' codec can't decode the name")
    }

    fun result(code: Int): List<Frame> = listOf(S_RENAME to byteArrayOf(code.toByte()))

    fun namePayload(name: String): ByteArray =
        TypedValues.encodeFieldsBytes(JArr(mutableListOf(jobj("id" to ROLE_NAME, "value" to jobj("tag" to TEXT_TAG,
            "raw_hex" to name.toByteArray(Charsets.UTF_8).toHexString())))))

    /** The save half: the card, role property 2, the replies (card frame, S128 {2: name}, S1696 01). */
    fun planRename(name: String, owned: Owned): Plan {
        val frames = ArrayList(owned.consumeTemplate(CARD_ITEM, 1))
        val value = owned.state.arr("role_properties").map { it.asObj }.firstOrNull { it["id"] == JInt(ROLE_NAME) }?.obj("value")
            ?: throw NoSuchElementException("StopIteration")
        val before = value["text"]
        value["raw_hex"] = JStr(name.toByteArray(Charsets.UTF_8).toHexString())
        value["text"] = JStr(name)
        frames.add(Acquisition.S_ROLE to namePayload(name))
        frames.addAll(result(OK))
        return Plan(jobj("name_before" to before, "name_after" to name, "evidence_class" to "native_use_policy_rules_order"), frames)
    }

    fun ownsCard(current: StateStore.Current): Boolean {
        val records = current.state.arr("items") + current.inventoryItems + current.acquiredItems
        return records.any { r ->
            val wire = PyDocs.at(r.asObj, "wire_values") as JArr
            wire[1] == JInt(CARD_ITEM) && PyDocs.compare(wire[2], JInt(0)) > 0
        }
    }
}
