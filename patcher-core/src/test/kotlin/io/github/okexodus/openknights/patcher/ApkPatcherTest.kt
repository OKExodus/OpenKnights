package io.github.okexodus.openknights.patcher

import io.github.okexodus.openknights.patcher.input.InputScanner
import io.github.okexodus.openknights.patcher.patch.CodePatch
import io.github.okexodus.openknights.patcher.report.Report
import io.github.okexodus.openknights.patcher.res.BinaryXml
import io.github.okexodus.openknights.patcher.signing.ApkSigning
import io.github.okexodus.openknights.patcher.signing.KeyStorage
import io.github.okexodus.openknights.patcher.util.Hashing
import io.github.okexodus.openknights.patcher.zip.FileSource
import io.github.okexodus.openknights.patcher.zip.ZipArchive
import io.github.okexodus.openknights.patcher.zip.ZipEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The whole patch on the made-up fixture game: check, patch, sign, verify. Runs everywhere, CI included. */
class ApkPatcherTest {
    @TempDir
    lateinit var dir: Path

    private fun build(name: String): Pair<Path, Report> {
        val report = Report()
        val unsigned = dir.resolve("$name-unsigned.apk")
        InputScanner(FixtureGame.supported, dir).identify(FixtureGame.writeSplitSet(dir.resolve("original"))).use { input ->
            ApkPatcher(nativePatches = FixtureGame.nativePatches, codePatch = CodePatch()).build(input, unsigned, report)
        }
        return unsigned to report
    }

    @Test
    fun `the fixture game becomes a signed OpenKnights app`() {
        val (unsigned, report) = build("first")
        val key = KeyStorage(dir.resolve("key")).loadOrCreate().first
        val signed = dir.resolve("signed.apk")
        ApkSigning.sign(unsigned, signed, key, "OpenKnights Patcher test")
        ApkSigning.verify(signed, key.certificateSha256)
        ZipArchive.open(FileSource.open(signed)).use { zip ->
            val names = zip.entries.map { it.name }
            assertEquals("AndroidManifest.xml", names.first())
            for (gone in listOf("META-INF/VENDOR.SF", "META-INF/VENDOR.RSA", "stamp-cert-sha256")) assertFalse(gone in names, gone)
            for (kept in listOf("META-INF/services/example.Service", "classes3.dex", "res/drawable-mdpi-v4/split_only.png",
                "res/drawable-mdpi-v4/icon.png", "res/drawable-anydpi-v26/openknights_icon.xml", "lib/arm64-v8a/libhelloworld.so")) {
                assertTrue(kept in names, kept)
            }
            val lib = zip["lib/arm64-v8a/libhelloworld.so"]!!
            assertEquals(ZipEntry.STORED, lib.method)
            assertEquals(0L, lib.dataOffset % 16384, "the library is page aligned")
            assertEquals("127.0.0.1:17777", String(zip.read(lib), 0x101, 15))
            val manifest = BinaryXml.read(zip.read("AndroidManifest.xml")).root
            assertEquals(PatchOptions.PACKAGE, manifest.attribute(null, "package")!!.value.string)
            assertEquals(BuildInfo.version.versionCode, manifest.androidAttribute("versionCode")!!.value.data)
        }
        val steps = report.toJson()["steps"].toString()
        for (step in listOf("manifest", "resources", "code", "native_library", "assemble")) assertTrue(steps.contains("\"step\":\"$step\""), step)
    }

    @Test
    fun `the same input gives the same APK`() {
        val (first, _) = build("first")
        val (second, _) = build("second")
        assertEquals(Hashing.sha256(first), Hashing.sha256(second))
    }

    @Test
    fun `the originals are only read`() {
        val original = FixtureGame.writeSplitSet(dir.resolve("original"))
        fun snapshot() = Files.list(original).use { s -> s.sorted().toList() }.associate { it.fileName.toString() to Hashing.sha256(it) }
        val before = snapshot()
        build("first")
        assertEquals(before, snapshot())
    }
}
