package io.github.okexodus.openknights.patcher

import io.github.okexodus.openknights.patcher.patch.NativePatchSet
import io.github.okexodus.openknights.patcher.signing.ApkSigning
import io.github.okexodus.openknights.patcher.signing.KeyStorage
import io.github.okexodus.openknights.patcher.util.Hashing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The folder flow on the made-up fixture game: outputs, reruns and every output-side failure. */
class PatcherTest {
    @TempDir
    lateinit var dir: Path

    private val keys get() = KeyStorage(dir.resolve("profile"))

    private fun patcher(native: NativePatchSet = FixtureGame.nativePatches, spare: Long = 0) = PatchOptions().let { options ->
        Patcher(options, keys, FixtureGame.supported, ApkPatcher(options, native), spareBytes = spare)
    }

    private fun original(): Path = FixtureGame.writeSplitSet(dir.resolve("original"))

    private fun listing(folder: Path): List<String> = if (!Files.exists(folder)) emptyList() else
        Files.list(folder).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun `a supported game gives the app, its report, checksum and log`() {
        val lines = ArrayList<String>()
        val result = patcher().run(original(), dir.resolve("patched"), PatchLog { lines += it })
        val name = "OpenKnights-${BuildInfo.version}.apk"
        assertEquals(listOf(name, "$name.log", "$name.report.json", "$name.sha256"), listing(dir.resolve("patched")), "no temporary files remain")
        assertEquals(Hashing.sha256(result.apk), result.sha256)
        assertEquals("${result.sha256}  $name\n", Files.readString(result.checksum))
        ApkSigning.verify(result.apk, result.certificateSha256)
        assertTrue(result.keyCreated)
        val report = Json.parseToJsonElement(Files.readString(result.report)).jsonObject
        assertEquals(result.sha256, report["output"]!!.jsonObject["sha256"]!!.jsonPrimitive.content)
        assertEquals("dev_server", report["mode"]!!.jsonPrimitive.content)
        assertFalse(Files.readString(result.report).contains(dir.toString().replace("\\", "\\\\")), "the report holds no local paths")
        assertTrue(Files.readString(result.log).contains("Signature checked"))
        assertTrue(lines.any { it.startsWith("Found: Fixture Game 3.0") })
    }

    @Test
    fun `a second run with the same input changes nothing and reuses the key`() {
        val first = patcher().run(original(), dir.resolve("patched"), PatchLog())
        val modified = Files.getLastModifiedTime(first.apk)
        val second = patcher().run(dir.resolve("original"), dir.resolve("patched"), PatchLog())
        assertTrue(second.unchanged)
        assertFalse(second.keyCreated)
        assertEquals(first.sha256, second.sha256)
        assertEquals(modified, Files.getLastModifiedTime(second.apk), "the app file was left as it was")
    }

    @Test
    fun `an unsupported game writes nothing at all`() {
        val folder = dir.resolve("original").also { Files.createDirectories(it) }
        // The same package as the supported game, another version.
        Files.write(folder.resolve("other.apk"), Fixtures.base(split = false))
        val failure = assertThrows<PatchFailure> { patcher().run(folder, dir.resolve("patched"), PatchLog()) }
        assertEquals(FailureCode.UNSUPPORTED_VERSION, failure.code)
        assertFalse(Files.exists(dir.resolve("patched")), "not even the output folder")
        assertFalse(keys.exists(), "and no key")
    }

    @Test
    fun `an output folder that cannot be written has its own message`() {
        val blocked = dir.resolve("patched").also { Files.writeString(it, "a file where the folder should be") }
        val failure = assertThrows<PatchFailure> { patcher().run(original(), blocked, PatchLog()) }
        assertEquals(FailureCode.OUTPUT_NOT_WRITABLE, failure.code)
    }

    @Test
    fun `too little disk space has its own message`() {
        val failure = assertThrows<PatchFailure> { patcher(spare = Long.MAX_VALUE / 4).run(original(), dir.resolve("patched"), PatchLog()) }
        assertEquals(FailureCode.NOT_ENOUGH_SPACE, failure.code)
        assertTrue(failure.message!!.contains("MB are free"))
        assertEquals(emptyList<String>(), listing(dir.resolve("patched")))
    }

    @Test
    fun `a failed patch leaves no half-written files`() {
        val wrong = NativePatchSet(FixtureGame.nativePatches.file, "0".repeat(64), null, FixtureGame.nativePatches.sites)
        val failure = assertThrows<PatchFailure> { patcher(native = wrong).run(original(), dir.resolve("patched"), PatchLog()) }
        assertEquals(FailureCode.PATCH_SITE_MISMATCH, failure.code)
        assertEquals(emptyList<String>(), listing(dir.resolve("patched")))
    }

    @Test
    fun `a key folder that cannot be used has its own message`() {
        Files.writeString(dir.resolve("profile"), "a file where the key folder should be")
        val failure = assertThrows<PatchFailure> { patcher().run(original(), dir.resolve("patched"), PatchLog()) }
        assertEquals(FailureCode.SIGNING_KEY_PROBLEM, failure.code)
    }
}
