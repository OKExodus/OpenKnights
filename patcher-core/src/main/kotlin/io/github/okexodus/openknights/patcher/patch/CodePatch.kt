package io.github.okexodus.openknights.patcher.patch

import io.github.okexodus.openknights.patcher.FailureCode
import io.github.okexodus.openknights.patcher.PatchFailure
import io.github.okexodus.openknights.patcher.dex.Smali
import io.github.okexodus.openknights.patcher.util.Hashing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Edits the game's Java classes (method bodies and strings named in `patches/smali/game-edits.json`) and adds our own
 * classes as a new DEX file. Only the DEX file holding an edited class is rewritten; the others are kept as they are.
 */
class CodePatch(
    private val edits: JsonObject = PatchData.json("smali/game-edits.json"),
    private val readPatch: (String) -> String = { PatchData.text("smali/$it") },
) {
    data class ClassEdit(val className: String, val dex: String, val replacedMethods: List<String>, val inertMethods: List<String>,
                         val replacedStrings: Int, val originalSmaliSha256: String, val editedSmaliSha256: String)

    class Result(
        /** DEX files to write: rewritten ones under their own names, plus the new DEX with our classes. */
        val dexFiles: Map<String, ByteArray>,
        val edits: List<ClassEdit>,
        val addedDex: String,
        val addedClasses: List<String>,
    )

    fun apply(originalDex: Map<String, ByteArray>): Result {
        val opened = originalDex.mapValues { Smali.open(it.value) }
        val editedTexts = LinkedHashMap<String, LinkedHashMap<String, String>>()   // dex name -> class -> smali
        val report = ArrayList<ClassEdit>()
        for (element in edits["classes"]!!.jsonArray) {
            val spec = element.jsonObject
            val className = spec["class"]!!.jsonPrimitive.content
            val holders = opened.filter { (_, dex) -> dex.classes.any { it.type == className } }.keys
            if (holders.size != 1) mismatch("the class $className was found ${holders.size} times")
            val dexName = holders.single()
            val dex = opened.getValue(dexName)
            val classDef = dex.classes.first { it.type == className }
            val original = Smali.disassemble(classDef, dex.opcodes.api)
            var text = original
            var strings = 0
            spec["replace_strings"]?.jsonArray?.forEach { r ->
                val before = r.jsonObject["before"]!!.jsonPrimitive.content
                val after = r.jsonObject["after"]!!.jsonPrimitive.content
                val count = text.split(before).size - 1
                if (count != 1) mismatch("$className holds $before $count times, expected once")
                text = text.replace(before, after)
                strings++
            }
            val replaced = ArrayList<String>()
            spec["replace_methods"]?.jsonArray?.forEach { path ->
                val method = readPatch(path.jsonPrimitive.content).trimEnd('\n', '\r')
                val signature = method.lineSequence().first().removePrefix(".method ").trim()
                text = replaceMethod(text, className, signature, method)
                replaced += signature
            }
            val inert = ArrayList<String>()
            spec["inert_methods"]?.jsonArray?.forEach { s ->
                val signature = s.jsonPrimitive.content
                text = replaceMethod(text, className, signature, ".method $signature\n    .locals 0\n    return-void\n.end method")
                inert += signature
            }
            editedTexts.getOrPut(dexName) { LinkedHashMap() }[className] = text
            report += ClassEdit(className, dexName, replaced, inert, strings, Hashing.sha256(original.toByteArray()), Hashing.sha256(text.toByteArray()))
        }

        val output = LinkedHashMap<String, ByteArray>()
        for ((dexName, texts) in editedTexts) {
            val api = opened.getValue(dexName).opcodes.api
            val assembled = Smali.open(Smali.assemble(texts, api))
            output[dexName] = Smali.rewrite(originalDex.getValue(dexName), assembled.classes.toList())
        }

        val added = edits["add_classes"]!!.jsonArray.map { it.jsonPrimitive.content }
        val addedSources = added.associateWith { readPatch(it) }
        val mainApi = opened.getValue(originalDex.keys.minByOrNull { dexIndex(it) }!!).opcodes.api
        val addedDex = "classes${originalDex.keys.maxOf { dexIndex(it) } + 1}.dex"
        val addedBytes = Smali.assemble(addedSources, mainApi)
        val addedTypes = Smali.open(addedBytes).classes.map { it.type }.sorted()
        val existing = opened.values.flatMap { d -> d.classes.map { it.type } }.toSet()
        addedTypes.firstOrNull { it in existing }?.let { mismatch("the game already has a class named $it") }
        output[addedDex] = addedBytes
        return Result(output, report, addedDex, addedTypes)
    }

    private fun replaceMethod(text: String, className: String, signature: String, replacement: String): String {
        val pattern = Regex("^\\.method " + Regex.escape(signature) + "\\n.*?^\\.end method", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
        val matches = pattern.findAll(text).count()
        if (matches != 1) mismatch("$className has the method $signature $matches times, expected once")
        return pattern.replace(text) { replacement }
    }

    private fun mismatch(what: String): Nothing =
        throw PatchFailure(FailureCode.PATCH_SITE_MISMATCH, "The game's code is not as expected: $what. Nothing was patched.")

    companion object {
        /** classes.dex → 1, classes2.dex → 2, ... */
        fun dexIndex(name: String): Int = Regex("classes(\\d*)\\.dex").matchEntire(name)?.groupValues?.get(1)?.let { if (it.isEmpty()) 1 else it.toInt() }
            ?: error("$name is not a DEX file name")
    }
}
