package io.github.okexodus.openknights.patcher.res

import io.github.okexodus.openknights.patcher.LocalOriginals
import io.github.okexodus.openknights.patcher.zip.FileSource
import io.github.okexodus.openknights.patcher.zip.ZipArchive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Local-only: the codecs give back the exact bytes of the real manifest and resource tables. */
class RealResourcesTest {
    private fun read(apk: String, entry: String): ByteArray =
        ZipArchive.open(FileSource.open(LocalOriginals.file(apk))).use { it.read(entry) }

    @Test
    fun `base manifest round-trips byte for byte`() {
        val bytes = read(LocalOriginals.BASE, "AndroidManifest.xml")
        assertArrayEquals(bytes, BinaryXml.read(bytes).encode())
    }

    @Test
    fun `split manifests round-trip byte for byte`() {
        for (apk in listOf(LocalOriginals.ABI_SPLIT, LocalOriginals.DENSITY_SPLIT)) {
            val bytes = read(apk, "AndroidManifest.xml")
            assertArrayEquals(bytes, BinaryXml.read(bytes).encode(), apk)
        }
    }

    @Test
    fun `resource tables round-trip byte for byte`() {
        for (apk in listOf(LocalOriginals.BASE, LocalOriginals.DENSITY_SPLIT)) {
            val bytes = read(apk, "resources.arsc")
            val table = ResourceTable.read(bytes)
            assertArrayEquals(bytes, table.encode(), apk)
            assertTrue(table.globalPool.encodedMatches(), "$apk global pool rebuild")
            for (p in table.packages) {
                assertTrue(p.typeStrings.encodedMatches(), "$apk type strings rebuild")
                assertTrue(p.keyStrings.encodedMatches(), "$apk key strings rebuild")
            }
        }
    }
}
