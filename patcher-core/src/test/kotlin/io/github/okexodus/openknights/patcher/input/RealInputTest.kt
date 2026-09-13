package io.github.okexodus.openknights.patcher.input

import io.github.okexodus.openknights.patcher.LocalOriginals
import io.github.okexodus.openknights.patcher.zip.FileSource
import io.github.okexodus.openknights.patcher.zip.ZipArchive
import io.github.okexodus.openknights.patcher.zip.ZipWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Local-only: the real split set is recognised, and a repack into another container gives the same result. */
class RealInputTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the real split set is the supported build`() {
        val folder = dir.resolve("original").also { Files.createDirectories(it) }
        for (name in listOf(LocalOriginals.BASE, LocalOriginals.ABI_SPLIT, LocalOriginals.DENSITY_SPLIT)) {
            Files.copy(LocalOriginals.file(name), folder.resolve(name))
        }
        InputScanner().identify(folder).use { input ->
            assertEquals(InputKind.SPLIT_SET, input.kind)
            assertEquals(LocalOriginals.ABI_SPLIT, input.abiSplit?.name)
            assertEquals(listOf(LocalOriginals.DENSITY_SPLIT), input.densitySplits.map { it.name })
            assertEquals("194 game tables match", input.checks["tables"])
            assertTrue(input.notes.isEmpty(), input.notes.toString())
        }
    }

    @Test
    fun `a repack into an XAPK gives the same fingerprint as the loose files`() {
        val xapk = dir.resolve("Pocket Knights.xapk")
        Files.newOutputStream(xapk).use { out ->
            ZipWriter(out).use { writer ->
                for (name in listOf(LocalOriginals.BASE, LocalOriginals.ABI_SPLIT, LocalOriginals.DENSITY_SPLIT)) {
                    writer.addStored(name, Files.readAllBytes(LocalOriginals.file(name)))
                }
            }
        }
        val fromXapk = InputScanner().identify(xapk).use { it.fingerprint }
        val fromFolder = InputScanner().identify(LocalOriginals.require()).use { input ->
            // The originals folder also holds unpacked copies and XAPK leftovers; they are listed, not used.
            assertTrue(input.notes.isNotEmpty())
            input.fingerprint
        }
        assertEquals(fromFolder, fromXapk)
        ZipArchive.open(FileSource.open(xapk)).use { assertEquals(3, it.entries.size) }
    }
}
