package io.github.okexodus.openknights.patcher.signing

import io.github.okexodus.openknights.patcher.FailureCode
import io.github.okexodus.openknights.patcher.PatchFailure
import io.github.okexodus.openknights.patcher.util.Hashing
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** The key that signs a player's OpenKnights app. */
class SigningKey(val privateKey: PrivateKey, val certificate: X509Certificate) {
    /** SHA-256 of the certificate: the same for every app this key signs. */
    val certificateSha256: String get() = Hashing.sha256(certificate.encoded)
}

/**
 * The player's own signing key, kept in their user profile (not next to the patcher), so every patcher version finds
 * and reuses it and updates install over the earlier app. Android accepts an update only when it is signed with the
 * same key as the installed app.
 *
 * The key is an RSA 4096-bit key with a self-signed certificate in a PKCS #12 file, protected by the fixed password
 * [PASSWORD] (the file itself is what must be kept safe and backed up).
 */
class KeyStorage(val directory: Path = defaultDirectory()) {
    val file: Path get() = directory.resolve(FILE_NAME)

    fun exists(): Boolean = Files.isRegularFile(file)

    /** The stored key, created on first use; [created] tells the caller to show the first-run message. */
    fun loadOrCreate(): Pair<SigningKey, Boolean> {
        if (exists()) return load(file) to false
        val key = generate()
        write(key, file)
        return key to true
    }

    fun load(): SigningKey = load(file)

    /** Copies the key file to [target] (never over an existing file). */
    fun export(target: Path) {
        if (!exists()) throw PatchFailure(FailureCode.SIGNING_KEY_PROBLEM, "There is no signing key yet at $file. Patch a game first; the key is made then.")
        try {
            Files.copy(file, target)
        } catch (_: FileAlreadyExistsException) {
            throw PatchFailure(FailureCode.SIGNING_KEY_PROBLEM, "$target already exists. Choose a new file name for the backup.")
        }
    }

    /**
     * Installs the key from [source]. An existing, different key is kept aside as a dated copy and replaced only with
     * [replace]: apps signed with the old key can then not be updated with new patches.
     */
    fun import(source: Path, replace: Boolean): SigningKey {
        val key = try { load(source) } catch (e: PatchFailure) {
            throw PatchFailure(FailureCode.SIGNING_KEY_PROBLEM, "$source is not an OpenKnights signing key: ${e.message}", cause = e)
        }
        if (exists()) {
            val current = load()
            if (current.certificateSha256 == key.certificateSha256) return current
            if (!replace) {
                throw PatchFailure(FailureCode.SIGNING_KEY_PROBLEM,
                    "You already have a different signing key ($file). Importing replaces it, and apps signed with the current key could " +
                        "then only be updated after uninstalling them (which deletes their saves). Run the import again with --replace to do it anyway.")
            }
            val aside = directory.resolve("${FILE_NAME.removeSuffix(".p12")}-replaced-${ZonedDateTime.now(ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))}.p12")
            Files.move(file, aside)
        }
        Files.createDirectories(directory)
        Files.copy(source, file, StandardCopyOption.REPLACE_EXISTING)
        restrict(file)
        return key
    }

    companion object {
        const val FILE_NAME = "openknights-signing-key.p12"
        const val ALIAS = "openknights"
        /** Fixed and public: the file itself is the secret (it lives in the player's own profile). */
        const val PASSWORD = "openknights"
        private const val KEY_BITS = 4096

        /** `%APPDATA%\OpenKnights` on Windows; `$XDG_DATA_HOME/openknights` or `~/.local/share/openknights` elsewhere. */
        fun defaultDirectory(): Path {
            System.getenv("OPENKNIGHTS_KEY_DIR")?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
            val windows = System.getProperty("os.name").lowercase().startsWith("windows")
            if (windows) {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: Path.of(System.getProperty("user.home"), "AppData", "Roaming").toString()
                return Path.of(appData, "OpenKnights")
            }
            val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            return if (xdg != null) Path.of(xdg, "openknights") else Path.of(System.getProperty("user.home"), ".local", "share", "openknights")
        }

        fun generate(random: SecureRandom = SecureRandom()): SigningKey {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS, random) }.generateKeyPair()
            val now = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
            val name = Der.sequence(Der.set(Der.sequence(Der.oid("2.5.4.3"), Der.utf8("OpenKnights"))),
                Der.set(Der.sequence(Der.oid("2.5.4.11"), Der.utf8("Player signing key"))))
            val algorithm = Der.sequence(Der.oid("1.2.840.113549.1.1.11"), Der.nullValue())   // sha256WithRSAEncryption
            val serial = BigInteger(127, random).add(BigInteger.ONE)
            val tbs = Der.sequence(
                Der.explicit(0, Der.integer(BigInteger.TWO)),                                  // version 3
                Der.integer(serial),
                algorithm,
                name,
                Der.sequence(Der.time(now), Der.time(now.plusYears(50))),
                name,
                pair.public.encoded,
            )
            val signature = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(tbs); sign() }
            val der = Der.sequence(tbs, algorithm, Der.bitString(signature))
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            certificate.verify(pair.public)
            return SigningKey(pair.private, certificate)
        }

        fun load(path: Path): SigningKey {
            val store = try {
                KeyStore.getInstance("PKCS12").apply { Files.newInputStream(path).use { load(it, PASSWORD.toCharArray()) } }
            } catch (e: Exception) {
                throw PatchFailure(FailureCode.SIGNING_KEY_PROBLEM, "The signing key file $path cannot be read (${e.message}).", cause = e)
            }
            val alias = store.aliases().toList().singleOrNull { store.isKeyEntry(it) }
                ?: throw PatchFailure(FailureCode.SIGNING_KEY_PROBLEM, "The signing key file $path must hold exactly one key.")
            val key = store.getKey(alias, PASSWORD.toCharArray()) as? PrivateKey
                ?: throw PatchFailure(FailureCode.SIGNING_KEY_PROBLEM, "The signing key file $path holds no private key.")
            val certificate = store.getCertificate(alias) as? X509Certificate
                ?: throw PatchFailure(FailureCode.SIGNING_KEY_PROBLEM, "The signing key file $path holds no certificate.")
            if (key !is RSAPrivateCrtKey || key.modulus != (certificate.publicKey as java.security.interfaces.RSAPublicKey).modulus) {
                throw PatchFailure(FailureCode.SIGNING_KEY_PROBLEM, "The key and certificate in $path do not belong together.")
            }
            return SigningKey(key, certificate)
        }

        /** Writes [key] to [path] through a temporary file, so a crash never leaves half a key file. */
        fun write(key: SigningKey, path: Path) {
            Files.createDirectories(path.parent)
            val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
            store.setKeyEntry(ALIAS, key.privateKey, PASSWORD.toCharArray(), arrayOf(key.certificate))
            val temp = Files.createTempFile(path.parent, ".openknights-key-", ".tmp")
            try {
                Files.newOutputStream(temp).use { store.store(it, PASSWORD.toCharArray()) }
                restrict(temp)
                try {
                    Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, path)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        }

        private fun restrict(path: Path) {
            runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
        }
    }
}
