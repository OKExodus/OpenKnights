package io.github.okexodus.openknights.patcher.input

import io.github.okexodus.openknights.gamedata.SupportedInput
import io.github.okexodus.openknights.patcher.FailureCode
import io.github.okexodus.openknights.patcher.Fixtures
import io.github.okexodus.openknights.patcher.PatchFailure
import io.github.okexodus.openknights.patcher.util.Hashing
import io.github.okexodus.openknights.patcher.zip.ByteArraySource
import io.github.okexodus.openknights.patcher.zip.ZipArchive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class InputScannerTest {
    @TempDir
    lateinit var dir: Path

    private val scanner get() = InputScanner(Fixtures.supported, tempRoot = dir.resolve("tmp").also { Files.createDirectories(it) })

    private fun failure(path: Path): PatchFailure = assertThrows<PatchFailure> { scanner.identify(path).close() }

    private fun folder(name: String = "original"): Path = dir.resolve(name).also { Files.createDirectories(it) }

    @Test
    fun `a folder of split APKs is identified`() {
        val original = Fixtures.writeSplitSet(folder())
        scanner.identify(original).use { input ->
            assertEquals(InputKind.SPLIT_SET, input.kind)
            assertEquals("config.arm64_v8a.apk", input.abiSplit?.name)
            assertEquals(listOf("config.mdpi.apk"), input.densitySplits.map { it.name })
            assertEquals(Fixtures.supported.nativeLibrarySha256, input.checks[Fixtures.supported.nativeLibraryPath])
            assertEquals(setOf("classes.dex", "classes2.dex", "lib/arm64-v8a/libhelloworld.so", "tables"), input.checks.keys)
            assertEquals(3, input.files.size)
        }
    }

    @Test
    fun `one universal APK is identified`() {
        val apk = folder().resolve("game.apk")
        Files.write(apk, Fixtures.base(split = false))
        scanner.identify(apk).use { input ->
            assertEquals(InputKind.UNIVERSAL, input.kind)
            assertNull(input.abiSplit)
            assertTrue(input.densitySplits.isEmpty())
        }
    }

    @Test
    fun `an XAPK bundle is identified and matches the loose split set`() {
        val xapk = folder().resolve("Game.xapk")
        Files.write(xapk, Fixtures.xapk())
        val loose = Fixtures.writeSplitSet(folder("loose"))
        val fromBundle = scanner.identify(xapk).use { it.fingerprint }
        val fromFolder = scanner.identify(loose).use { it.fingerprint }
        assertEquals(fromFolder, fromBundle, "the same content in another container gives the same fingerprint")
    }

    @Test
    fun `an APKM bundle with compressed APKs is identified and its temporary copies are removed`() {
        val apkm = folder().resolve("Game.apkm")
        Files.write(apkm, Fixtures.apkmDeflated())
        val tmp = dir.resolve("tmp")
        val input = scanner.identify(apkm)
        assertEquals(InputKind.SPLIT_SET, input.kind)
        assertTrue(Files.list(tmp).use { it.count() } > 0, "compressed APKs are unpacked while checking")
        input.close()
        assertEquals(0, Files.list(tmp).use { it.count() }, "temporary copies are deleted on close")
    }

    @Test
    fun `an empty folder has its own message`() {
        val f = failure(folder())
        assertEquals(FailureCode.EMPTY_FOLDER, f.code)
        assertTrue(f.message!!.contains("\"original\" is empty"))
    }

    @Test
    fun `a folder without app files has its own message`() {
        val original = folder()
        Files.writeString(original.resolve("readme.txt"), "hello")
        Files.write(original.resolve("picture.png"), byteArrayOf(1, 2, 3))
        val f = failure(original)
        assertEquals(FailureCode.NO_GAME_FILE, f.code)
        assertEquals(2, f.details.size)
    }

    @Test
    fun `a zip of screenshots is the wrong file type`() {
        val zip = folder().resolve("screenshots.zip")
        Files.write(zip, Fixtures.zip(mapOf("shot1.png" to byteArrayOf(1), "shot2.png" to byteArrayOf(2))))
        val f = failure(zip)
        assertEquals(FailureCode.WRONG_FILE_TYPE, f.code)
        assertTrue(f.message!!.contains("holds no Android app"))
    }

    @Test
    fun `a non-zip file is the wrong file type`() {
        val file = folder().resolve("game.apk")
        Files.writeString(file, "this is not a zip file at all")
        assertEquals(FailureCode.WRONG_FILE_TYPE, failure(file).code)
    }

    @Test
    fun `a cut-off download is damaged`() {
        val bytes = Fixtures.base(split = false)
        val file = folder().resolve("game.apk")
        Files.write(file, bytes.copyOf(bytes.size - 40))
        assertEquals(FailureCode.DAMAGED_FILE, failure(file).code)
    }

    @Test
    fun `a flipped byte inside the APK is damaged, not modified`() {
        val bytes = Fixtures.base(split = false)
        val entry = ZipArchive.open(ByteArraySource(bytes)).use { it["classes.dex"]!! }
        bytes[entry.dataOffset.toInt()] = (bytes[entry.dataOffset.toInt()].toInt() xor 1).toByte()
        val file = folder().resolve("game.apk")
        Files.write(file, bytes)
        val f = failure(file)
        assertEquals(FailureCode.DAMAGED_FILE, f.code)
        assertTrue(f.message!!.contains("checksums"))
    }

    @Test
    fun `different program code is a modified game`() {
        val file = folder().resolve("game.apk")
        Files.write(file, Fixtures.base(split = false, dexOverride = mapOf("classes.dex" to "patched dex".toByteArray())))
        val f = failure(file)
        assertEquals(FailureCode.MODIFIED_GAME, f.code)
        assertTrue(f.message!!.contains("classes.dex"))
    }

    @Test
    fun `a changed game table is a modified game`() {
        val file = folder().resolve("game.apk")
        Files.write(file, Fixtures.base(split = false, tableOverrides = mapOf("beta.csv" to "id\n3\n".toByteArray())))
        val f = failure(file)
        assertEquals(FailureCode.MODIFIED_GAME, f.code)
        assertTrue(f.message!!.contains("1 game tables changed, e.g. beta.csv"))
    }

    @Test
    fun `an extra game table is a modified game`() {
        val file = folder().resolve("game.apk")
        Files.write(file, Fixtures.base(split = false, tableOverrides = mapOf("gamma.csv" to "x".toByteArray())))
        assertEquals(FailureCode.MODIFIED_GAME, failure(file).code)
    }

    @Test
    fun `another version names the version found and the supported one`() {
        val original = Fixtures.writeSplitSet(folder(), base = Fixtures.base(versionCode = 6), abi = Fixtures.abiSplit(6),
            density = Fixtures.densitySplit(6))
        val f = failure(original)
        assertEquals(FailureCode.UNSUPPORTED_VERSION, f.code)
        assertTrue(f.message!!.contains("version code 6"))
        assertTrue(f.message!!.contains("Fixture Game 1.7"))
    }

    @Test
    fun `another app is the wrong game`() {
        val file = folder().resolve("other.apk")
        Files.write(file, Fixtures.base(split = false, packageName = "org.example.other"))
        val f = failure(file)
        assertEquals(FailureCode.WRONG_GAME, f.code)
        assertTrue(f.message!!.contains("org.example.other"))
    }

    @Test
    fun `a missing arm64 split is incomplete`() {
        val f = failure(Fixtures.writeSplitSet(folder(), abi = null))
        assertEquals(FailureCode.INCOMPLETE_SPLITS, f.code)
        assertTrue(f.message!!.contains("arm64"))
    }

    @Test
    fun `a missing density split is incomplete`() {
        val f = failure(Fixtures.writeSplitSet(folder(), density = null))
        assertEquals(FailureCode.INCOMPLETE_SPLITS, f.code)
        assertTrue(f.message!!.contains("screen-density"))
    }

    @Test
    fun `splits without their base are incomplete`() {
        val original = folder()
        Files.write(original.resolve("config.arm64_v8a.apk"), Fixtures.abiSplit())
        Files.write(original.resolve("config.mdpi.apk"), Fixtures.densitySplit())
        assertEquals(FailureCode.INCOMPLETE_SPLITS, failure(original).code)
    }

    @Test
    fun `a split of another version is refused`() {
        val f = failure(Fixtures.writeSplitSet(folder(), abi = Fixtures.abiSplit(versionCode = 6)))
        assertEquals(FailureCode.MISMATCHED_SPLITS, f.code)
    }

    @Test
    fun `a wrong library in the split is a modified game`() {
        val f = failure(Fixtures.writeSplitSet(folder(), abi = Fixtures.abiSplit(lib = "other library".toByteArray())))
        assertEquals(FailureCode.MODIFIED_GAME, f.code)
        assertTrue(f.message!!.contains("libhelloworld.so"))
    }

    @Test
    fun `an encrypted bundle has its own message`() {
        val bytes = Fixtures.xapk()
        val zip = ZipArchive.open(ByteArraySource(bytes)).use { it.entries.map { e -> e.name to e.localHeaderOffset } }
        // Set the "encrypted" flag of every inner APK in the central directory.
        val cd = String(bytes, Charsets.ISO_8859_1)
        var at = cd.indexOf("PK")
        while (at >= 0) {
            val nameLength = (bytes[at + 28].toInt() and 0xFF) or ((bytes[at + 29].toInt() and 0xFF) shl 8)
            val name = String(bytes, at + 46, nameLength, Charsets.UTF_8)
            if (name.endsWith(".apk")) bytes[at + 8] = (bytes[at + 8].toInt() or 1).toByte()
            at = cd.indexOf("PK", at + 4)
        }
        assertTrue(zip.isNotEmpty())
        val file = folder().resolve("locked.apkm")
        Files.write(file, bytes)
        assertEquals(FailureCode.ENCRYPTED_FILE, failure(file).code)
    }

    @Test
    fun `one supported copy among other files is chosen and the others are listed`() {
        val original = Fixtures.writeSplitSet(folder())
        Files.write(original.resolve("screenshots.zip"), Fixtures.zip(mapOf("a.png" to byteArrayOf(1))))
        Files.writeString(original.resolve("notes.txt"), "my notes")
        Files.write(original.resolve("old.xapk"), Fixtures.zip(linkedMapOf("old.apk" to Fixtures.base(split = false, versionCode = 5))))
        scanner.identify(original).use { input ->
            assertEquals("the 3 APK files", input.displayName)
            assertEquals(3, input.notes.size, input.notes.joinToString("\n"))
            assertTrue(input.notes.any { it.contains("screenshots.zip") })
            assertTrue(input.notes.any { it.contains("old.xapk") && it.contains("version code 5") })
            assertTrue(input.notes.any { it.contains("notes.txt") })
        }
    }

    @Test
    fun `two identical copies patch the first and name the duplicate`() {
        val original = folder()
        Files.write(original.resolve("a.xapk"), Fixtures.xapk())
        Files.write(original.resolve("b.xapk"), Fixtures.xapk())
        scanner.identify(original).use { input ->
            assertEquals("a.xapk", input.displayName)
            assertTrue(input.notes.single().contains("the same copy as a.xapk"))
        }
    }

    @Test
    fun `two different supported copies must be reduced to one`() {
        val original = folder()
        Files.write(original.resolve("a.xapk"), Fixtures.xapk())
        Files.write(original.resolve("b.apk"), Fixtures.base(split = false))
        assertEquals(FailureCode.SEVERAL_SUPPORTED, failure(original).code)
    }

    @Test
    fun `several unsupported files list every reason`() {
        val original = folder()
        Files.write(original.resolve("a.zip"), Fixtures.zip(mapOf("x.txt" to byteArrayOf(1))))
        Files.write(original.resolve("b.apk"), Fixtures.base(split = false, versionCode = 3))
        val f = failure(original)
        assertEquals(FailureCode.NO_SUPPORTED_FILE, f.code)
        assertEquals(2, f.details.size)
    }

    @Test
    fun `a path that does not exist has its own message`() {
        assertEquals(FailureCode.INPUT_NOT_FOUND, failure(dir.resolve("missing")).code)
    }

    @Test
    fun `scanning writes nothing and changes nothing in the input folder`() {
        val original = folder()
        Fixtures.writeSplitSet(original)
        Files.write(original.resolve("Game.apkm"), Fixtures.apkmDeflated())
        fun snapshot() = Files.list(original).use { s -> s.sorted().toList() }.associate { it.fileName.toString() to Hashing.sha256(it) }
        val before = snapshot()
        scanner.identify(original).close()
        assertEquals(before, snapshot())
    }

    @Test
    fun `the bundled definition describes Pocket Knights 4_4_9`() {
        val s = SupportedInput.bundled
        assertEquals("com.enjoygame.hero2d", s.packageName)
        assertEquals(450, s.versionCode)
        assertEquals("4.4.9", s.versionName)
        assertEquals(194, s.tables.size)
        assertEquals(setOf("classes.dex", "classes2.dex"), s.dex.keys)
        assertNotNull(s.tables["hero.csv"])
        assertFalse(s.tables.keys.any { !it.endsWith(".csv") })
    }
}
