package io.github.okexodus.openknights.protocol

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asInt
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString

/**
 * The client's dynamic typed values (AnyType / CDynamicStruct), lossless. A value is `{"tag", ...}` with `bits` for
 * the numeric tags, `raw_hex` + `text` (UTF-8 or null) for strings (0x61), `items` for vectors (0x62) and `entries`
 * for maps (0x63), then `offset` / `end_offset` (payload positions). Field lists are `[{"id", "value"}]` with a u8
 * count. Unknown tags are refused rather than guessed.
 */
object TypedValues {
    val WIDTH_FORMAT: Map<Int, Char> = mapOf(1 to 'B', 2 to 'B', 3 to 'H', 4 to 'H', 5 to 'I', 6 to 'I',
        7 to 'Q', 8 to 'Q', 0x21 to 'I', 0x22 to 'Q')
    const val MAX_DEPTH = 48

    fun readValue(reader: WireReader, depth: Int = 0): JObj {
        if (depth > MAX_DEPTH) throw ProtocolException("Typed value nesting limit exceeded")
        val start = reader.offset
        val tag = reader.u8()
        val value = jobj("tag" to tag)
        val width = WIDTH_FORMAT[tag]
        when {
            width != null -> value["bits"] = reader.number(width)
            tag == 0x61 -> {
                val raw = reader.cstringBytes()
                value["raw_hex"] = JStr(raw.toHexString())
                value["text"] = Utf8.decodeStrict(raw)?.let { JStr(it) } ?: JNull
            }
            tag == 0x62 -> {
                val items = JArr()
                repeat(reader.u8()) { items.add(readValue(reader, depth + 1)) }
                value["items"] = items
            }
            tag == 0x63 -> value["entries"] = readFields(reader, depth + 1)
            else -> throw ProtocolException("Unsupported AnyType tag 0x%02x at payload offset %d".format(tag, start))
        }
        value["offset"] = JInt(start)
        value["end_offset"] = JInt(reader.offset)
        return value
    }

    fun readFields(reader: WireReader, depth: Int = 0): JArr {
        if (depth > MAX_DEPTH) throw ProtocolException("Typed value nesting limit exceeded")
        val count = reader.u8()
        val fields = JArr()
        repeat(count) {
            val id = reader.u8()
            fields.add(jobj("id" to id, "value" to readValue(reader, depth)))
        }
        return fields
    }

    fun encodeValue(value: JObj, out: WireWriter = WireWriter(), depth: Int = 0): WireWriter {
        if (depth > MAX_DEPTH) throw ProtocolException("Typed value nesting limit exceeded")
        val tag = value.int("tag").toInt()
        out.number('B', tag.toLong())
        val width = WIDTH_FORMAT[tag]
        when {
            width != null -> out.number(width, value.getValue("bits"))
            tag == 0x61 -> out.cstring(value.str("raw_hex").hexBytes(), "AnyType string")
            tag == 0x62 -> {
                val items = value.arr("items")
                out.number('B', items.size.toLong())
                items.forEach { encodeValue(it.asObj, out, depth + 1) }
            }
            tag == 0x63 -> encodeFields(value.arr("entries"), out, depth + 1)
            else -> throw ProtocolException("Unsupported AnyType tag 0x%02x".format(tag))
        }
        return out
    }

    fun encodeFields(fields: JArr, out: WireWriter = WireWriter(), depth: Int = 0): WireWriter {
        if (depth > MAX_DEPTH) throw ProtocolException("Typed value nesting limit exceeded")
        out.number('B', fields.size.toLong())
        for (field in fields) {
            val f = field.asObj
            out.number('B', f.getValue("id"))
            encodeValue(f.obj("value"), out, depth)
        }
        return out
    }

    fun encodeFieldsBytes(fields: JArr): ByteArray = encodeFields(fields).bytes()

    /** The value of field `id` of a field list (exactly one occurrence), like the reference's `_role` helpers. */
    fun field(fields: JArr, id: Int): JObj {
        val matches = fields.filter { it.asObj.int("id").toInt() == id }
        if (matches.size != 1) throw ProtocolException("Field $id must occur exactly once")
        return matches[0].asObj.obj("value")
    }

    fun bitsOf(value: JValue): java.math.BigInteger = value.asObj.getValue("bits").asInt
}
