package io.github.okexodus.openknights.patcher

import io.github.okexodus.openknights.patcher.res.BinaryXml
import io.github.okexodus.openknights.patcher.res.ResValue
import io.github.okexodus.openknights.patcher.res.ResourceTable
import io.github.okexodus.openknights.patcher.res.TypeChunk
import io.github.okexodus.openknights.patcher.util.Hashing
import io.github.okexodus.openknights.patcher.zip.FileSource
import io.github.okexodus.openknights.patcher.zip.ZipArchive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local-only parity. The decoded manifest differs from the earlier patched build only in the intended places. Every
 * resource keeps exactly its original value (the base's or its split's), and every resource the earlier build had
 * exists here; where the earlier build's value differs, it also differs from the original (its tool re-encoded it).
 */
class ResourceParityTest {
    private fun archive(path: Path) = ZipArchive.open(FileSource.open(path))

    @Test
    fun `the manifest differs only in the intended places`() {
        val ours = archive(RealBuild.build().signed).use { BinaryXml.read(it.read("AndroidManifest.xml")) }
        val theirs = archive(LocalReference.require()).use { BinaryXml.read(it.read("AndroidManifest.xml")) }
        Files.writeString(RealBuild.directory.resolve("manifest-ours.txt"), ours.toText())
        Files.writeString(RealBuild.directory.resolve("manifest-earlier.txt"), theirs.toText())
        val oursElements = ours.elements().toList()
        val theirElements = theirs.elements().toList()
        assertEquals(theirElements.map { it.name }, oursElements.map { it.name }, "same elements in the same order")
        val intended = setOf("package", "versionCode", "versionName", "label", "icon")
        val differences = ArrayList<String>()
        for ((a, b) in oursElements.zip(theirElements)) {
            for (name in (a.attributes.map { it.name } + b.attributes.map { it.name }).distinct()) {
                val va = a.attributes.firstOrNull { it.name == name }?.let { io.github.okexodus.openknights.patcher.res.describe(it.value) }
                val vb = b.attributes.firstOrNull { it.name == name }?.let { io.github.okexodus.openknights.patcher.res.describe(it.value) }
                if (va != vb && name !in intended) differences += "<${a.name}> $name: ours $va / earlier $vb"
            }
        }
        assertTrue(differences.isEmpty(), differences.joinToString("\n"))
        val application = oursElements.single { it.name == "application" }
        assertEquals("OpenKnights", application.androidAttribute("label")!!.value.string)
        assertEquals(10000, oursElements[0].androidAttribute("versionCode")!!.value.data)
        assertEquals("0.1.0.0", oursElements[0].androidAttribute("versionName")!!.value.string)
        assertEquals("io.github.okexodus.openknights", oursElements[0].attribute(null, "package")!!.value.string)
    }

    /** One resource value, resolved: files by content hash, maps as their (attribute, value) list. */
    private data class Resolved(val name: String, val value: String, val unorderedValue: String, val isXmlFile: Boolean)

    private fun resolve(table: ResourceTable, zip: ZipArchive): Map<Pair<Int, String>, Resolved> {
        val pkg = table.packages.single()
        val out = HashMap<Pair<Int, String>, Resolved>()
        fun valueText(type: Int, data: Int): Pair<String, Boolean> {
            if (type != ResValue.TYPE_STRING) return "t$type:$data" to false
            val text = table.globalPool[data]
            if (!text.startsWith("res/")) return "s:$text" to false
            val entry = zip[text] ?: return "missing:$text" to false
            return "file:${Hashing.sha256(zip.openStream(entry))}" to text.endsWith(".xml")
        }
        for (chunk in pkg.chunks.filterIsInstance<TypeChunk>()) {
            val config = chunk.config.drop(4).joinToString("") { "%02x".format(it) }.trimEnd('0')
            for (index in chunk.presentIndices()) {
                val entry = chunk.entry(index)!!
                val id = (pkg.id shl 24) or (chunk.typeId shl 16) or index
                val name = pkg.keyStrings[entry.keyIndex]
                val simple = entry.value
                out[id to config] = if (simple != null) {
                    val (text, xml) = valueText(simple.type, simple.data)
                    Resolved(name, text, text, xml)
                } else {
                    val b = ByteBuffer.wrap(entry.bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val headerSize = b.getShort(0).toInt()
                    val parent = b.getInt(8)
                    val items = (0 until b.getInt(12)).map { i ->
                        val at = headerSize + 12 * i
                        "0x%08x=%s".format(b.getInt(at), valueText(entry.bytes[at + 7].toInt() and 0xFF, b.getInt(at + 8)).first)
                    }
                    Resolved(name, "map parent 0x%08x ${items.joinToString(",")}".format(parent), "map parent 0x%08x ${items.sorted().joinToString(",")}".format(parent), false)
                }
            }
        }
        return out
    }

    @Test
    fun `every resource keeps its original value and the earlier build's resources all exist`() {
        val built = RealBuild.build().signed
        val ours = archive(built).use { resolve(ResourceTable.read(it.read("resources.arsc")), it) }
        val theirs = archive(LocalReference.require()).use { resolve(ResourceTable.read(it.read("resources.arsc")), it) }
        val original = HashMap<Pair<Int, String>, Resolved>()
        for (apk in listOf(LocalOriginals.BASE, LocalOriginals.DENSITY_SPLIT)) {
            archive(LocalOriginals.file(apk)).use { original.putAll(resolve(ResourceTable.read(it.read("resources.arsc")), it)) }
        }

        val changed = original.filter { (key, value) -> ours[key] != value }.keys
        assertTrue(changed.isEmpty(), "resources whose value changed: ${changed.take(10)}")
        val added = (ours.keys - original.keys).map { ours.getValue(it).name }.toSet()
        assertEquals(setOf("openknights_icon", "openknights_icon_foreground"), added)

        val missing = theirs.keys - ours.keys
        assertTrue(missing.isEmpty(), "resources of the earlier build missing here: ${missing.take(10)}")
        // Every value here equals the original, so wherever the earlier build differs, it is the earlier build that
        // changed the original. Count what kind of change it made (for the diff report).
        val kinds = sortedMapOf<String, Int>()
        for ((key, theirValue) in theirs) {
            val ourValue = ours.getValue(key)
            if (ourValue == theirValue) continue
            assertEquals(original[key], ourValue)
            val kind = when {
                ourValue.unorderedValue == theirValue.unorderedValue -> "map entries re-ordered"
                ourValue.isXmlFile && theirValue.isXmlFile -> "XML file recompiled"
                ourValue.value.startsWith("file:") && theirValue.value.startsWith("file:") -> "image file re-encoded"
                ourValue.value.startsWith("map") -> "map values changed (references)"
                else -> "other"
            }
            kinds.merge(kind, 1, Int::plus)
        }
        println("original resources kept: ${original.size}; added: $added")
        println("earlier build: ${theirs.size} values; differences from the original there: $kinds")
    }
}
