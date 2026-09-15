package io.github.okexodus.openknights.patcher.dex

import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.rewriter.Rewriters
import com.android.tools.smali.dexlib2.rewriter.TypeRewriter
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool

/**
 * Gives the embedded server its own Kotlin runtime. The game already carries an older Kotlin library: appending
 * another DEX with the same class names makes Android load the game's classes first, so newer server calls fail.
 * Rewriting only the server payload preserves the game's runtime and keeps the server's types consistent across
 * all its DEX files, including arrays, method signatures, annotations and instruction references.
 */
object ServerRuntime {
    private const val PREFIX = "Lio/github/okexodus/openknights/runtime/"

    fun isolate(dex: ByteArray): ByteArray {
        val original = Smali.open(dex)
        val rewriter = DexRewriter(object : RewriterModule() {
            override fun getTypeRewriter(rewriters: Rewriters) = object : TypeRewriter() {
                override fun rewriteUnwrappedType(value: String): String = when {
                    value.startsWith("Lkotlin/") || value.startsWith("Lkotlinx/") -> PREFIX + value.substring(1)
                    else -> value
                }
            }
        })
        val rewritten = rewriter.dexFileRewriter.rewrite(original)
        val pool = DexPool(original.opcodes)
        rewritten.classes.forEach(pool::internClass)
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.data
    }
}
