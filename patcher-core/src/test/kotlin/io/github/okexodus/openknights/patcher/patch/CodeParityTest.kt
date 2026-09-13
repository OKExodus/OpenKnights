package io.github.okexodus.openknights.patcher.patch

import io.github.okexodus.openknights.patcher.LocalOriginals
import io.github.okexodus.openknights.patcher.LocalReference
import io.github.okexodus.openknights.patcher.dex.Smali
import io.github.okexodus.openknights.patcher.zip.FileSource
import io.github.okexodus.openknights.patcher.zip.ZipArchive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Local-only parity: after the code patch, every class of the game disassembles exactly as in the earlier patched
 * build, and our added classes match its bridge classes except for their package and the two intended names.
 */
class CodeParityTest {
    private fun dexFiles(zip: ZipArchive) = zip.entries.map { it.name }.filter { Regex("classes\\d*\\.dex").matches(it) }
        .associateWith { zip.read(it) }

    private val defaultValue = Regex("""^(\.field .*\bstatic\b.*?) = (false|null|0x0[tsL]?|0\.0f?|'\\u0000')$""", RegexOption.MULTILINE)

    private fun withoutDefaults(smali: String): String = smali.replace(defaultValue, "$1")

    @Test
    fun `code matches the earlier build except the intended renames`() {
        val reference = LocalReference.require()
        val original = ZipArchive.open(FileSource.open(LocalOriginals.file(LocalOriginals.BASE))).use { dexFiles(it) }
        val result = CodePatch().apply(original)
        assertEquals(setOf("classes.dex", "classes3.dex"), result.dexFiles.keys, "only the DEX holding edited classes is rewritten")
        val ours = (original + result.dexFiles).mapValues { Smali.open(it.value) }
        val theirs = ZipArchive.open(FileSource.open(reference)).use { dexFiles(it) }.mapValues { Smali.open(it.value) }

        val originalTypes = original.values.flatMap { Smali.open(it).classes.map { c -> c.type } }.toSet()
        val ourClasses = ours.values.flatMap { it.classes }.associateBy { it.type }
        val theirClasses = theirs.values.flatMap { it.classes }.associateBy { it.type }
        val ourAdded = ourClasses.keys - originalTypes
        val theirAdded = theirClasses.keys - originalTypes
        assertEquals(result.addedClasses.toSet(), ourAdded)
        assertEquals(6, theirAdded.size)
        val theirPackage = theirAdded.map { LocalReference.packageOf(it) }.distinct().single()
        val ourPackage = ourAdded.map { LocalReference.packageOf(it) }.distinct().single()
        assertEquals("Lio/github/okexodus/openknights/client/", ourPackage)
        assertEquals(theirAdded.map { it.removePrefix(theirPackage) }.toSet(), ourAdded.map { it.removePrefix(ourPackage) }.toSet())
        assertEquals(originalTypes, theirClasses.keys - theirAdded, "the earlier build has the same game classes")

        val api = ours.getValue("classes.dex").opcodes.api
        val originalClasses = original.values.flatMap { Smali.open(it).classes }.associateBy { it.type }
        val edited = result.edits.map { it.className }.toSet()
        val unexpected = ArrayList<String>()
        val renamedStrings = ArrayList<Pair<String, String>>()
        val earlierReencoded = ArrayList<String>()
        val earlierOther = ArrayList<String>()
        val defaultsOmitted = ArrayList<String>()
        for (type in ourClasses.keys.sorted()) {
            val mine = Smali.disassemble(ourClasses.getValue(type), api)
            when (type) {
                in ourAdded -> {
                    val other = Smali.disassemble(theirClasses.getValue(theirPackage + type.removePrefix(ourPackage)), api).replace(theirPackage, ourPackage)
                    for ((a, b) in LocalReference.differingLines(mine, other)) {
                        if (a.trim().startsWith("const-string") && b.trim().startsWith("const-string") && "OpenKnights" in a) renamedStrings += a.trim() to b.trim()
                        else unexpected += "$type: ours `${a.trim()}` / earlier `${b.trim()}`"
                    }
                }
                in edited -> {
                    val other = Smali.disassemble(theirClasses.getValue(type), api).replace(theirPackage, ourPackage)
                    LocalReference.differingLines(mine, other).forEach { (a, b) -> unexpected += "$type: ours `${a.trim()}` / earlier `${b.trim()}`" }
                }
                else -> {
                    // Untouched game classes must disassemble as in the original. Writing a DEX file may leave out a
                    // static field's explicit default value (`= false`, `= null`, `= 0x0`): the field then starts at
                    // the same default, so only that is normalised.
                    val source = Smali.disassemble(originalClasses.getValue(type), api)
                    if (mine != source) {
                        defaultsOmitted += type
                        LocalReference.differingLines(withoutDefaults(mine), withoutDefaults(source))
                            .forEach { (a, b) -> unexpected += "$type: ours `${a.trim()}` / original `${b.trim()}`" }
                    }
                    val other = Smali.disassemble(theirClasses.getValue(type), api)
                    if (other != source) {
                        earlierReencoded += type
                        LocalReference.differingLines(withoutDefaults(other), withoutDefaults(source))
                            .forEach { (a, b) -> earlierOther += "$type: earlier `${a.trim()}` / original `${b.trim()}`" }
                    }
                }
            }
        }
        assertTrue(unexpected.isEmpty(), unexpected.take(20).joinToString("\n"))
        // The bridge name (registered and removed) and the dialog title are the only intended text changes.
        assertEquals(3, renamedStrings.size, renamedStrings.joinToString("\n"))
        println("untouched classes whose explicit default field values were left out: ${defaultsOmitted.size}")
        println("untouched classes the earlier build encoded differently: ${earlierReencoded.size}; other differences there: ${earlierOther.size}")
        earlierOther.take(10).forEach(::println)
    }

    @Test
    fun `patching the same code twice gives the same bytes`() {
        val original = ZipArchive.open(FileSource.open(LocalOriginals.file(LocalOriginals.BASE))).use { dexFiles(it) }
        val first = CodePatch().apply(original).dexFiles
        val second = CodePatch().apply(original).dexFiles
        assertEquals(first.keys, second.keys)
        for (name in first.keys) assertArrayEquals(first.getValue(name), second.getValue(name), name)
    }
}
