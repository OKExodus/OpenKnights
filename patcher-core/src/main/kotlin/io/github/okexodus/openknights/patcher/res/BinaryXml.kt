package io.github.okexodus.openknights.patcher.res

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A typed attribute or text value (`Res_value`). String values keep their text in [string]. */
data class ResValue(val type: Int, val data: Int, val string: String? = null) {
    companion object {
        const val TYPE_NULL = 0x00
        const val TYPE_REFERENCE = 0x01
        const val TYPE_ATTRIBUTE = 0x02
        const val TYPE_STRING = 0x03
        const val TYPE_INT_DEC = 0x10
        const val TYPE_INT_HEX = 0x11
        const val TYPE_INT_BOOLEAN = 0x12
        const val TYPE_INT_COLOR_ARGB8 = 0x1c

        fun string(value: String) = ResValue(TYPE_STRING, 0, value)
        fun reference(id: Int) = ResValue(TYPE_REFERENCE, id)
        fun boolean(value: Boolean) = ResValue(TYPE_INT_BOOLEAN, if (value) -1 else 0)
        fun int(value: Int) = ResValue(TYPE_INT_DEC, value)
        fun color(argb: Int) = ResValue(TYPE_INT_COLOR_ARGB8, argb)
    }
}

/**
 * One attribute. [resourceId] is the framework attribute id (for example `android:label` = 0x01010001), or 0 for a
 * plain attribute such as `package`. [raw] is the attribute's original text, kept for string values.
 */
class XmlAttribute(
    var namespace: String?,
    var name: String,
    var resourceId: Int,
    var raw: String?,
    var value: ResValue,
) {
    override fun toString(): String = "${if (namespace != null) "$namespace:" else ""}$name=$value"
}

class XmlNamespace(val prefix: String?, val uri: String?, val line: Int, val comment: String?, val endLine: Int, val endComment: String?)

sealed class XmlNode

class XmlText(val text: String?, val value: ResValue, val line: Int, val comment: String?) : XmlNode()

class XmlElement(
    var namespace: String?,
    var name: String,
    val attributes: MutableList<XmlAttribute> = ArrayList(),
    val children: MutableList<XmlNode> = ArrayList(),
    var line: Int = 1,
    var comment: String? = null,
    var endLine: Int = 1,
    var endComment: String? = null,
    /** Namespace declarations that open before this element and close after it. */
    val namespaces: MutableList<XmlNamespace> = ArrayList(),
) : XmlNode() {
    internal var idAttribute: XmlAttribute? = null
    internal var classAttribute: XmlAttribute? = null
    internal var styleAttribute: XmlAttribute? = null

    val elements: List<XmlElement> get() = children.filterIsInstance<XmlElement>()

    fun attribute(namespace: String?, name: String): XmlAttribute? =
        attributes.firstOrNull { it.namespace == namespace && it.name == name }

    fun androidAttribute(name: String): XmlAttribute? = attribute(BinaryXml.ANDROID_NS, name)

    /**
     * Sets an attribute, adding it when missing. Attributes with a resource id stay sorted by that id (the framework
     * looks them up assuming that order); plain attributes go after them.
     */
    fun set(namespace: String?, name: String, resourceId: Int, value: ResValue, raw: String? = value.string): XmlAttribute {
        attribute(namespace, name)?.let { existing ->
            require(existing.resourceId == resourceId) { "$name has resource id ${existing.resourceId.hex()}, not ${resourceId.hex()}" }
            existing.value = value
            existing.raw = raw
            return existing
        }
        val added = XmlAttribute(namespace, name, resourceId, raw, value)
        val position = if (resourceId != 0) {
            attributes.indexOfFirst { it.resourceId == 0 || (it.resourceId.toLong() and 0xFFFFFFFFL) > (resourceId.toLong() and 0xFFFFFFFFL) }
                .let { if (it < 0) attributes.size else it }
        } else {
            attributes.size
        }
        attributes.add(position, added)
        return added
    }

    fun remove(namespace: String?, name: String): Boolean = attributes.removeIf { it.namespace == namespace && it.name == name }

    override fun toString(): String = "<$name ${attributes.joinToString(" ")}>"
}

