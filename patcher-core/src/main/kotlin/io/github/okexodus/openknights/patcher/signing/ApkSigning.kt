package io.github.okexodus.openknights.patcher.signing

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.android.apksig.KeyConfig
import io.github.okexodus.openknights.patcher.FailureCode
import io.github.okexodus.openknights.patcher.PatchFailure
import io.github.okexodus.openknights.patcher.util.Hashing
import io.github.okexodus.openknights.patcher.zip.FileSource
import io.github.okexodus.openknights.patcher.zip.ZipArchive
import io.github.okexodus.openknights.patcher.zip.ZipEntry
import java.nio.file.Path

/** Signs an APK with APK Signature Schemes v1, v2 and v3 (apksig, in-process) and checks the result. */
object ApkSigning {
    /** Stored native libraries start at a 16 KB boundary; other stored entries at 4 bytes. */
    const val LIBRARY_ALIGNMENT = 16384
    /** The v1 signature files are `META-INF/OPENKNIG.SF` / `.RSA` (JAR signer names are 8 characters). */
    const val SIGNER_NAME = "OPENKNIG"

    data class Verification(
        val v1: Boolean,
        val v2: Boolean,
        val v3: Boolean,
        val certificateSha256: String,
        val warnings: List<String>,
    )

    fun sign(unsigned: Path, signed: Path, key: SigningKey, createdBy: String) {
        val signer = ApkSigner.SignerConfig.Builder(SIGNER_NAME, KeyConfig.Jca(key.privateKey), listOf(key.certificate)).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(unsigned.toFile())
            .setOutputApk(signed.toFile())
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .setCreatedBy(createdBy)
            .setAlignmentPreserved(false)
            .setLibraryPageAlignmentBytes(LIBRARY_ALIGNMENT)
            .build()
            .sign()
    }

    /** Verifies every scheme and the alignment; throws when anything is wrong. */
    fun verify(apk: Path, expectedCertificateSha256: String? = null): Verification {
        val result = ApkVerifier.Builder(apk.toFile()).build().verify()
        if (!result.isVerified || result.containsErrors()) {
            throw PatchFailure(FailureCode.SIGNATURE_CHECK_FAILED,
                "The signed app did not pass the signature check: ${result.allErrors.take(3).joinToString("; ")}.")
        }
        if (!result.isVerifiedUsingV1Scheme || !result.isVerifiedUsingV2Scheme || !result.isVerifiedUsingV3Scheme) {
            throw PatchFailure(FailureCode.SIGNATURE_CHECK_FAILED, "The signed app is missing a signature scheme (v1 ${result.isVerifiedUsingV1Scheme}, " +
                "v2 ${result.isVerifiedUsingV2Scheme}, v3 ${result.isVerifiedUsingV3Scheme}).")
        }
        val certificate = Hashing.sha256(result.signerCertificates.single().encoded)
        if (expectedCertificateSha256 != null && certificate != expectedCertificateSha256) {
            throw PatchFailure(FailureCode.SIGNATURE_CHECK_FAILED, "The app is signed with another certificate ($certificate).")
        }
        val alignment = alignmentProblems(apk)
        if (alignment.isNotEmpty()) {
            throw PatchFailure(FailureCode.SIGNATURE_CHECK_FAILED, "The app's files are not aligned: ${alignment.take(3).joinToString("; ")}.")
        }
        val warnings = result.warnings.map { it.toString() } +
            result.v1SchemeSigners.flatMap { s -> s.warnings.map { it.toString() } } +
            result.v2SchemeSigners.flatMap { s -> s.warnings.map { it.toString() } } +
            result.v3SchemeSigners.flatMap { s -> s.warnings.map { it.toString() } }
        return Verification(true, true, true, certificate, warnings)
    }

    /** Entries stored without compression whose data does not start on their boundary. */
    fun alignmentProblems(apk: Path): List<String> = FileSource.open(apk).use { source ->
        ZipArchive.open(source).entries.filter { it.method == ZipEntry.STORED }.mapNotNull { entry ->
            val boundary = if (entry.name.endsWith(".so")) LIBRARY_ALIGNMENT else 4
            if (entry.dataOffset % boundary == 0L) null else "${entry.name} at ${entry.dataOffset} (needs $boundary)"
        }
    }
}
