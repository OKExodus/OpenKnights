package io.github.okexodus.openknights.patcher.dex

import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.android.tools.smali.smali.smaliFlexLexer
import com.android.tools.smali.smali.smaliParser
import com.android.tools.smali.smali.smaliTreeWalker
import org.antlr.runtime.CommonTokenStream
import org.antlr.runtime.tree.CommonTree
import org.antlr.runtime.tree.CommonTreeNodeStream
import java.io.StringReader
import java.io.StringWriter

/** Disassembling and assembling classes with smali / baksmali, all in memory. */
object Smali {
    /** The dex format version in a DEX header (`dex\n035\0` → 35). */
    fun dexVersion(dex: ByteArray): Int {
        require(dex.size > 8 && dex[0] == 'd'.code.toByte() && dex[1] == 'e'.code.toByte() && dex[2] == 'x'.code.toByte()) { "not a DEX file" }
        return String(dex, 4, 3, Charsets.US_ASCII).toInt()
    }

    fun open(dex: ByteArray): DexBackedDexFile = DexBackedDexFile(Opcodes.forDexVersion(dexVersion(dex)), dex)

    /** The same baksmali options apktool uses, so the text matches what the earlier build edited. */
    private fun options(apiLevel: Int) = BaksmaliOptions().apply {
        this.apiLevel = apiLevel
        deodex = false
        implicitReferences = false
        parameterRegisters = true
        localsDirective = true
        sequentialLabels = true
        debugInfo = true
        codeOffsets = false
        accessorComments = false
        registerInfo = 0
    }

    fun disassemble(classDef: ClassDef, apiLevel: Int): String {
        val out = StringWriter()
        BaksmaliWriter(out).use { writer -> ClassDefinition(options(apiLevel), classDef).writeTo(writer) }
        // baksmali ends lines with the platform's separator; use \n everywhere so Windows and Linux agree.
        return out.toString().replace("\r\n", "\n")
    }

    /** Assembles smali sources into one DEX file. [sources] maps a name (for messages) to smali text. */
    fun assemble(sources: Map<String, String>, apiLevel: Int): ByteArray {
        val builder = DexBuilder(Opcodes.forApi(apiLevel))
        for ((name, text) in sources) {
            val lexer = smaliFlexLexer(StringReader(text), apiLevel)
            lexer.setSourceFile(java.io.File(name))
            val tokens = CommonTokenStream(lexer)
            val parser = smaliParser(tokens)
            parser.setVerboseErrors(false)
            parser.setAllowOdex(false)
            parser.setApiLevel(apiLevel)
            val result = parser.smali_file()
            if (parser.numberOfSyntaxErrors > 0 || lexer.numberOfSyntaxErrors > 0) error("$name: smali syntax errors")
            val nodes = CommonTreeNodeStream(result.tree as CommonTree)
            nodes.tokenStream = tokens
            val walker = smaliTreeWalker(nodes)
            walker.setApiLevel(apiLevel)
            walker.setVerboseErrors(false)
            walker.setDexBuilder(builder)
            walker.smali_file()
            if (walker.numberOfSyntaxErrors > 0) error("$name: smali errors")
        }
        val store = MemoryDataStore()
        builder.writeTo(store)
        return store.data
    }

    /** Rewrites a DEX file with some of its classes replaced (same type name) by [replacements]. */
    fun rewrite(dex: ByteArray, replacements: List<ClassDef>): ByteArray {
        val original = open(dex)
        val replaced = replacements.associateBy { it.type }
        val found = original.classes.map { it.type }.toSet()
        require(found.containsAll(replaced.keys)) { "classes not in this DEX file: ${replaced.keys - found}" }
        val pool = DexPool(original.opcodes)
        for (classDef in original.classes) pool.internClass(replaced[classDef.type] ?: classDef)
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.data
    }
}
