package io.github.okexodus.openknights.patcher.signing

import io.github.okexodus.openknights.patcher.FailureCode
import io.github.okexodus.openknights.patcher.Fixtures
import io.github.okexodus.openknights.patcher.PatchFailure
import io.github.okexodus.openknights.patcher.util.Hashing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.interfaces.RSAPublicKey

class SigningTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the key is made once and then reused`() {
        val storage = KeyStorage(dir.resolve("profile"))
        val (first, created) = storage.loadOrCreate()
        assertTrue(created)
        val (second, again) = storage.loadOrCreate()
        assertFalse(again)
        assertEquals(first.certificateSha256, second.certificateSha256)
        assertEquals(4096, (first.certificate.publicKey as RSAPublicKey).modulus.bitLength())
        first.certificate.verify(first.certificate.publicKey)
        assertTrue(first.certificate.subjectX500Principal.name.contains("CN=OpenKnights"))
        assertTrue(first.certificate.notAfter.toInstant().isAfter(java.time.Instant.now().plus(java.time.Duration.ofDays(365L * 49))))
        assertTrue(Files.list(storage.directory).use { s -> s.toList() }.map { it.fileName.toString() } == listOf(KeyStorage.FILE_NAME), "no temporary files left")
    }

    @Test
    fun `export copies the key and never overwrites`() {
        val storage = KeyStorage(dir.resolve("profile"))
        val key = storage.loadOrCreate().first
        val backup = dir.resolve("backup.p12")
        storage.export(backup)
        assertEquals(key.certificateSha256, KeyStorage.load(backup).certificateSha256)
        assertEquals(FailureCode.SIGNING_KEY_PROBLEM, assertThrows<PatchFailure> { storage.export(backup) }.code)
    }

    @Test
    fun `import on a new machine installs the backup`() {
        val old = KeyStorage(dir.resolve("old")).also { it.loadOrCreate() }
        val backup = dir.resolve("backup.p12").also { old.export(it) }
        val fresh = KeyStorage(dir.resolve("new"))
        assertEquals(old.load().certificateSha256, fresh.import(backup, replace = false).certificateSha256)
        assertEquals(old.load().certificateSha256, fresh.load().certificateSha256)
    }

    @Test
    fun `importing a different key needs replace and keeps the old key aside`() {
        val mine = KeyStorage(dir.resolve("mine")).also { it.loadOrCreate() }
        val other = KeyStorage(dir.resolve("other")).also { it.loadOrCreate() }
        val backup = dir.resolve("other.p12").also { other.export(it) }
        val before = mine.load().certificateSha256
        assertEquals(FailureCode.SIGNING_KEY_PROBLEM, assertThrows<PatchFailure> { mine.import(backup, replace = false) }.code)
        assertEquals(before, mine.load().certificateSha256, "nothing changed")
        mine.import(backup, replace = true)
        assertEquals(other.load().certificateSha256, mine.load().certificateSha256)
        val aside = Files.list(mine.directory).use { s -> s.toList() }.map { it.fileName.toString() }.filter { it.contains("replaced") }
        assertEquals(1, aside.size)
        assertEquals(before, KeyStorage.load(mine.directory.resolve(aside.single())).certificateSha256)
    }

    @Test
    fun `a file that is not a key is refused`() {
        val junk = dir.resolve("junk.p12").also { Files.writeString(it, "not a key") }
        assertEquals(FailureCode.SIGNING_KEY_PROBLEM, assertThrows<PatchFailure> { KeyStorage(dir.resolve("p")).import(junk, false) }.code)
    }

    @Test
    fun `signing gives v1 v2 and v3 signatures, aligned and repeatable`() {
        val key = KeyStorage(dir.resolve("profile")).loadOrCreate().first
        val unsigned = dir.resolve("unsigned.apk")
        Files.write(unsigned, Fixtures.base(split = false))
        val signed = dir.resolve("signed.apk")
        ApkSigning.sign(unsigned, signed, key, "OpenKnights Patcher test")
        val check = ApkSigning.verify(signed, key.certificateSha256)
        assertTrue(check.v1 && check.v2 && check.v3)
        val again = dir.resolve("again.apk")
        ApkSigning.sign(unsigned, again, key, "OpenKnights Patcher test")
        assertEquals(Hashing.sha256(signed), Hashing.sha256(again), "the same input and key give the same bytes")
        val otherKey = KeyStorage(dir.resolve("other")).loadOrCreate().first
        assertThrows<PatchFailure> { ApkSigning.verify(signed, otherKey.certificateSha256) }
        assertNotEquals(key.certificateSha256, otherKey.certificateSha256)
    }

    @Test
    fun `the default key folder is in the user profile`() {
        val folder = KeyStorage.defaultDirectory().toString()
        assertTrue(folder.endsWith("OpenKnights") || folder.endsWith("openknights") || System.getenv("OPENKNIGHTS_KEY_DIR") != null, folder)
    }
}
