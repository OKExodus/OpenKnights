package io.github.okexodus.openknights.patcher

import io.github.okexodus.openknights.patcher.dex.ServerRuntime
import io.github.okexodus.openknights.patcher.dex.Smali
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerRuntimeTest {
    @Test
    fun `server references use isolated runtime across dex files while the game stays untouched`() {
        val runtime = Smali.assemble(mapOf("Runtime" to """
            .class public Lkotlin/collections/Fixture;
            .super Ljava/lang/Object;
            .method public static value()I
                .locals 1
                const/4 v0, 0x1
                return v0
            .end method
        """.trimIndent()), 26)
        val caller = Smali.assemble(mapOf("Caller" to """
            .class public Lexample/Server;
            .super Ljava/lang/Object;
            .field public entries:[Lkotlin/collections/Fixture;
            .method public static call(Lkotlin/collections/Fixture;)I
                .locals 1
                invoke-static {}, Lkotlin/collections/Fixture;->value()I
                move-result v0
                return v0
            .end method
        """.trimIndent()), 26)
        val isolated = Smali.open(ServerRuntime.isolate(runtime)).classes.single()
        assertEquals("Lio/github/okexodus/openknights/runtime/kotlin/collections/Fixture;", isolated.type)
        val server = Smali.open(ServerRuntime.isolate(caller))
        val text = Smali.disassemble(server.classes.single(), server.opcodes.api)
        assertFalse(text.contains("Lkotlin/"))
        assertTrue(text.contains("[Lio/github/okexodus/openknights/runtime/kotlin/collections/Fixture;"))
        assertTrue(text.contains("Lio/github/okexodus/openknights/runtime/kotlin/collections/Fixture;->value()I"))
        assertEquals("Lkotlin/collections/Fixture;", Smali.open(runtime).classes.single().type)
    }
}
