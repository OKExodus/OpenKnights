package io.github.okexodus.openknights.patcher.cli

import io.github.okexodus.openknights.patcher.ApkPatcher
import io.github.okexodus.openknights.patcher.BuildInfo
import io.github.okexodus.openknights.patcher.FailureCode
import io.github.okexodus.openknights.patcher.FixtureGame
import io.github.okexodus.openknights.patcher.Patcher
import io.github.okexodus.openknights.patcher.signing.KeyStorage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class CliTest {
    @TempDir
    lateinit var dir: Path

    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val keys get() = KeyStorage(dir.resolve("profile"))

    private fun cli() = Cli(PrintStream(out, true), PrintStream(err, true), keys, dir.resolve("patcher")) { options, keys, _ ->
        Patcher(options, keys, FixtureGame.supported, ApkPatcher(options, FixtureGame.nativePatches))
    }

    private fun run(vararg args: String): Int = cli().run(args.toList())

    @Test
    fun `the first run makes the two folders and explains them`() {
        assertEquals(FailureCode.EMPTY_FOLDER.exitCode, run())
        assertTrue(Files.isDirectory(dir.resolve("patcher/original")))
        assertTrue(Files.isDirectory(dir.resolve("patcher/patched")))
        assertTrue(out.toString().contains("Put your copy of Pocket Knights 4.4.9 in \"original\""))
    }

    @Test
    fun `an empty original folder has its own message and exit code`() {
        Files.createDirectories(dir.resolve("patcher/original"))
        assertEquals(FailureCode.EMPTY_FOLDER.exitCode, run())
        assertTrue(err.toString().contains("is empty"))
    }

    @Test
    fun `the folder flow patches the game next to the patcher`() {
        FixtureGame.writeSplitSet(dir.resolve("patcher/original"))
        assertEquals(0, run(), err.toString())
        val apk = dir.resolve("patcher/patched/OpenKnights-${BuildInfo.version}.apk")
        assertTrue(Files.isRegularFile(apk))
        val text = out.toString()
        assertTrue(text.contains("Done: $apk"))
        assertTrue(text.contains("Your personal signing key was created"))
        assertTrue(text.contains("must be uninstalled"))
        // Second run: the same key is reused and said so.
        out.reset()
        assertEquals(0, run())
        assertTrue(out.toString().contains("Signed with your signing key"))
        assertTrue(out.toString().contains("Already up to date"))
    }

    @Test
    fun `explicit input, output and report paths`() {
        val input = FixtureGame.writeSplitSet(dir.resolve("my game"))
        val report = dir.resolve("copy.json")
        assertEquals(0, run("patch", "--input", input.toString(), "--output", dir.resolve("out").toString(), "--report", report.toString(), "--dev-server"), err.toString())
        assertTrue(Files.isRegularFile(dir.resolve("out/OpenKnights-${BuildInfo.version}.apk")))
        assertTrue(Files.readString(report).contains("\"patcher_version\""))
        assertFalse(Files.exists(dir.resolve("patcher/original")), "no default folders when paths are given")
    }

    @Test
    fun `key show, export and import`() {
        assertEquals(0, run("key", "show"))
        assertTrue(out.toString().contains("There is no signing key yet"))
        keys.loadOrCreate()
        out.reset()
        assertEquals(0, run("key"))
        assertTrue(out.toString().contains("Certificate SHA-256: ${keys.load().certificateSha256}"))
        val backup = dir.resolve("backup.p12")
        assertEquals(0, run("key", "export", backup.toString()))
        assertEquals(FailureCode.SIGNING_KEY_PROBLEM.exitCode, run("key", "export", backup.toString()), "never overwrites")
        val other = KeyStorage(dir.resolve("other")).also { it.loadOrCreate() }
        val otherBackup = dir.resolve("other.p12").also { other.export(it) }
        assertEquals(FailureCode.SIGNING_KEY_PROBLEM.exitCode, run("key", "import", otherBackup.toString()))
        assertTrue(err.toString().contains("--replace"))
        assertEquals(0, run("key", "import", otherBackup.toString(), "--replace"))
        assertEquals(other.load().certificateSha256, keys.load().certificateSha256)
    }

    @Test
    fun `usage errors, help and version`() {
        assertEquals(2, run("patch", "--colour"))
        assertEquals(2, run("patch", "--input"))
        assertEquals(2, run("frobnicate"))
        assertEquals(2, run("key", "burn"))
        assertEquals(0, run("--help"))
        assertTrue(out.toString().contains("key export <file>"))
        out.reset()
        assertEquals(0, run("--version"))
        assertEquals("OpenKnights Patcher ${BuildInfo.version}", out.toString().trim())
    }
}