/**
 * Android binary XML (`AndroidManifest.xml` and compiled XML resources). A document read and written back without
 * changes gives the same bytes; after changes, the string pool keeps its order and drops strings nothing uses.
 */
class BinaryXml private constructor(
    val root: XmlElement,
    private val originalStrings: List<String>,
    private val originalResourceIds: IntArray,
    private val utf8: Boolean,
) {
    fun encode(): ByteArray {
        val (pool, resourceIds) = buildPool()
        val body = ByteArrayOutputStream()
        val lookup = PoolLookup(pool, resourceIds)
        writeElement(body, root, lookup)
        val poolBytes = StringPool.create(pool, utf8).encode()
        val mapSize = 8 + 4 * resourceIds.size
        val total = 8 + poolBytes.size + (if (resourceIds.isEmpty()) 0 else mapSize) + body.size()
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(XML_TYPE.toShort()).putShort(8).putInt(total)
        out.put(poolBytes)
        if (resourceIds.isNotEmpty()) {
            out.putShort(RESOURCE_MAP_TYPE.toShort()).putShort(8).putInt(mapSize)
            resourceIds.forEach { out.putInt(it) }
        }
        out.put(body.toByteArray())
        return out.array()
    }

    /**
     * A readable rendering: one element per line, attributes as `ns:name(0xresid)=value`, typed values tagged so that
     * two renderings can be compared line by line.
     */
    fun toText(): String {
        val out = StringBuilder()
        fun prefixOf(uri: String?): String = when (uri) {
            null -> ""
            ANDROID_NS -> "android:"
            else -> "{$uri}"
        }
        fun render(e: XmlElement, depth: Int) {
            val indent = "  ".repeat(depth)
            out.append(indent).append('<').append(prefixOf(e.namespace)).append(e.name)
            for (a in e.attributes) {
                out.append("\n").append(indent).append("    ").append(prefixOf(a.namespace)).append(a.name)
                if (a.resourceId != 0) out.append('(').append(a.resourceId.hex()).append(')')
                out.append('=').append(describe(a.value))
                if (a.raw != null && a.value.type != ResValue.TYPE_STRING) out.append(" raw=\"").append(a.raw).append('"')
            }
            if (e.children.isEmpty()) {
                out.append("/>\n")
                return
            }
            out.append(">\n")
            for (child in e.children) when (child) {
                is XmlElement -> render(child, depth + 1)
                is XmlText -> out.append(indent).append("  ").append("text=").append(describe(child.value)).append('\n')
            }
            out.append(indent).append("</").append(prefixOf(e.namespace)).append(e.name).append(">\n")
        }
        render(root, 0)
        return out.toString()
    }

    /** Every element in document order. */
    fun elements(): Sequence<XmlElement> = sequence {
        suspend fun SequenceScope<XmlElement>.walk(e: XmlElement) {
            yield(e)
            e.children.filterIsInstance<XmlElement>().forEach { walk(it) }
        }
        walk(root)
    }

    private class PoolLookup(pool: List<String>, resourceIds: IntArray) {
        private val mapped = HashMap<Pair<String, Int>, Int>()
        private val plain = HashMap<String, Int>()
        private val anyMapped = HashMap<String, Int>()

        init {
            pool.forEachIndexed { i, s ->
                if (i >= resourceIds.size) {
                    plain.putIfAbsent(s, i)
                } else {
                    anyMapped.putIfAbsent(s, i)
                    mapped[s to resourceIds[i]] = i
                }
            }
        }

        fun attributeName(name: String, id: Int): Int =
            if (id == 0) string(name) else mapped[name to id] ?: error("attribute $name is missing from the pool")

        fun string(value: String?): Int {
            if (value == null) return -1
            return plain[value] ?: anyMapped[value] ?: error("string \"$value\" is missing from the pool")
        }
    }

    private fun buildPool(): Pair<List<String>, IntArray> {
        val neededMapped = LinkedHashSet<Pair<String, Int>>()
        val neededPlain = LinkedHashSet<String>()
        fun plain(s: String?) { if (s != null) neededPlain += s }
        fun visit(e: XmlElement) {
            for (ns in e.namespaces) { plain(ns.prefix); plain(ns.uri); plain(ns.comment); plain(ns.endComment) }
            plain(e.namespace); plain(e.name); plain(e.comment); plain(e.endComment)
            for (a in e.attributes) {
                if (a.resourceId != 0) neededMapped += a.name to a.resourceId else plain(a.name)
                plain(a.namespace); plain(a.raw)
                if (a.value.type == ResValue.TYPE_STRING) plain(a.value.string)
            }
            for (child in e.children) when (child) {
                is XmlElement -> visit(child)
                is XmlText -> { plain(child.text); plain(child.comment); if (child.value.type == ResValue.TYPE_STRING) plain(child.value.string) }
            }
        }
        visit(root)
        val mapped = ArrayList<Pair<String, Int>>()
        for (i in originalResourceIds.indices) {
            val pair = originalStrings[i] to originalResourceIds[i]
            if (pair in neededMapped && pair !in mapped) mapped += pair
        }
        for (pair in neededMapped) {
            if (pair in mapped) continue
            val id = pair.second.toLong() and 0xFFFFFFFFL
            val at = mapped.indexOfFirst { (it.second.toLong() and 0xFFFFFFFFL) > id }
            if (at < 0) mapped += pair else mapped.add(at, pair)
        }
        val mappedStrings = mapped.map { it.first }.toSet()
        val plainSection = ArrayList<String>()
        val placed = HashSet<String>()
        for (i in originalResourceIds.size until originalStrings.size) {
            val s = originalStrings[i]
            if (s in neededPlain && placed.add(s)) plainSection += s
        }
        for (s in neededPlain) {
            if (s !in placed && s !in mappedStrings) { plainSection += s; placed += s }
        }
        return (mapped.map { it.first } + plainSection) to mapped.map { it.second }.toIntArray()
    }

    private fun writeElement(out: ByteArrayOutputStream, e: XmlElement, pool: PoolLookup) {
        for (ns in e.namespaces) {
            node(out, START_NAMESPACE, ns.line, pool.string(ns.comment), 8) { it.putInt(pool.string(ns.prefix)).putInt(pool.string(ns.uri)) }
        }
        val attributeCount = e.attributes.size
        node(out, START_ELEMENT, e.line, pool.string(e.comment), 20 + 20 * attributeCount) { b ->
            b.putInt(pool.string(e.namespace)).putInt(pool.string(e.name))
            b.putShort(20).putShort(20).putShort(attributeCount.toShort())
            b.putShort(indexOf(e, e.idAttribute)).putShort(indexOf(e, e.classAttribute)).putShort(indexOf(e, e.styleAttribute))
            for (a in e.attributes) {
                b.putInt(pool.string(a.namespace)).putInt(pool.attributeName(a.name, a.resourceId)).putInt(pool.string(a.raw))
                putValue(b, a.value, pool)
            }
        }
        for (child in e.children) when (child) {
            is XmlElement -> writeElement(out, child, pool)
            is XmlText -> node(out, CDATA, child.line, pool.string(child.comment), 12) { b ->
                b.putInt(pool.string(child.text))
                putValue(b, child.value, pool)
            }
        }
        node(out, END_ELEMENT, e.endLine, pool.string(e.endComment), 8) { it.putInt(pool.string(e.namespace)).putInt(pool.string(e.name)) }
        for (ns in e.namespaces.asReversed()) {
            node(out, END_NAMESPACE, ns.endLine, pool.string(ns.endComment), 8) { it.putInt(pool.string(ns.prefix)).putInt(pool.string(ns.uri)) }
        }
    }

    private fun indexOf(e: XmlElement, a: XmlAttribute?): Short {
        if (a == null) return 0
        val i = e.attributes.indexOf(a)
        return if (i < 0) 0 else (i + 1).toShort()
    }

    private fun putValue(b: ByteBuffer, v: ResValue, pool: PoolLookup) {
        b.putShort(8).put(0).put(v.type.toByte())
        b.putInt(if (v.type == ResValue.TYPE_STRING) pool.string(v.string) else v.data)
    }

    private inline fun node(out: ByteArrayOutputStream, type: Int, line: Int, comment: Int, extension: Int, fill: (ByteBuffer) -> Unit) {
        val b = ByteBuffer.allocate(16 + extension).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(type.toShort()).putShort(16).putInt(16 + extension).putInt(line).putInt(comment)
        fill(b)
        check(b.position() == 16 + extension) { "node $type wrote ${b.position()} bytes, expected ${16 + extension}" }
        out.write(b.array())
    }

    companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val XML_TYPE = 0x0003
        private const val RESOURCE_MAP_TYPE = 0x0180
        private const val START_NAMESPACE = 0x0100
        private const val END_NAMESPACE = 0x0101
        private const val START_ELEMENT = 0x0102
        private const val END_ELEMENT = 0x0103
        private const val CDATA = 0x0104

        /** A new document with [root] as its only element (the android namespace is declared on it). */
        fun create(root: XmlElement, utf8: Boolean = true): BinaryXml {
            if (root.namespaces.isEmpty()) root.namespaces += XmlNamespace("android", ANDROID_NS, root.line, null, root.endLine, null)
            return BinaryXml(root, emptyList(), IntArray(0), utf8)
        }

        fun read(data: ByteArray): BinaryXml {
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            if (data.size < 8 || (b.getShort(0).toInt() and 0xFFFF) != XML_TYPE) throw ResourceFormatException("not an Android binary XML file")
            val total = b.getInt(4)
            if (total > data.size || total < 8) throw ResourceFormatException("binary XML size is damaged")
            var at = b.getShort(2).toInt() and 0xFFFF
            var pool: StringPool? = null
            var resourceIds = IntArray(0)
            val stack = ArrayDeque<XmlElement>()
            val pendingNamespaces = ArrayList<XmlNamespace>()
            val openNamespaces = ArrayDeque<Pair<XmlElement?, XmlNamespace>>()
            var root: XmlElement? = null
            var lastClosed: XmlElement? = null

            fun str(i: Int): String? = if (i == -1) null else pool?.let {
                if (i < 0 || i >= it.size) throw ResourceFormatException("string index $i is outside the pool") else it[i]
            } ?: throw ResourceFormatException("binary XML has no string pool")

            fun value(at: Int): ResValue {
                val type = data[at + 3].toInt() and 0xFF
                val raw = b.getInt(at + 4)
                return if (type == ResValue.TYPE_STRING) ResValue(type, 0, str(raw)) else ResValue(type, raw)
            }

            while (at < total) {
                val type = b.getShort(at).toInt() and 0xFFFF
                val headerSize = b.getShort(at + 2).toInt() and 0xFFFF
                val size = b.getInt(at + 4)
                if (size < 8 || at + size > total) throw ResourceFormatException("damaged binary XML chunk at $at")
                when (type) {
                    StringPool.TYPE -> pool = StringPool.read(data, at)
                    RESOURCE_MAP_TYPE -> resourceIds = IntArray((size - headerSize) / 4) { b.getInt(at + headerSize + 4 * it) }
                    START_NAMESPACE, END_NAMESPACE, START_ELEMENT, END_ELEMENT, CDATA -> {
                        val line = b.getInt(at + 8)
                        val comment = str(b.getInt(at + 12))
                        val ext = at + headerSize
                        when (type) {
                            START_NAMESPACE -> pendingNamespaces += XmlNamespace(str(b.getInt(ext)), str(b.getInt(ext + 4)), line, comment, 0, null)
                            END_NAMESPACE -> {
                                val (owner, ns) = openNamespaces.removeLastOrNull()
                                    ?: throw ResourceFormatException("unbalanced namespace end at $at")
                                if (owner == null || owner !== lastClosed) throw ResourceFormatException("namespace ends away from its element")
                                val i = owner.namespaces.indexOf(ns)
                                owner.namespaces[i] = XmlNamespace(ns.prefix, ns.uri, ns.line, ns.comment, line, comment)
                            }
                            START_ELEMENT -> {
                                val attributeStart = b.getShort(ext + 8).toInt() and 0xFFFF
                                val attributeSize = b.getShort(ext + 10).toInt() and 0xFFFF
                                val count = b.getShort(ext + 12).toInt() and 0xFFFF
                                val idIndex = b.getShort(ext + 14).toInt() and 0xFFFF
                                val classIndex = b.getShort(ext + 16).toInt() and 0xFFFF
                                val styleIndex = b.getShort(ext + 18).toInt() and 0xFFFF
                                val element = XmlElement(str(b.getInt(ext)), str(b.getInt(ext + 4)) ?: "", line = line, comment = comment)
                                for (i in 0 until count) {
                                    val a = ext + attributeStart + i * attributeSize
                                    val nameIndex = b.getInt(a + 4)
                                    val id = if (nameIndex in resourceIds.indices) resourceIds[nameIndex] else 0
                                    element.attributes += XmlAttribute(str(b.getInt(a)), str(nameIndex) ?: "", id, str(b.getInt(a + 8)), value(a + 12))
                                }
                                if (idIndex > 0) element.idAttribute = element.attributes.getOrNull(idIndex - 1)
                                if (classIndex > 0) element.classAttribute = element.attributes.getOrNull(classIndex - 1)
                                if (styleIndex > 0) element.styleAttribute = element.attributes.getOrNull(styleIndex - 1)
                                element.namespaces += pendingNamespaces
                                pendingNamespaces.forEach { openNamespaces.addLast(element to it) }
                                pendingNamespaces.clear()
                                val parent = stack.lastOrNull()
                                if (parent == null) {
                                    if (root != null) throw ResourceFormatException("binary XML has more than one root element")
                                    root = element
                                } else {
                                    parent.children += element
                                }
                                stack.addLast(element)
                            }
                            END_ELEMENT -> {
                                val element = stack.removeLastOrNull() ?: throw ResourceFormatException("unbalanced element end at $at")
                                element.endLine = line
                                element.endComment = comment
                                lastClosed = element
                            }
                            CDATA -> {
                                val parent = stack.lastOrNull() ?: throw ResourceFormatException("text outside the root element")
                                parent.children += XmlText(str(b.getInt(ext)), value(ext + 4), line, comment)
                            }
                        }
                    }
                    else -> throw ResourceFormatException("unsupported binary XML chunk ${type.hex()} at $at")
                }
                at += size
            }
            if (stack.isNotEmpty() || openNamespaces.isNotEmpty() || pendingNamespaces.isNotEmpty()) {
                throw ResourceFormatException("binary XML ends inside an element")
            }
            val strings = pool?.strings ?: emptyList()
            return BinaryXml(root ?: throw ResourceFormatException("binary XML has no element"), strings, resourceIds, pool?.utf8 ?: false)
        }
    }
}

internal fun Int.hex(): String = "0x%08x".format(this)

/** A typed value as text: `"string"`, `@0x7f070001`, `true`, `450`, `#ff000000`, or `type 0x..:0x..`. */
fun describe(v: ResValue): String = when (v.type) {
    ResValue.TYPE_STRING -> "\"${v.string}\""
    ResValue.TYPE_REFERENCE -> "@${v.data.hex()}"
    ResValue.TYPE_ATTRIBUTE -> "?${v.data.hex()}"
    ResValue.TYPE_INT_BOOLEAN -> if (v.data != 0) "true" else "false"
    ResValue.TYPE_INT_DEC -> v.data.toString()
    ResValue.TYPE_INT_HEX -> "0x%x".format(v.data)
    in 0x1c..0x1f -> "#%08x".format(v.data)
    else -> "type 0x%02x:0x%08x".format(v.type, v.data)
}
