package io.github.okexodus.openknights.patcher

import io.github.okexodus.openknights.patcher.input.InputScanner
import io.github.okexodus.openknights.patcher.report.Report
import io.github.okexodus.openknights.patcher.signing.ApkSigning
import io.github.okexodus.openknights.patcher.util.Hashing
import io.github.okexodus.openknights.patcher.zip.FileSource
import io.github.okexodus.openknights.patcher.zip.ZipArchive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** Local-only: the real originals patch, sign and verify; the same input gives the same bytes. */
class RealPatchTest {
    @Test
    fun `the patched app is signed with all three schemes and aligned`() {
        val out = RealBuild.build()
        val check = ApkSigning.verify(out.signed, out.key.certificateSha256)
        assertTrue(check.v1 && check.v2 && check.v3)
        ZipArchive.open(FileSource.open(out.signed)).use { zip ->
            assertTrue(zip.contains("META-INF/OPENKNIG.SF"))
            assertTrue(zip.contains("classes3.dex"))
            assertEquals("a26869e38b88bed9bedaf62df6e5283536ac8aedd8999a4b5d49a9b399508350",
                Hashing.sha256(zip.openStream(zip["lib/arm64-v8a/libhelloworld.so"]!!)))
            assertTrue(zip.entries.none { it.name.startsWith("META-INF/BNDLTOOL") || it.name == "stamp-cert-sha256" })
        }
        println("warnings: ${check.warnings.size}")
        check.warnings.take(5).forEach(::println)
    }

    @Test
    fun `patching the same originals again gives identical files`() {
        val first = RealBuild.build()
        val unsigned = RealBuild.directory.resolve("again-unsigned.apk")
        val signed = RealBuild.directory.resolve("again-signed.apk")
        Files.deleteIfExists(unsigned)
        Files.deleteIfExists(signed)
        InputScanner().identify(LocalOriginals.require()).use { ApkPatcher().build(it, unsigned, Report()) }
        ApkSigning.sign(unsigned, signed, first.key, "OpenKnights Patcher test")
        assertEquals(Hashing.sha256(first.unsigned), Hashing.sha256(unsigned), "unsigned APK")
        assertEquals(Hashing.sha256(first.signed), Hashing.sha256(signed), "signed APK")
        Files.delete(unsigned)
        Files.delete(signed)
    }
}
